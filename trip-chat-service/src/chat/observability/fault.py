"""Application-layer fault injection framework (chat-service mirror).

This module is intentionally kept in lockstep with
``trip-itinerary-planner/src/itinerary_planner/observability/fault.py``
so the two services expose the same DSL and Span semantics.  When a
shared library becomes worthwhile, both copies should be moved into
``libs/tripsphere`` and re-exported from here.

It is the runtime data plane for the design captured in
``docs/langgraph-fault-injection-report.md``.  It implements:

* A compact DSL for declaring faults (``tool.geocoding.latency=8000;``
  ``rpc.itinerary.create.error=UNAVAILABLE``).
* A thread-safe :class:`FaultRegistry` that merges process-wide faults
  (loaded from environment / JSON file at startup) with per-request
  faults (carried in the ``x-fault-scenario`` header).
* A :class:`contextvars.ContextVar` based :class:`FaultContext` so deeply
  nested code (LangGraph nodes, gRPC clients, tools) can read the
  current request scope without threading headers through every call.
* :func:`inject_fault`: an async context manager that performs pre-call
  faults (latency / exception / gRPC error).
* :func:`maybe_mutate`: a post-call helper that mutates returned values
  for response tampering experiments.
* :func:`should_clear_state`, :func:`force_route_decision`,
  :func:`should_drop`: synchronous helpers for state, routing and
  cross-agent fault primitives.
* :func:`invoke_with_fault`: a convenience wrapper for
  ``await runnable.ainvoke(...)`` that combines pre-call and post-call
  injection in a single line.

Design invariants
-----------------
* When the global switch ``FAULT_INJECTION_ENABLED`` is *not* truthy,
  every public entry point is a near-zero-cost fast path -- no parsing,
  no contextvar lookup beyond a single boolean check.
* The framework MUST never crash a production request because of its
  own bugs.  All evaluation is wrapped in ``try/except`` and the
  framework's own errors are recorded onto the current OTel span as
  ``fault.injector_error`` instead of being raised.
* Every triggered fault is observable: the current span gains
  ``fault.injected=true`` together with ``fault.target``,
  ``fault.primitive``, ``fault.outcome``, ``fault.experiment_id`` and
  per-primitive parameter attributes; a ``fault.inject`` Span Event is
  also added for time-axis visibility in Tempo.
"""

from __future__ import annotations

import asyncio
import contextvars
import json
import logging
import os
import random
import threading
from collections.abc import AsyncIterator, Mapping
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Callable, TypeVar

from opentelemetry import trace
from opentelemetry.trace import Span

logger = logging.getLogger(__name__)

T = TypeVar("T")

# ─────────────────────────────────────────────────────────────────────────────
# Public enums and dataclasses
# ─────────────────────────────────────────────────────────────────────────────


class FaultPrimitive(str, Enum):
    """The set of fault primitives understood by the framework."""

    LATENCY = "latency"
    EXCEPTION = "exception"
    GRPC_ERROR = "error"  # alias `grpc_error`; ``error`` keeps the DSL short
    MUTATE = "mutate"
    CLEAR = "clear"
    FORCE_ROUTE = "force_route"
    DROP = "drop"

    @classmethod
    def from_token(cls, token: str) -> "FaultPrimitive | None":
        token_normalised = token.strip().lower()
        if token_normalised == "grpc_error":
            return cls.GRPC_ERROR
        for member in cls:
            if member.value == token_normalised:
                return member
        return None


@dataclass(frozen=True)
class FaultSpec:
    """A single fault declaration.

    A spec is the smallest reproducible unit of an experiment: a target
    (``tool.geocoding``), a primitive (``latency``) and parameters
    (``{"value": 8000, "jitter": 200}``).
    """

    target: str
    primitive: FaultPrimitive
    params: Mapping[str, Any] = field(default_factory=dict)
    probability: float = 1.0
    experiment_id: str | None = None


# ─────────────────────────────────────────────────────────────────────────────
# DSL parsing
# ─────────────────────────────────────────────────────────────────────────────


def _parse_scalar(text: str) -> Any:
    text = text.strip()
    if not text:
        return ""
    lowered = text.lower()
    if lowered in ("true", "false"):
        return lowered == "true"
    try:
        return int(text)
    except ValueError:
        pass
    try:
        return float(text)
    except ValueError:
        pass
    return text


def _parse_params(rhs: str) -> dict[str, Any]:
    """Parse the right-hand side of a DSL entry into a params dict.

    The first positional token (no ``=``) becomes ``value``; subsequent
    positional tokens are stored as ``arg1``, ``arg2`` etc.; ``key=val``
    tokens are stored verbatim.
    """
    params: dict[str, Any] = {}
    parts = [p for p in (s.strip() for s in rhs.split(",")) if p]
    positional_index = 0
    for part in parts:
        if "=" in part:
            key, raw_value = part.split("=", 1)
            params[key.strip()] = _parse_scalar(raw_value)
        else:
            key = "value" if positional_index == 0 else f"arg{positional_index}"
            params[key] = _parse_scalar(part)
            positional_index += 1
    return params


def parse_scenario(text: str | None) -> list[FaultSpec]:
    """Parse a scenario expression into a list of :class:`FaultSpec`.

    Grammar (one entry per ``;`` separated chunk)::

        <target>.<primitive>=<arg1>[,<arg2>...][,key=val,...]

    Unknown / malformed chunks are silently skipped (with a debug log)
    so that an experimenter typo never crashes a request.
    """
    if not text:
        return []
    specs: list[FaultSpec] = []
    for chunk in text.split(";"):
        entry = chunk.strip()
        if not entry:
            continue
        if "=" not in entry:
            logger.debug("fault scenario chunk missing '=': %r", entry)
            continue
        lhs, rhs = entry.split("=", 1)
        if "." not in lhs:
            logger.debug("fault scenario LHS missing primitive suffix: %r", lhs)
            continue
        target_part, primitive_token = lhs.rsplit(".", 1)
        target = target_part.strip()
        primitive = FaultPrimitive.from_token(primitive_token)
        if not target or primitive is None:
            logger.debug("unknown primitive %r in scenario chunk %r", primitive_token, entry)
            continue
        params = _parse_params(rhs)
        raw_probability = params.pop("probability", 1.0)
        try:
            probability = float(raw_probability)
        except (TypeError, ValueError):
            probability = 1.0
        experiment_id = params.pop("experiment_id", None)
        specs.append(
            FaultSpec(
                target=target,
                primitive=primitive,
                params=params,
                probability=max(0.0, min(1.0, probability)),
                experiment_id=str(experiment_id) if experiment_id else None,
            )
        )
    return specs


# ─────────────────────────────────────────────────────────────────────────────
# Registry (process-wide static config)
# ─────────────────────────────────────────────────────────────────────────────


class FaultRegistry:
    """Process-wide registry of static fault specs and a global switch.

    Per-request DSL specs live in :class:`FaultContext` instead and are
    *merged in* by :func:`_resolve_specs_for`.  Keeping these two stores
    separate makes it possible for an operator to ship a baseline
    experiment via env / file while individual requests opt into
    additional faults via headers.
    """

    _instance: "FaultRegistry | None" = None
    _instance_lock = threading.Lock()

    def __init__(self) -> None:
        self._enabled: bool = False
        self._specs_by_target: dict[str, list[FaultSpec]] = {}

    # ── Singleton accessors ────────────────────────────────────────────────

    @classmethod
    def instance(cls) -> "FaultRegistry":
        if cls._instance is None:
            with cls._instance_lock:
                if cls._instance is None:
                    cls._instance = cls()
        return cls._instance

    @classmethod
    def reset_for_tests(cls) -> None:
        with cls._instance_lock:
            cls._instance = None

    # ── Bootstrap ──────────────────────────────────────────────────────────

    def bootstrap(self) -> None:
        """Read the environment / config file and populate the registry."""
        self._enabled = _env_truthy(os.getenv("FAULT_INJECTION_ENABLED"))
        self._specs_by_target = {}
        if not self._enabled:
            logger.info("fault injection disabled (FAULT_INJECTION_ENABLED unset)")
            return

        scenario_env = os.getenv("FAULT_INJECTION_SCENARIO", "")
        for spec in parse_scenario(scenario_env):
            self._specs_by_target.setdefault(spec.target, []).append(spec)

        config_path = os.getenv("FAULT_INJECTION_FILE")
        if config_path:
            try:
                self._load_from_file(Path(config_path))
            except Exception as exc:  # noqa: BLE001 - never crash bootstrap
                logger.warning(
                    "failed to load fault config from %s: %s", config_path, exc
                )

        logger.info(
            "fault injection enabled with %d static spec(s) for %d target(s)",
            sum(len(v) for v in self._specs_by_target.values()),
            len(self._specs_by_target),
        )

    def _load_from_file(self, path: Path) -> None:
        raw = path.read_text(encoding="utf-8")
        data = json.loads(raw)
        if not isinstance(data, list):
            raise ValueError("fault config file must be a JSON array of specs")
        for entry in data:
            if not isinstance(entry, dict):
                continue
            target = entry.get("target")
            primitive_token = entry.get("primitive")
            if not isinstance(target, str) or not isinstance(primitive_token, str):
                continue
            primitive = FaultPrimitive.from_token(primitive_token)
            if primitive is None:
                continue
            params = entry.get("params") or {}
            if not isinstance(params, dict):
                params = {}
            spec = FaultSpec(
                target=target,
                primitive=primitive,
                params=params,
                probability=float(entry.get("probability", 1.0) or 1.0),
                experiment_id=(
                    str(entry["experiment_id"]) if entry.get("experiment_id") else None
                ),
            )
            self._specs_by_target.setdefault(target, []).append(spec)

    # ── Query API ──────────────────────────────────────────────────────────

    def is_enabled(self) -> bool:
        return self._enabled

    def specs_for(self, target: str) -> list[FaultSpec]:
        return list(self._specs_by_target.get(target, ()))


def _env_truthy(value: str | None) -> bool:
    if value is None:
        return False
    return value.strip().lower() in ("1", "true", "yes", "on")


def is_enabled() -> bool:
    """Module-level convenience for the registry's enabled flag."""
    return FaultRegistry.instance().is_enabled()


# ─────────────────────────────────────────────────────────────────────────────
# Per-request context (ContextVar)
# ─────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class FaultContext:
    """Per-request fault scope captured from the entry middleware."""

    experiment_id: str | None = None
    fault_scenario: str | None = None
    scenario_specs: tuple[FaultSpec, ...] = ()


_FAULT_CONTEXT: contextvars.ContextVar["FaultContext | None"] = contextvars.ContextVar(
    "tripsphere_fault_context", default=None
)


def _extract_header(headers: Mapping[str, Any] | None, *names: str) -> str | None:
    if headers is None:
        return None
    for name in names:
        value = headers.get(name)
        if value is None:
            value = headers.get(name.lower())
        if value is None and "-" in name:
            value = headers.get(name.replace("-", "_"))
        if value is not None:
            text = str(value).strip()
            if text:
                return text
    return None


def set_fault_context(
    headers: Mapping[str, Any] | None,
) -> contextvars.Token["FaultContext | None"]:
    """Install a :class:`FaultContext` derived from request headers.

    The returned token MUST be passed to :func:`reset_fault_context`
    when the scope ends, otherwise the context will leak between
    requests served by the same worker.
    """
    experiment_id = _extract_header(headers, "x-experiment-id", "experiment_id")
    fault_scenario = _extract_header(headers, "x-fault-scenario", "fault_scenario")
    specs = tuple(parse_scenario(fault_scenario)) if fault_scenario else ()
    ctx = FaultContext(
        experiment_id=experiment_id,
        fault_scenario=fault_scenario,
        scenario_specs=specs,
    )
    return _FAULT_CONTEXT.set(ctx)


def reset_fault_context(token: contextvars.Token["FaultContext | None"]) -> None:
    _FAULT_CONTEXT.reset(token)


def get_fault_context() -> FaultContext | None:
    return _FAULT_CONTEXT.get()


# ─────────────────────────────────────────────────────────────────────────────
# Spec resolution
# ─────────────────────────────────────────────────────────────────────────────


def _resolve_specs_for(
    target: str,
    override_headers: Mapping[str, Any] | None = None,
) -> list[FaultSpec]:
    """Aggregate active specs from registry + context + override headers."""
    if not is_enabled():
        return []
    specs: list[FaultSpec] = list(FaultRegistry.instance().specs_for(target))
    ctx = get_fault_context()
    if ctx is not None and ctx.scenario_specs:
        specs.extend(s for s in ctx.scenario_specs if s.target == target)
    if override_headers is not None:
        scenario = _extract_header(
            override_headers, "x-fault-scenario", "fault_scenario"
        )
        if scenario:
            specs.extend(s for s in parse_scenario(scenario) if s.target == target)
    return specs


def _passes_probability(spec: FaultSpec) -> bool:
    if spec.probability >= 1.0:
        return True
    if spec.probability <= 0.0:
        return False
    return random.random() < spec.probability


# ─────────────────────────────────────────────────────────────────────────────
# Span recording
# ─────────────────────────────────────────────────────────────────────────────


def _current_span() -> Span | None:
    span = trace.get_current_span()
    return span if span.is_recording() else None


def _record_fault(
    span: Span | None,
    target: str,
    spec: FaultSpec,
    outcome: str,
    **extra: Any,
) -> None:
    if span is None:
        return
    try:
        span.set_attribute("fault.injected", True)
        span.set_attribute("fault.target", target)
        span.set_attribute("fault.primitive", spec.primitive.value)
        span.set_attribute("fault.outcome", outcome)
        if spec.experiment_id:
            span.set_attribute("fault.experiment_id", spec.experiment_id)
        ctx = get_fault_context()
        if ctx is not None and ctx.experiment_id:
            span.set_attribute("experiment.id", ctx.experiment_id)
        for k, v in spec.params.items():
            if isinstance(v, (str, int, float, bool)):
                span.set_attribute(f"fault.params.{k}", v)
        event_attrs: dict[str, Any] = {
            "fault.target": target,
            "fault.primitive": spec.primitive.value,
            "fault.outcome": outcome,
        }
        for k, v in extra.items():
            if isinstance(v, (str, int, float, bool)):
                span.set_attribute(f"fault.extra.{k}", v)
                event_attrs[f"fault.extra.{k}"] = v
        span.add_event("fault.inject", event_attrs)
    except Exception as exc:  # noqa: BLE001 - never let observability fail business
        logger.debug("fault span recording failed at %s: %s", target, exc)


def _record_injector_error(span: Span | None, target: str, exc: BaseException) -> None:
    if span is None:
        return
    try:
        span.set_attribute("fault.injector_error", f"{type(exc).__name__}: {exc}")
    except Exception:  # noqa: BLE001
        pass


# ─────────────────────────────────────────────────────────────────────────────
# Pre-call primitives (latency / exception / grpc_error)
# ─────────────────────────────────────────────────────────────────────────────


_SAFE_EXCEPTIONS: dict[str, type[BaseException]] = {
    "RuntimeError": RuntimeError,
    "ValueError": ValueError,
    "TimeoutError": TimeoutError,
    "ConnectionError": ConnectionError,
    "OSError": OSError,
}


def _resolve_exception_class(name: str) -> type[BaseException]:
    return _SAFE_EXCEPTIONS.get(name, RuntimeError)


def _build_grpc_error(code_name: str, details: str) -> BaseException:
    """Build a realistic ``grpc.aio.AioRpcError`` for injection.

    Falls back to a plain :class:`RuntimeError` if grpc is not importable
    in this process (e.g. unit tests on a slim env).
    """
    try:
        import grpc
        from grpc.aio import AioRpcError, Metadata
    except Exception:  # noqa: BLE001
        return RuntimeError(f"injected grpc error {code_name}: {details}")
    code = getattr(grpc.StatusCode, code_name.upper(), grpc.StatusCode.UNKNOWN)
    return AioRpcError(code, Metadata(), Metadata(), details=details)


async def _apply_latency(target: str, spec: FaultSpec, span: Span | None) -> None:
    delay_ms = int(spec.params.get("value") or spec.params.get("delay_ms") or 0)
    jitter_ms = int(spec.params.get("jitter") or spec.params.get("jitter_ms") or 0)
    actual_ms = delay_ms
    if jitter_ms > 0:
        actual_ms += random.randint(-jitter_ms, jitter_ms)
    actual_ms = max(0, actual_ms)
    _record_fault(span, target, spec, "delayed", actual_delay_ms=actual_ms)
    if actual_ms > 0:
        await asyncio.sleep(actual_ms / 1000.0)


def _raise_exception(target: str, spec: FaultSpec, span: Span | None) -> None:
    cls_name = str(spec.params.get("value") or "RuntimeError")
    message = str(spec.params.get("message") or f"injected fault at {target}")
    exc_cls = _resolve_exception_class(cls_name)
    _record_fault(span, target, spec, "exception", exception=cls_name)
    raise exc_cls(message)


def _raise_grpc_error(target: str, spec: FaultSpec, span: Span | None) -> None:
    code_name = str(spec.params.get("value") or "UNAVAILABLE")
    details = str(spec.params.get("message") or f"injected gRPC error at {target}")
    _record_fault(span, target, spec, "grpc_error", code=code_name)
    raise _build_grpc_error(code_name, details)


@asynccontextmanager
async def inject_fault(
    target: str,
    *,
    headers: Mapping[str, Any] | None = None,
) -> AsyncIterator[None]:
    """Async context manager that runs pre-call faults at ``target``.

    Usage::

        async with inject_fault("rpc.itinerary.create", headers=hdrs):
            response = await stub.CreateItinerary(req, metadata=md)

    * ``latency`` sleeps before yielding to the body.
    * ``exception`` / ``error`` raise before yielding (body never runs).
    * Other primitives are no-ops here -- they are evaluated by their
      dedicated helpers (:func:`maybe_mutate`, :func:`should_clear_state`,
      :func:`force_route_decision`, :func:`should_drop`).
    """
    if not is_enabled():
        yield
        return

    try:
        specs = [s for s in _resolve_specs_for(target, headers) if _passes_probability(s)]
    except Exception as exc:  # noqa: BLE001 - never crash business
        _record_injector_error(_current_span(), target, exc)
        yield
        return

    span = _current_span()
    for spec in specs:
        try:
            if spec.primitive is FaultPrimitive.LATENCY:
                await _apply_latency(target, spec, span)
            elif spec.primitive is FaultPrimitive.EXCEPTION:
                _raise_exception(target, spec, span)
            elif spec.primitive is FaultPrimitive.GRPC_ERROR:
                _raise_grpc_error(target, spec, span)
            # Non pre-call primitives intentionally ignored here.
        except (RuntimeError, ValueError, TimeoutError, ConnectionError, OSError) as exc:
            raise exc
        except Exception:
            # _build_grpc_error returned an AioRpcError or similar
            raise

    yield


# ─────────────────────────────────────────────────────────────────────────────
# Post-call primitives (mutate)
# ─────────────────────────────────────────────────────────────────────────────


def _generic_mutate(value: Any, spec: FaultSpec) -> Any:
    """Apply a generic mutation based on ``spec.params``.

    Supported actions (selected via the ``value`` positional or
    ``action`` keyword):

    * ``truncate``: when ``value`` is a list, keep only the first ``n``
      items (default 0).  When it is a Pydantic model with a list field
      named by ``field``, truncate that field.
    * ``blank``: replace the list field named ``field`` with an empty
      list.
    * ``set``: assign ``to`` to the attribute named ``field``.
    * ``noop``: no change (useful for testing the wrapper itself).
    """
    action = str(spec.params.get("value") or spec.params.get("action") or "noop")
    if action == "truncate":
        n = int(spec.params.get("n") or spec.params.get("count") or 0)
        field_name = spec.params.get("field")
        if field_name and hasattr(value, str(field_name)):
            current = getattr(value, str(field_name))
            if isinstance(current, list):
                setattr(value, str(field_name), current[:n])
        elif isinstance(value, list):
            return value[:n]
    elif action == "blank":
        field_name = spec.params.get("field")
        if field_name and hasattr(value, str(field_name)):
            current = getattr(value, str(field_name))
            if isinstance(current, list):
                setattr(value, str(field_name), [])
    elif action == "set":
        field_name = spec.params.get("field")
        new_value = spec.params.get("to")
        if field_name and hasattr(value, str(field_name)):
            setattr(value, str(field_name), new_value)
    return value


def maybe_mutate(
    target: str,
    value: T,
    *,
    headers: Mapping[str, Any] | None = None,
    mutator: Callable[[T, FaultSpec], T] | None = None,
) -> T:
    """Apply ``mutate`` primitives to a return value.

    ``mutator`` lets call sites supply richer, type-aware mutations.
    When omitted, :func:`_generic_mutate` is used.
    """
    if not is_enabled():
        return value
    try:
        specs = [
            s
            for s in _resolve_specs_for(target, headers)
            if s.primitive is FaultPrimitive.MUTATE and _passes_probability(s)
        ]
    except Exception as exc:  # noqa: BLE001
        _record_injector_error(_current_span(), target, exc)
        return value
    if not specs:
        return value

    span = _current_span()
    mutated = value
    for spec in specs:
        try:
            mutated = mutator(mutated, spec) if mutator is not None else _generic_mutate(mutated, spec)
            _record_fault(span, target, spec, "mutated")
        except Exception as exc:  # noqa: BLE001
            _record_injector_error(span, target, exc)
    return mutated


# ─────────────────────────────────────────────────────────────────────────────
# State / routing / agent primitives
# ─────────────────────────────────────────────────────────────────────────────


def should_clear_state(target: str) -> bool:
    """Return ``True`` when a ``clear`` primitive is active at ``target``.

    Used by reducers and tools that hold checkpoint-style state, e.g. to
    test that ``add_day`` rejects writes when ``pending_day_plan`` is
    cleared mid-turn.
    """
    if not is_enabled():
        return False
    specs = [
        s
        for s in _resolve_specs_for(target)
        if s.primitive is FaultPrimitive.CLEAR and _passes_probability(s)
    ]
    if not specs:
        return False
    _record_fault(_current_span(), target, specs[0], "state_cleared")
    return True


def force_route_decision(target: str, default: str) -> str:
    """Optionally override a graph routing decision.

    When a ``force_route`` primitive is active, returns the requested
    branch name; otherwise returns ``default`` unchanged.
    """
    if not is_enabled():
        return default
    specs = [
        s
        for s in _resolve_specs_for(target)
        if s.primitive is FaultPrimitive.FORCE_ROUTE and _passes_probability(s)
    ]
    if not specs:
        return default
    forced = str(specs[0].params.get("value") or default)
    _record_fault(_current_span(), target, specs[0], "route_forced", route=forced)
    return forced


def should_drop(target: str, *, headers: Mapping[str, Any] | None = None) -> bool:
    """Return ``True`` when a ``drop`` primitive is active at ``target``.

    Used by remote-agent resolution to simulate "the sub-agent is gone".
    """
    if not is_enabled():
        return False
    specs = [
        s
        for s in _resolve_specs_for(target, headers)
        if s.primitive is FaultPrimitive.DROP and _passes_probability(s)
    ]
    if not specs:
        return False
    _record_fault(_current_span(), target, specs[0], "dropped")
    return True


# ─────────────────────────────────────────────────────────────────────────────
# Convenience wrappers
# ─────────────────────────────────────────────────────────────────────────────


async def invoke_with_fault(
    target: str,
    runnable: Any,
    *args: Any,
    headers: Mapping[str, Any] | None = None,
    response_target: str | None = None,
    mutator: Callable[[Any, FaultSpec], Any] | None = None,
    **kwargs: Any,
) -> Any:
    """Drop-in replacement for ``await runnable.ainvoke(*args, **kwargs)``.

    Combines :func:`inject_fault` (pre-call) and :func:`maybe_mutate`
    (post-call) so call sites only need a single line change.

    ``response_target`` defaults to ``f"{target}.response"``.
    """
    async with inject_fault(target, headers=headers):
        result = await runnable.ainvoke(*args, **kwargs)
    return maybe_mutate(
        response_target or f"{target}.response",
        result,
        headers=headers,
        mutator=mutator,
    )


__all__ = [
    "FaultContext",
    "FaultPrimitive",
    "FaultRegistry",
    "FaultSpec",
    "force_route_decision",
    "get_fault_context",
    "inject_fault",
    "invoke_with_fault",
    "is_enabled",
    "maybe_mutate",
    "parse_scenario",
    "reset_fault_context",
    "set_fault_context",
    "should_clear_state",
    "should_drop",
]
