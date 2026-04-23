"""FastAPI dependencies for surfacing experiment / fault headers.

These helpers let routers receive the experiment headers that the
fault-injection framework needs without coupling business handlers to
the framework itself.  The router can simply ``Depends`` on
``ExperimentHeaders`` and forward the resulting dict to downstream
gRPC clients via their ``headers=`` keyword.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends, Request


def provide_experiment_headers(request: Request) -> dict[str, str]:
    """Extract the experiment / fault headers from the inbound request.

    Returns an empty dict when no relevant headers are present so the
    caller can pass ``headers=...`` unconditionally.
    """
    out: dict[str, str] = {}
    for key in ("x-experiment-id", "x-fault-scenario"):
        value = request.headers.get(key)
        if value is None:
            continue
        text = value.strip()
        if text:
            out[key] = text
    return out


ExperimentHeaders = Annotated[dict[str, str], Depends(provide_experiment_headers)]
