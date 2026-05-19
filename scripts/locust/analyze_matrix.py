"""TripSphere 实验聚合分析脚本

从多个实验目录（quality.csv + stats_stats.csv）提取指标，生成：
  artifacts/analysis/{timestamp}/
    thesis_numbers.md       ← 论文填数字的主文件
    comparison_table.csv    ← 所有实验所有指标的宽表
    degradation_detail.csv  ← 每实验每降级信号的计数
    charts/
      success_rate.png
      degradation_rate.png
      latency_p95.png
      degradation_signals.png
      quality_metrics.png   （链路 A 专用）

用法：
  # 指定多个目录
  uv run --project scripts/locust python scripts/locust/analyze_matrix.py \\
      artifacts/locust/exp1 artifacts/locust/exp2 ...

  # 按 glob 模式自动收集
  uv run --project scripts/locust python scripts/locust/analyze_matrix.py \\
      --glob "artifacts/locust/locust-chaina-*"

  # 自定义输出目录
  uv run --project scripts/locust python scripts/locust/analyze_matrix.py \\
      --output artifacts/analysis/my_run \\
      --glob "artifacts/locust/locust-chaina-*"
"""

from __future__ import annotations

import argparse
import csv
import glob as glob_module
import os
import sys
import time
import warnings
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

# ── 字体配置（SimHei 缺失时 fallback 到 DejaVu，屏蔽字体查找警告）──────────────

warnings.filterwarnings("ignore", category=UserWarning, module="matplotlib")

import logging
logging.getLogger("matplotlib.font_manager").setLevel(logging.ERROR)

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
from matplotlib import font_manager as _fm

def _probe_font(name: str) -> bool:
    try:
        result = _fm.findfont(name, fallback_to_default=False)  # type: ignore[call-arg]
        return bool(result)
    except Exception:
        pass
    # fallback: check font list directly
    available = {f.name for f in _fm.fontManager.ttflist}
    return name in available

_CHINESE_FONTS = ["WenQuanYi Micro Hei", "Noto Sans CJK SC", "SimHei", "Arial Unicode MS"]
_chosen_font = next((f for f in _CHINESE_FONTS if _probe_font(f)), None)
if _chosen_font:
    matplotlib.rcParams["font.family"] = _chosen_font
else:
    matplotlib.rcParams["font.family"] = "DejaVu Sans"

matplotlib.rcParams["axes.unicode_minus"] = False
matplotlib.rcParams["figure.dpi"] = 150
matplotlib.rcParams["figure.facecolor"] = "white"

# ── 数据加载 ──────────────────────────────────────────────────────────────────

def _load_quality_csv(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as f:
        return list(csv.DictReader(f))


def _load_stats_csv(path: Path) -> list[dict[str, str]]:
    """Locust stats_stats.csv — 取 Name="Aggregated" 行。"""
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as f:
        return list(csv.DictReader(f))


def _find_experiment_dirs(raw_args: list[str], glob_pattern: str | None) -> list[Path]:
    dirs: list[Path] = []
    if glob_pattern:
        matched = sorted(glob_module.glob(glob_pattern, recursive=True))
        dirs.extend(Path(p) for p in matched if Path(p).is_dir())
    for a in raw_args:
        p = Path(a)
        if p.is_dir():
            dirs.append(p)
        else:
            print(f"警告：路径不存在或不是目录，跳过: {a}", file=sys.stderr)
    # 去重，保持顺序
    seen: set[Path] = set()
    result: list[Path] = []
    for d in dirs:
        rd = d.resolve()
        if rd not in seen:
            seen.add(rd)
            result.append(d)
    return result


# ── 指标计算 ──────────────────────────────────────────────────────────────────

def _percentile(vals: list[int | float], pct: float) -> float:
    if not vals:
        return 0.0
    s = sorted(vals)
    idx = max(0, min(int(len(s) * pct / 100 + 0.5) - 1, len(s) - 1))
    return float(s[idx])


def _avg(vals: list[int | float]) -> float:
    return sum(vals) / len(vals) if vals else 0.0


def _compute_quality_metrics(rows: list[dict[str, str]], chain: str) -> dict[str, Any]:
    total = len(rows)
    if total == 0:
        return {}

    error_rows = [r for r in rows if r.get("error") and r.get("error") != ""]
    success_rows = [r for r in rows if r.get("status_code") in ("200", "201")]
    degraded_rows = [r for r in success_rows if r.get("degraded") == "True"]
    clean_rows = [r for r in success_rows if r.get("degraded") != "True"]

    elapsed_all = [int(r["elapsed_ms"]) for r in rows if r.get("elapsed_ms", "").lstrip("-").isdigit()]
    elapsed_degraded = [int(r["elapsed_ms"]) for r in degraded_rows if r.get("elapsed_ms", "").lstrip("-").isdigit()]
    elapsed_clean = [int(r["elapsed_ms"]) for r in clean_rows if r.get("elapsed_ms", "").lstrip("-").isdigit()]

    signal_counter: Counter[str] = Counter()
    for r in degraded_rows:
        for sig in (r.get("degradation_signals") or "").split(","):
            if sig.strip():
                signal_counter[sig.strip()] += 1

    metrics: dict[str, Any] = {
        "total_requests": total,
        "error_count": len(error_rows),
        "error_rate_pct": len(error_rows) / total * 100 if total else 0.0,
        "success_count": len(success_rows),
        "success_rate_pct": len(success_rows) / total * 100 if total else 0.0,
        "degraded_count": len(degraded_rows),
        "degraded_rate_pct": len(degraded_rows) / len(success_rows) * 100 if success_rows else 0.0,
        "clean_count": len(clean_rows),
        "clean_rate_pct": len(clean_rows) / total * 100 if total else 0.0,
        "p50_ms": _percentile(elapsed_all, 50),
        "p95_ms": _percentile(elapsed_all, 95),
        "p99_ms": _percentile(elapsed_all, 99),
        "avg_ms": _avg(elapsed_all),
        "p95_degraded_ms": _percentile(elapsed_degraded, 95),
        "p95_clean_ms": _percentile(elapsed_clean, 95),
        "signal_counter": signal_counter,
    }

    if chain == "a":
        day_plans = [int(r["day_plan_count"]) for r in success_rows if r.get("day_plan_count", "").isdigit()]
        activities = [int(r["activity_count"]) for r in success_rows if r.get("activity_count", "").isdigit()]
        md_lengths = [int(r["markdown_length"]) for r in success_rows if r.get("markdown_length", "").isdigit()]
        has_id_count = sum(1 for r in success_rows if r.get("has_id") == "True")
        metrics.update({
            "avg_day_plans": _avg(day_plans),
            "avg_activities": _avg(activities),
            "avg_markdown_length": _avg(md_lengths),
            "itinerary_saved_rate_pct": has_id_count / len(success_rows) * 100 if success_rows else 0.0,
            "p50_day_plans": _percentile(day_plans, 50),
            "p50_activities": _percentile(activities, 50),
        })

    if chain == "b":
        text_chars = [int(r.get("text_chars", 0) or 0) for r in success_rows]
        tool_calls = [int(r.get("tool_calls_count", 0) or 0) for r in success_rows]
        snap_count = sum(1 for r in success_rows if r.get("state_snapshot_seen") == "True")
        run_err_count = sum(1 for r in rows if r.get("run_error_seen") == "True")
        metrics.update({
            "avg_text_chars": _avg(text_chars),
            "avg_tool_calls": _avg(tool_calls),
            "state_snapshot_rate_pct": snap_count / len(success_rows) * 100 if success_rows else 0.0,
            "run_error_count": run_err_count,
        })

    if chain == "c":
        deleg_count = sum(1 for r in success_rows if r.get("remote_agent_delegated") == "True")
        draft_count = sum(1 for r in success_rows if r.get("order_draft_seen") == "True")
        submit_count = sum(1 for r in success_rows if r.get("order_submit_seen") == "True")
        run_err_count = sum(1 for r in rows if r.get("run_error_seen") == "True")
        metrics.update({
            "delegation_rate_pct": deleg_count / len(success_rows) * 100 if success_rows else 0.0,
            "order_draft_rate_pct": draft_count / len(success_rows) * 100 if success_rows else 0.0,
            "order_submit_rate_pct": submit_count / len(success_rows) * 100 if success_rows else 0.0,
            "run_error_count": run_err_count,
        })

    return metrics


def _safe_float(val: str | None, default: float = 0.0) -> float:
    """将可能为 'N/A'、None 或空字符串的值安全转换为 float。"""
    if val is None or str(val).strip() in ("", "N/A", "n/a", "NA"):
        return default
    try:
        return float(val)
    except (ValueError, TypeError):
        return default


def _extract_locust_stats(stats_rows: list[dict[str, str]]) -> dict[str, Any]:
    """从 stats_stats.csv 取 Aggregated 行的 Locust 指标。

    Locust stats_stats.csv 列名（实测）：
      Request Count, Failure Count, Median Response Time, Average Response Time,
      Requests/s, Failures/s, 50%, 66%, 75%, 80%, 90%, 95%, 98%, 99%, 99.9%, ...
    当无请求时百分位列值为 'N/A'。
    """
    agg = next((r for r in stats_rows if r.get("Name") == "Aggregated"), None)
    if not agg:
        agg = stats_rows[-1] if stats_rows else {}
    total = int(_safe_float(agg.get("Request Count")))
    failures = int(_safe_float(agg.get("Failure Count")))
    fail_pct = failures / total * 100 if total > 0 else 0.0
    return {
        "locust_rps": _safe_float(agg.get("Requests/s")),
        "locust_fail_pct": fail_pct,
        "locust_p50_ms": _safe_float(agg.get("50%") or agg.get("Median Response Time")),
        "locust_p95_ms": _safe_float(agg.get("95%")),
        "locust_p99_ms": _safe_float(agg.get("99%")),
        "locust_avg_ms": _safe_float(agg.get("Average Response Time") or agg.get("Average (ms)")),
        "locust_total_requests": total,
        "locust_failure_count": failures,
    }


# ── 实验元数据 ────────────────────────────────────────────────────────────────

def _extract_meta(rows: list[dict[str, str]], exp_dir: Path) -> dict[str, str]:
    if not rows:
        return {"experiment_id": exp_dir.name, "chain": "a", "matrix_cell": "", "fault_scenario_name": "", "fault_dsl": ""}
    r = rows[0]
    return {
        "experiment_id": r.get("experiment_id") or exp_dir.name,
        "chain": (r.get("chain") or "a").lower().strip(),
        "matrix_cell": r.get("matrix_cell") or "",
        "fault_scenario_name": r.get("fault_scenario_name") or "",
        "scenario_mode": r.get("scenario_mode") or "single",
        "fault_dsl": r.get("fault_dsl") or "",
        "ts_start": rows[0].get("ts", ""),
        "ts_end": rows[-1].get("ts", ""),
    }


# ── 加载单个实验 ──────────────────────────────────────────────────────────────

def load_experiment(exp_dir: Path) -> dict[str, Any] | None:
    quality_path = exp_dir / "quality.csv"
    stats_path = exp_dir / "stats_stats.csv"

    quality_rows = _load_quality_csv(quality_path)
    stats_rows = _load_stats_csv(stats_path)

    if not quality_rows and not stats_rows:
        print(f"警告：{exp_dir} 无有效数据文件，跳过", file=sys.stderr)
        return None

    meta = _extract_meta(quality_rows, exp_dir)
    chain = meta["chain"]

    quality_metrics = _compute_quality_metrics(quality_rows, chain)
    locust_stats = _extract_locust_stats(stats_rows)

    return {
        "dir": exp_dir,
        "meta": meta,
        "quality": quality_metrics,
        "locust": locust_stats,
    }


# ── 论文填写数字 ──────────────────────────────────────────────────────────────

def _fmt_pct(v: float) -> str:
    return f"{v:.1f}%"


def _fmt_ms(v: float) -> str:
    return f"{v:.0f}"


def _traceql_hint(meta: dict[str, str]) -> str:
    svc_map = {"a": "trip-itinerary-planner", "b": "trip-itinerary-planner", "c": "trip-chat-service"}
    svc = svc_map.get(meta["chain"], "trip-itinerary-planner")
    exp_id = meta["experiment_id"]
    lines = [
        f'{{ resource.service.name = "{svc}" && .experiment.id = "{exp_id}" }}',
        f'{{ resource.service.name = "{svc}" && .experiment.id = "{exp_id}" && .fault.injected = true }}',
    ]
    return "\n".join(f"    {l}" for l in lines)


def _auto_compare_sentences(experiments: list[dict[str, Any]]) -> list[str]:
    """按链路分组，找 baseline-low 和 fault-low 做自动对比描述。"""
    sentences: list[str] = []
    by_chain: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for e in experiments:
        by_chain[e["meta"]["chain"]].append(e)

    for chain, exps in sorted(by_chain.items()):
        baselines = [e for e in exps if "baseline" in e["meta"]["matrix_cell"]]
        faults = [e for e in exps if "fault" in e["meta"]["matrix_cell"]]
        bl_low = next((e for e in baselines if "low" in e["meta"]["matrix_cell"]), None)
        bl_high = next((e for e in baselines if "high" in e["meta"]["matrix_cell"]), None)

        chain_label = {"a": "链路 A（REST 规划链）", "b": "链路 B（ReAct 对话）", "c": "链路 C（A2A 跨服务）"}.get(chain, f"链路 {chain.upper()}")
        sentences.append(f"\n### {chain_label}\n")

        if bl_low:
            bl_p95 = bl_low["quality"].get("p95_ms", bl_low["locust"].get("locust_p95_ms", 0))
            bl_err = bl_low["quality"].get("error_rate_pct", bl_low["locust"].get("locust_fail_pct", 0))
            sentences.append(
                f"- baseline-low 基线：p95 延迟 **{_fmt_ms(bl_p95)} ms**，错误率 **{_fmt_pct(bl_err)}**。"
            )
        if bl_high:
            bh_p95 = bl_high["quality"].get("p95_ms", bl_high["locust"].get("locust_p95_ms", 0))
            bh_err = bl_high["quality"].get("error_rate_pct", bl_high["locust"].get("locust_fail_pct", 0))
            sentences.append(
                f"- baseline-high 容量上限：p95 延迟 **{_fmt_ms(bh_p95)} ms**，错误率 **{_fmt_pct(bh_err)}**。"
            )

        for e in faults:
            scenario = e["meta"].get("fault_scenario_name") or "unknown"
            cell = e["meta"]["matrix_cell"]
            q = e["quality"]
            l = e["locust"]
            p95 = q.get("p95_ms", l.get("locust_p95_ms", 0))
            err = q.get("error_rate_pct", l.get("locust_fail_pct", 0))
            deg = q.get("degraded_rate_pct", 0)

            ref = bl_low if "low" in cell else bl_high
            if ref:
                ref_p95 = ref["quality"].get("p95_ms", ref["locust"].get("locust_p95_ms", 0))
                ref_err = ref["quality"].get("error_rate_pct", ref["locust"].get("locust_fail_pct", 0))
                p95_delta = p95 - ref_p95
                err_delta = err - ref_err
                delta_str = f"p95 较基线 {'↑' if p95_delta >= 0 else '↓'} **{abs(p95_delta):.0f} ms**"
                if abs(err_delta) >= 0.1:
                    delta_str += f"，错误率 {'↑' if err_delta >= 0 else '↓'} **{abs(err_delta):.1f}pp**"
            else:
                delta_str = f"p95 = **{_fmt_ms(p95)} ms**，错误率 = **{_fmt_pct(err)}**"

            deg_str = f"，降级率 **{_fmt_pct(deg)}**" if deg > 0 else ""
            sentences.append(
                f"- **{cell} / {scenario}**：{delta_str}{deg_str}。"
            )

        # 链路 A 额外业务指标对比
        if chain == "a":
            bl = bl_low
            if bl and "avg_day_plans" in bl["quality"]:
                sentences.append(
                    f"\n  链路 A 业务质量（baseline-low）："
                    f"平均 day_plans={bl['quality']['avg_day_plans']:.1f}，"
                    f"平均 activities={bl['quality']['avg_activities']:.1f}，"
                    f"平均 markdown 长度={bl['quality']['avg_markdown_length']:.0f} 字符。"
                )
            for e in faults:
                q = e["quality"]
                if "avg_day_plans" in q and bl and "avg_day_plans" in bl["quality"]:
                    dp_delta = q["avg_day_plans"] - bl["quality"]["avg_day_plans"]
                    act_delta = q["avg_activities"] - bl["quality"]["avg_activities"]
                    scenario = e["meta"].get("fault_scenario_name") or "unknown"
                    sentences.append(
                        f"  **{scenario}** 组："
                        f"avg_day_plans={q['avg_day_plans']:.1f}（基线 {dp_delta:+.1f}），"
                        f"avg_activities={q['avg_activities']:.1f}（基线 {act_delta:+.1f}），"
                        f"itinerary_saved_rate={q.get('itinerary_saved_rate_pct', 0):.1f}%。"
                    )

    return sentences


def build_thesis_numbers(experiments: list[dict[str, Any]]) -> str:
    lines: list[str] = []

    lines.append("# TripSphere 论文实验数字汇总")
    lines.append("")
    lines.append(f"> 生成时间：{time.strftime('%Y-%m-%d %H:%M:%S')}  |  实验数量：{len(experiments)}")
    lines.append("")

    # ── 汇总表 ────────────────────────────────────────────────────────────────
    lines.append("## 1. 四象限汇总表\n")
    header = (
        "| 实验 ID | 链路 | 象限 | 故障场景 | 总请求 | 错误率 | 成功率 | "
        "降级率(2xx中) | p50(ms) | p95(ms) | p99(ms) | RPS |"
    )
    sep = "|" + "|".join(["---"] * 12) + "|"
    lines.append(header)
    lines.append(sep)
    for e in experiments:
        meta = e["meta"]
        q = e["quality"]
        l = e["locust"]
        exp_id = meta["experiment_id"]
        short_id = exp_id[:50] + ("…" if len(exp_id) > 50 else "")
        p95 = q.get("p95_ms", l.get("locust_p95_ms", 0))
        p50 = q.get("p50_ms", l.get("locust_p50_ms", 0))
        p99 = q.get("p99_ms", l.get("locust_p99_ms", 0))
        err = q.get("error_rate_pct", l.get("locust_fail_pct", 0))
        suc = q.get("success_rate_pct", 100 - err)
        deg = q.get("degraded_rate_pct", 0)
        total = q.get("total_requests", l.get("locust_total_requests", 0))
        rps = l.get("locust_rps", 0)
        lines.append(
            f"| `{short_id}` | {meta['chain'].upper()} | {meta['matrix_cell']} "
            f"| {meta['fault_scenario_name'] or '—'} "
            f"| {int(total)} | {_fmt_pct(err)} | {_fmt_pct(suc)} "
            f"| {_fmt_pct(deg)} | {_fmt_ms(p50)} | {_fmt_ms(p95)} | {_fmt_ms(p99)} "
            f"| {rps:.1f} |"
        )
    lines.append("")

    # ── 链路 A 业务质量表 ─────────────────────────────────────────────────────
    chain_a_exps = [e for e in experiments if e["meta"]["chain"] == "a" and "avg_day_plans" in e["quality"]]
    if chain_a_exps:
        lines.append("## 2. 链路 A — 业务质量指标\n")
        lines.append(
            "| 实验 ID | 象限 | 故障场景 | avg_day_plans | avg_activities | "
            "avg_markdown_len | itinerary_saved_rate |"
        )
        lines.append("|---|---|---|---:|---:|---:|---:|")
        for e in chain_a_exps:
            q = e["quality"]
            meta = e["meta"]
            exp_id = meta["experiment_id"]
            short_id = exp_id[:40] + ("…" if len(exp_id) > 40 else "")
            lines.append(
                f"| `{short_id}` | {meta['matrix_cell']} | {meta['fault_scenario_name'] or '—'} "
                f"| {q.get('avg_day_plans', 0):.2f} | {q.get('avg_activities', 0):.2f} "
                f"| {q.get('avg_markdown_length', 0):.0f} "
                f"| {_fmt_pct(q.get('itinerary_saved_rate_pct', 0))} |"
            )
        lines.append("")

    # ── 降级信号频次表 ────────────────────────────────────────────────────────
    lines.append("## 3. 降级信号分布（per 实验）\n")
    lines.append("| 实验 ID | 信号名 | 命中次数 | 占降级请求比例 |")
    lines.append("|---|---|---:|---:|")
    for e in experiments:
        meta = e["meta"]
        q = e["quality"]
        signal_counter: Counter[str] = q.get("signal_counter", Counter())
        degraded_total: int = q.get("degraded_count", 0)
        short_id = meta["experiment_id"][:40]
        if not signal_counter:
            lines.append(f"| `{short_id}` | — | 0 | — |")
        else:
            for sig, cnt in signal_counter.most_common():
                ratio = cnt / degraded_total * 100 if degraded_total > 0 else 0.0
                lines.append(f"| `{short_id}` | `{sig}` | {cnt} | {_fmt_pct(ratio)} |")
    lines.append("")

    # ── 自动对比段落 ──────────────────────────────────────────────────────────
    lines.append("## 4. 关键对比（可直接引用至论文）\n")
    sentences = _auto_compare_sentences(experiments)
    lines.extend(sentences)
    lines.append("")

    # ── TraceQL 查询提示 ──────────────────────────────────────────────────────
    lines.append("## 5. Tempo TraceQL 查询（验证故障命中数）\n")
    for e in experiments:
        meta = e["meta"]
        if not meta.get("fault_scenario_name"):
            continue
        lines.append(f"**{meta['experiment_id']}**")
        lines.append("```traceql")
        lines.append(_traceql_hint(meta))
        lines.append("```")
        lines.append("")

    # ── 原始数据位置 ──────────────────────────────────────────────────────────
    lines.append("## 6. 原始数据位置\n")
    for e in experiments:
        d = e["dir"]
        lines.append(f"- `{d}`")
        if (d / "quality.csv").exists():
            lines.append(f"  - `{d}/quality.csv`")
        if (d / "stats_stats.csv").exists():
            lines.append(f"  - `{d}/stats_stats.csv`")
        if (d / "degradation_report.html").exists():
            lines.append(f"  - `{d}/degradation_report.html`")
    lines.append("")

    return "\n".join(lines)


# ── CSV 输出 ──────────────────────────────────────────────────────────────────

def build_comparison_table(experiments: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for e in experiments:
        meta = e["meta"]
        q = e["quality"]
        l = e["locust"]
        row: dict[str, Any] = {
            "experiment_id": meta["experiment_id"],
            "chain": meta["chain"],
            "matrix_cell": meta["matrix_cell"],
            "fault_scenario_name": meta["fault_scenario_name"],
            "scenario_mode": meta.get("scenario_mode", ""),
            "fault_dsl": meta.get("fault_dsl", ""),
            "ts_start": meta.get("ts_start", ""),
            "ts_end": meta.get("ts_end", ""),
            # quality.csv 来源
            "total_requests": q.get("total_requests", 0),
            "error_count": q.get("error_count", 0),
            "error_rate_pct": round(q.get("error_rate_pct", 0), 2),
            "success_count": q.get("success_count", 0),
            "success_rate_pct": round(q.get("success_rate_pct", 0), 2),
            "degraded_count": q.get("degraded_count", 0),
            "degraded_rate_pct": round(q.get("degraded_rate_pct", 0), 2),
            "p50_ms": round(q.get("p50_ms", 0), 1),
            "p95_ms": round(q.get("p95_ms", 0), 1),
            "p99_ms": round(q.get("p99_ms", 0), 1),
            "avg_ms": round(q.get("avg_ms", 0), 1),
            "p95_degraded_ms": round(q.get("p95_degraded_ms", 0), 1),
            "p95_clean_ms": round(q.get("p95_clean_ms", 0), 1),
            # locust stats 来源
            "locust_rps": round(l.get("locust_rps", 0), 2),
            "locust_fail_pct": round(l.get("locust_fail_pct", 0), 2),
            "locust_p50_ms": l.get("locust_p50_ms", 0),
            "locust_p95_ms": l.get("locust_p95_ms", 0),
            "locust_p99_ms": l.get("locust_p99_ms", 0),
            "locust_avg_ms": round(l.get("locust_avg_ms", 0), 1),
            "locust_total_requests": l.get("locust_total_requests", 0),
            "locust_failure_count": l.get("locust_failure_count", 0),
            # 链路 A
            "avg_day_plans": round(q.get("avg_day_plans", 0), 2),
            "avg_activities": round(q.get("avg_activities", 0), 2),
            "avg_markdown_length": round(q.get("avg_markdown_length", 0), 1),
            "itinerary_saved_rate_pct": round(q.get("itinerary_saved_rate_pct", 0), 2),
            # 链路 B
            "avg_text_chars": round(q.get("avg_text_chars", 0), 1),
            "avg_tool_calls": round(q.get("avg_tool_calls", 0), 2),
            "state_snapshot_rate_pct": round(q.get("state_snapshot_rate_pct", 0), 2),
            "run_error_count": q.get("run_error_count", 0),
            # 链路 C
            "delegation_rate_pct": round(q.get("delegation_rate_pct", 0), 2),
            "order_draft_rate_pct": round(q.get("order_draft_rate_pct", 0), 2),
            "order_submit_rate_pct": round(q.get("order_submit_rate_pct", 0), 2),
        }
        rows.append(row)
    return rows


def build_degradation_detail(experiments: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for e in experiments:
        meta = e["meta"]
        q = e["quality"]
        counter: Counter[str] = q.get("signal_counter", Counter())
        degraded_total: int = q.get("degraded_count", 0)
        total: int = q.get("total_requests", 0)
        for sig, cnt in counter.most_common():
            rows.append({
                "experiment_id": meta["experiment_id"],
                "chain": meta["chain"],
                "matrix_cell": meta["matrix_cell"],
                "fault_scenario_name": meta["fault_scenario_name"],
                "signal": sig,
                "count": cnt,
                "pct_of_degraded": round(cnt / degraded_total * 100 if degraded_total else 0, 2),
                "pct_of_total": round(cnt / total * 100 if total else 0, 2),
                "degraded_total": degraded_total,
                "total_requests": total,
            })
        if not counter:
            rows.append({
                "experiment_id": meta["experiment_id"],
                "chain": meta["chain"],
                "matrix_cell": meta["matrix_cell"],
                "fault_scenario_name": meta["fault_scenario_name"],
                "signal": "",
                "count": 0,
                "pct_of_degraded": 0.0,
                "pct_of_total": 0.0,
                "degraded_total": 0,
                "total_requests": total,
            })
    return rows


def _write_csv(rows: list[dict[str, Any]], path: Path) -> None:
    if not rows:
        return
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


# ── 图表 ──────────────────────────────────────────────────────────────────────

def _short_label(exp_id: str) -> str:
    """从 experiment_id 提取短标签用于图表 x 轴。"""
    # locust-chaina-fault-low-geocode_latency-202604271400 → fault-low\ngeocoding
    parts = exp_id.split("-")
    # 找 cell 部分
    for i, p in enumerate(parts):
        if p in ("baseline", "fault"):
            cell_parts = []
            j = i
            while j < len(parts) and parts[j] not in ("202", "none"):
                if parts[j].isdigit() and len(parts[j]) >= 8:
                    break
                cell_parts.append(parts[j])
                j += 1
            label = "-".join(cell_parts[:4])
            return label[:30]
    return exp_id[:25]


def _cell_order(cell: str) -> int:
    order = {"baseline-low": 0, "fault-low": 1, "baseline-high": 2, "fault-high": 3}
    for k, v in order.items():
        if k in cell:
            return v
    return 99


def _plot_bar_comparison(
    labels: list[str],
    values: list[float],
    title: str,
    ylabel: str,
    out_path: Path,
    color_map: list[str] | None = None,
) -> None:
    fig, ax = plt.subplots(figsize=(max(8, len(labels) * 1.2), 5))
    colors = color_map or ["#4c72b0"] * len(labels)
    bars = ax.bar(range(len(labels)), values, color=colors, width=0.6, edgecolor="white")
    ax.set_xticks(range(len(labels)))
    ax.set_xticklabels(labels, rotation=30, ha="right", fontsize=8)
    ax.set_ylabel(ylabel, fontsize=9)
    ax.set_title(title, fontsize=11, pad=10)
    ax.yaxis.set_major_formatter(mticker.FormatStrFormatter("%.1f"))
    ax.grid(axis="y", alpha=0.3)
    for bar, val in zip(bars, values):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + max(values) * 0.01,
                f"{val:.1f}", ha="center", va="bottom", fontsize=7)
    fig.tight_layout()
    fig.savefig(out_path)
    plt.close(fig)


def _cell_color(cell: str) -> str:
    if "baseline" in cell and "low" in cell:
        return "#2ca02c"
    if "baseline" in cell and "high" in cell:
        return "#1f77b4"
    if "fault" in cell and "low" in cell:
        return "#ff7f0e"
    if "fault" in cell and "high" in cell:
        return "#d62728"
    return "#7f7f7f"


def generate_charts(experiments: list[dict[str, Any]], charts_dir: Path) -> list[Path]:
    charts_dir.mkdir(parents=True, exist_ok=True)
    generated: list[Path] = []

    if not experiments:
        return generated

    # 按链路分组
    by_chain: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for e in experiments:
        by_chain[e["meta"]["chain"]].append(e)

    for chain, exps in sorted(by_chain.items()):
        exps_sorted = sorted(exps, key=lambda e: (
            e["meta"].get("fault_scenario_name", ""),
            _cell_order(e["meta"].get("matrix_cell", "")),
        ))

        labels = [_short_label(e["meta"]["experiment_id"]) for e in exps_sorted]
        colors = [_cell_color(e["meta"].get("matrix_cell", "")) for e in exps_sorted]

        def _q(e: dict, k: str, fb_l: str = "", fb: float = 0.0) -> float:
            v = e["quality"].get(k)
            if v is None and fb_l:
                v = e["locust"].get(fb_l)
            return float(v) if v is not None else fb

        # 1. success_rate
        vals = [_q(e, "success_rate_pct", fb=100) for e in exps_sorted]
        p = charts_dir / f"success_rate_chain{chain}.png"
        _plot_bar_comparison(labels, vals, f"Chain {chain.upper()} - Success Rate (%)", "Success Rate (%)", p, colors)
        generated.append(p)

        # 2. error_rate
        vals = [_q(e, "error_rate_pct", "locust_fail_pct") for e in exps_sorted]
        p = charts_dir / f"error_rate_chain{chain}.png"
        _plot_bar_comparison(labels, vals, f"Chain {chain.upper()} - Error Rate (%)", "Error Rate (%)", p, colors)
        generated.append(p)

        # 3. degradation_rate
        vals = [_q(e, "degraded_rate_pct") for e in exps_sorted]
        if any(v > 0 for v in vals):
            p = charts_dir / f"degradation_rate_chain{chain}.png"
            _plot_bar_comparison(labels, vals, f"Chain {chain.upper()} - Degradation Rate (% of 2xx)", "Degradation Rate (%)", p, colors)
            generated.append(p)

        # 4. p95 latency
        vals = [_q(e, "p95_ms", "locust_p95_ms") for e in exps_sorted]
        p = charts_dir / f"latency_p95_chain{chain}.png"
        _plot_bar_comparison(labels, vals, f"Chain {chain.upper()} - p95 Latency (ms)", "p95 Latency (ms)", p, colors)
        generated.append(p)

        # 5. p50/p95/p99 grouped bar
        p50s = [_q(e, "p50_ms", "locust_p50_ms") for e in exps_sorted]
        p95s = [_q(e, "p95_ms", "locust_p95_ms") for e in exps_sorted]
        p99s = [_q(e, "p99_ms", "locust_p99_ms") for e in exps_sorted]
        if any(v > 0 for v in p99s):
            fig, ax = plt.subplots(figsize=(max(10, len(labels) * 1.5), 5))
            x = range(len(labels))
            w = 0.25
            ax.bar([i - w for i in x], p50s, width=w, label="p50", color="#4c72b0", edgecolor="white")
            ax.bar(list(x), p95s, width=w, label="p95", color="#dd8452", edgecolor="white")
            ax.bar([i + w for i in x], p99s, width=w, label="p99", color="#c44e52", edgecolor="white")
            ax.set_xticks(list(x))
            ax.set_xticklabels(labels, rotation=30, ha="right", fontsize=8)
            ax.set_ylabel("Latency (ms)", fontsize=9)
            ax.set_title(f"Chain {chain.upper()} - Latency Distribution (p50/p95/p99)", fontsize=11, pad=10)
            ax.legend(fontsize=8)
            ax.grid(axis="y", alpha=0.3)
            fig.tight_layout()
            fp = charts_dir / f"latency_distribution_chain{chain}.png"
            fig.savefig(fp)
            plt.close(fig)
            generated.append(fp)

        # 6. 降级信号堆叠柱状图（链路 A 专用）
        if chain == "a":
            all_signals: set[str] = set()
            for e in exps_sorted:
                all_signals.update(e["quality"].get("signal_counter", {}).keys())
            if all_signals:
                sig_list = sorted(all_signals)
                sig_data = {sig: [e["quality"].get("signal_counter", Counter()).get(sig, 0) for e in exps_sorted] for sig in sig_list}
                fig, ax = plt.subplots(figsize=(max(10, len(labels) * 1.5), 5))
                bottom = [0] * len(labels)
                palette = plt.cm.tab10.colors  # type: ignore[attr-defined]
                for idx, sig in enumerate(sig_list):
                    ax.bar(range(len(labels)), sig_data[sig], bottom=bottom,
                           label=sig, color=palette[idx % len(palette)], edgecolor="white", width=0.6)
                    bottom = [b + v for b, v in zip(bottom, sig_data[sig])]
                ax.set_xticks(range(len(labels)))
                ax.set_xticklabels(labels, rotation=30, ha="right", fontsize=8)
                ax.set_ylabel("Count", fontsize=9)
                ax.set_title(f"Chain {chain.upper()} - Degradation Signals Distribution", fontsize=11, pad=10)
                ax.legend(fontsize=7, bbox_to_anchor=(1.01, 1), loc="upper left")
                ax.grid(axis="y", alpha=0.3)
                fig.tight_layout()
                fp = charts_dir / f"degradation_signals_chain{chain}.png"
                fig.savefig(fp, bbox_inches="tight")
                plt.close(fig)
                generated.append(fp)

            # 7. 链路 A 业务质量指标对比
            a_exps_with_q = [e for e in exps_sorted if "avg_day_plans" in e["quality"]]
            if a_exps_with_q:
                a_labels = [_short_label(e["meta"]["experiment_id"]) for e in a_exps_with_q]
                a_colors = [_cell_color(e["meta"].get("matrix_cell", "")) for e in a_exps_with_q]
                dp_vals = [e["quality"]["avg_day_plans"] for e in a_exps_with_q]
                act_vals = [e["quality"]["avg_activities"] for e in a_exps_with_q]
                md_vals = [e["quality"]["avg_markdown_length"] / 100 for e in a_exps_with_q]

                fig, axes = plt.subplots(1, 3, figsize=(max(14, len(a_labels) * 1.5), 5))
                for ax_, vals, title_, ylabel_ in zip(
                    axes,
                    [dp_vals, act_vals, md_vals],
                    ["Avg Day Plans", "Avg Activities", "Avg Markdown Length (/100)"],
                    ["Count", "Count", "Length/100"],
                ):
                    ax_.bar(range(len(a_labels)), vals, color=a_colors, width=0.6, edgecolor="white")
                    ax_.set_xticks(range(len(a_labels)))
                    ax_.set_xticklabels(a_labels, rotation=30, ha="right", fontsize=7)
                    ax_.set_title(title_, fontsize=9)
                    ax_.set_ylabel(ylabel_, fontsize=8)
                    ax_.grid(axis="y", alpha=0.3)
                fig.suptitle("Chain A - Business Quality Metrics", fontsize=11, y=1.02)
                fig.tight_layout()
                fp = charts_dir / "quality_metrics_chaina.png"
                fig.savefig(fp, bbox_inches="tight")
                plt.close(fig)
                generated.append(fp)

    return generated


# ── 主函数 ────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="TripSphere 跨实验聚合分析工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "dirs",
        nargs="*",
        metavar="EXPERIMENT_DIR",
        help="实验目录（含 quality.csv / stats_stats.csv）",
    )
    parser.add_argument(
        "--glob", "-g",
        metavar="PATTERN",
        help='按 glob 模式自动发现实验目录，例如 "artifacts/locust/locust-chaina-*"',
    )
    parser.add_argument(
        "--output", "-o",
        metavar="OUTPUT_DIR",
        help="输出目录（默认 artifacts/analysis/{timestamp}）",
    )
    args = parser.parse_args()

    # 收集实验目录
    exp_dirs = _find_experiment_dirs(args.dirs, args.glob)
    if not exp_dirs:
        print("错误：未找到任何实验目录。请通过位置参数或 --glob 指定。", file=sys.stderr)
        parser.print_help()
        sys.exit(1)

    print(f"找到 {len(exp_dirs)} 个实验目录：")
    for d in exp_dirs:
        print(f"  {d}")
    print()

    # 加载
    experiments: list[dict[str, Any]] = []
    for d in exp_dirs:
        exp = load_experiment(d)
        if exp:
            experiments.append(exp)

    if not experiments:
        print("错误：所有目录均无有效数据，退出。", file=sys.stderr)
        sys.exit(1)

    print(f"成功加载 {len(experiments)} 个实验。")

    # 输出目录
    ts = time.strftime("%Y%m%d_%H%M%S")
    if args.output:
        out_dir = Path(args.output)
    else:
        out_dir = Path("artifacts/analysis") / ts
    out_dir.mkdir(parents=True, exist_ok=True)

    charts_dir = out_dir / "charts"

    # thesis_numbers.md
    thesis_md = build_thesis_numbers(experiments)
    (out_dir / "thesis_numbers.md").write_text(thesis_md, encoding="utf-8")
    print(f"  ✓ {out_dir / 'thesis_numbers.md'}")

    # comparison_table.csv
    comp_rows = build_comparison_table(experiments)
    _write_csv(comp_rows, out_dir / "comparison_table.csv")
    print(f"  ✓ {out_dir / 'comparison_table.csv'}")

    # degradation_detail.csv
    deg_rows = build_degradation_detail(experiments)
    _write_csv(deg_rows, out_dir / "degradation_detail.csv")
    print(f"  ✓ {out_dir / 'degradation_detail.csv'}")

    # charts
    print("  生成图表...")
    generated = generate_charts(experiments, charts_dir)
    for fp in generated:
        print(f"    ✓ {fp}")

    # latest 软链接
    latest_link = Path("artifacts/analysis/latest")
    try:
        if latest_link.is_symlink() or latest_link.exists():
            latest_link.unlink()
        latest_link.symlink_to(out_dir.resolve())
    except Exception as e:
        print(f"  警告：无法创建 latest 软链接：{e}", file=sys.stderr)

    print()
    print("═" * 64)
    print(f"  分析完成！输出目录：{out_dir}")
    print()
    print("  论文填数字：")
    print(f"    cat {out_dir / 'thesis_numbers.md'}")
    print()
    print("  图表：")
    print(f"    ls {charts_dir}/")
    print("═" * 64)


if __name__ == "__main__":
    main()
