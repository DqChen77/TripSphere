"""TripSphere Locust 降级报告生成器

读取 quality.csv，生成包含降级统计、信号分布和请求时间线的 HTML 报告。

用法：
  python3 scripts/locust/generate_report.py <quality.csv路径> [输出HTML路径]

示例：
  python3 scripts/locust/generate_report.py \
      artifacts/locust/locust-fault-low-geocode_latency-xxx/quality.csv

输出文件默认放在 quality.csv 同目录下的 degradation_report.html。
"""

import csv
import html
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path


def _load_rows(csv_path: Path) -> list[dict[str, str]]:
    with csv_path.open(encoding="utf-8") as f:
        return list(csv.DictReader(f))


def _compute_stats(rows: list[dict[str, str]]) -> dict:
    total = len(rows)
    errors = [r for r in rows if r.get("error")]
    successes = [r for r in rows if not r.get("error") and r.get("status_code") in ("200", "201")]
    degraded = [r for r in successes if r.get("degraded") == "True"]
    clean = [r for r in successes if r.get("degraded") != "True"]

    signal_counter: Counter = Counter()
    for r in degraded:
        for sig in (r.get("degradation_signals") or "").split(","):
            if sig.strip():
                signal_counter[sig.strip()] += 1

    elapsed = []
    for r in rows:
        try:
            elapsed.append(int(r["elapsed_ms"]))
        except (ValueError, KeyError):
            pass

    degraded_elapsed = []
    for r in degraded:
        try:
            degraded_elapsed.append(int(r["elapsed_ms"]))
        except (ValueError, KeyError):
            pass

    clean_elapsed = []
    for r in clean:
        try:
            clean_elapsed.append(int(r["elapsed_ms"]))
        except (ValueError, KeyError):
            pass

    def _pct(n: int) -> str:
        return f"{n / total * 100:.1f}%" if total > 0 else "—"

    def _med(vals: list[int]) -> str:
        if not vals:
            return "—"
        s = sorted(vals)
        mid = len(s) // 2
        return str(s[mid] if len(s) % 2 else (s[mid - 1] + s[mid]) // 2)

    def _p95(vals: list[int]) -> str:
        if not vals:
            return "—"
        s = sorted(vals)
        idx = int(len(s) * 0.95)
        return str(s[min(idx, len(s) - 1)])

    day_plan_counts = []
    act_counts = []
    md_lengths = []
    for r in successes:
        try:
            day_plan_counts.append(int(r["day_plan_count"]))
        except (ValueError, KeyError):
            pass
        try:
            act_counts.append(int(r["activity_count"]))
        except (ValueError, KeyError):
            pass
        try:
            md_lengths.append(int(r["markdown_length"]))
        except (ValueError, KeyError):
            pass

    def _avg(vals: list[int]) -> str:
        return f"{sum(vals) / len(vals):.1f}" if vals else "—"

    return {
        "total": total,
        "errors": len(errors),
        "error_pct": _pct(len(errors)),
        "successes": len(successes),
        "degraded": len(degraded),
        "degraded_pct": _pct(len(degraded)),
        "clean": len(clean),
        "clean_pct": _pct(len(clean)),
        "signal_counter": signal_counter,
        "p50_all": _med(elapsed),
        "p95_all": _p95(elapsed),
        "p50_degraded": _med(degraded_elapsed),
        "p95_degraded": _p95(degraded_elapsed),
        "p50_clean": _med(clean_elapsed),
        "p95_clean": _p95(clean_elapsed),
        "avg_day_plans": _avg(day_plan_counts),
        "avg_activities": _avg(act_counts),
        "avg_markdown": _avg(md_lengths),
    }


def _meta(rows: list[dict[str, str]]) -> dict[str, str]:
    if not rows:
        return {}
    r = rows[0]
    return {
        "experiment_id": r.get("experiment_id", ""),
        "matrix_cell": r.get("matrix_cell", ""),
        "fault_scenario_name": r.get("fault_scenario_name", ""),
        "scenario_mode": r.get("scenario_mode", ""),
        "fault_dsl": r.get("fault_dsl", ""),
        "ts_start": rows[0].get("ts", ""),
        "ts_end": rows[-1].get("ts", ""),
    }


def _timeline_table(rows: list[dict[str, str]], max_rows: int = 300) -> str:
    """生成请求时间线表格（最多显示 max_rows 行，超出时取均匀采样）。"""
    if not rows:
        return "<p>无数据</p>"

    sample = rows
    if len(rows) > max_rows:
        step = len(rows) // max_rows
        sample = rows[::step][:max_rows]

    def _status_class(r: dict[str, str]) -> str:
        if r.get("error"):
            return "err"
        if r.get("degraded") == "True":
            return "deg"
        return "ok"

    def _badge(r: dict[str, str]) -> str:
        if r.get("error"):
            return f'<span class="badge err">{r.get("status_code","—")}</span>'
        if r.get("degraded") == "True":
            signals = r.get("degradation_signals", "")
            return f'<span class="badge deg" title="{html.escape(signals)}">降级</span>'
        return f'<span class="badge ok">OK</span>'

    head = """
    <table>
      <thead>
        <tr>
          <th>时间</th><th>状态</th><th>耗时(ms)</th>
          <th>day_plans</th><th>activities</th><th>markdown长度</th>
          <th>降级信号</th>
        </tr>
      </thead>
      <tbody>
    """
    rows_html = ""
    for r in sample:
        cls = _status_class(r)
        rows_html += (
            f'<tr class="{cls}">'
            f"<td>{html.escape(r.get('ts',''))}</td>"
            f"<td>{_badge(r)}</td>"
            f"<td>{html.escape(r.get('elapsed_ms',''))}</td>"
            f"<td>{html.escape(r.get('day_plan_count',''))}</td>"
            f"<td>{html.escape(r.get('activity_count',''))}</td>"
            f"<td>{html.escape(r.get('markdown_length',''))}</td>"
            f"<td><small>{html.escape(r.get('degradation_signals',''))}</small></td>"
            f"</tr>\n"
        )
    return head + rows_html + "</tbody></table>"


def _signal_bars(signal_counter: Counter, degraded_total: int) -> str:
    if not signal_counter:
        return "<p>无降级信号</p>"
    items = signal_counter.most_common()
    max_count = items[0][1] if items else 1
    bars = ""
    for sig, cnt in items:
        pct = cnt / degraded_total * 100 if degraded_total > 0 else 0
        bar_w = int(cnt / max_count * 100)
        bars += f"""
        <div class="bar-row">
          <span class="bar-label">{html.escape(sig)}</span>
          <div class="bar-track"><div class="bar-fill" style="width:{bar_w}%"></div></div>
          <span class="bar-value">{cnt} &nbsp;<small>({pct:.1f}%)</small></span>
        </div>"""
    return bars


def generate_html(csv_path: Path, out_path: Path) -> None:
    rows = _load_rows(csv_path)
    stats = _compute_stats(rows)
    meta = _meta(rows)

    title = f"TripSphere 降级报告 — {meta.get('experiment_id', '')}"

    overview_rows = [
        ("实验 ID", meta.get("experiment_id", "—")),
        ("象限", meta.get("matrix_cell", "—")),
        ("故障场景", meta.get("fault_scenario_name", "—") or "（baseline）"),
        ("模式", meta.get("scenario_mode", "—")),
        ("故障 DSL", meta.get("fault_dsl", "—") or "（无）"),
        ("时间范围", f"{meta.get('ts_start','?')} ~ {meta.get('ts_end','?')}"),
    ]

    perf_rows = [
        ("", "全部请求", "成功+未降级", "成功+降级"),
        ("p50 (ms)", stats["p50_all"], stats["p50_clean"], stats["p50_degraded"]),
        ("p95 (ms)", stats["p95_all"], stats["p95_clean"], stats["p95_degraded"]),
        ("平均 day_plan_count", "—", "—", stats["avg_day_plans"]),
        ("平均 activity_count", "—", "—", stats["avg_activities"]),
        ("平均 markdown 长度", "—", "—", stats["avg_markdown"]),
    ]

    def _ov_trs(pairs: list[tuple[str, str]]) -> str:
        return "".join(
            f"<tr><td><b>{html.escape(k)}</b></td><td>{html.escape(v)}</td></tr>"
            for k, v in pairs
        )

    def _perf_trs(rows_data: list[tuple]) -> str:
        out = ""
        for i, r in enumerate(rows_data):
            cls = "thead-row" if i == 0 else ""
            cells = "".join(f"<td>{html.escape(str(c))}</td>" for c in r)
            out += f'<tr class="{cls}">{cells}</tr>'
        return out

    html_content = f"""<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<title>{html.escape(title)}</title>
<style>
  body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
         margin: 0; padding: 24px; background: #f5f5f5; color: #222; }}
  h1 {{ font-size: 1.4rem; margin-bottom: 4px; }}
  h2 {{ font-size: 1.1rem; margin: 28px 0 10px; border-bottom: 2px solid #ddd; padding-bottom: 4px; }}
  .cards {{ display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 8px; }}
  .card {{ background: #fff; border-radius: 8px; padding: 16px 24px;
           box-shadow: 0 1px 4px rgba(0,0,0,.08); min-width: 140px; text-align: center; }}
  .card .val {{ font-size: 2rem; font-weight: 700; }}
  .card .lbl {{ font-size: .8rem; color: #666; margin-top: 2px; }}
  .card.green .val {{ color: #16a34a; }}
  .card.amber .val {{ color: #d97706; }}
  .card.red .val   {{ color: #dc2626; }}
  table {{ border-collapse: collapse; width: 100%; background: #fff;
           border-radius: 8px; overflow: hidden;
           box-shadow: 0 1px 4px rgba(0,0,0,.08); font-size: .88rem; }}
  th, td {{ padding: 8px 12px; text-align: left; border-bottom: 1px solid #eee; }}
  th {{ background: #f0f0f0; font-weight: 600; }}
  tr.ok  {{ background: #f0fdf4; }}
  tr.deg {{ background: #fffbeb; }}
  tr.err {{ background: #fef2f2; }}
  tr.thead-row td {{ font-weight: 600; background: #f0f0f0; }}
  .badge {{ display: inline-block; padding: 2px 8px; border-radius: 4px;
            font-size: .78rem; font-weight: 600; }}
  .badge.ok  {{ background: #dcfce7; color: #166534; }}
  .badge.deg {{ background: #fef9c3; color: #854d0e; }}
  .badge.err {{ background: #fee2e2; color: #991b1b; }}
  .bar-row {{ display: flex; align-items: center; gap: 10px; margin: 6px 0; }}
  .bar-label {{ width: 200px; font-size: .85rem; text-align: right; flex-shrink: 0; }}
  .bar-track {{ flex: 1; background: #e5e7eb; border-radius: 4px; height: 16px; }}
  .bar-fill  {{ background: #f59e0b; border-radius: 4px; height: 16px; }}
  .bar-value {{ width: 120px; font-size: .85rem; }}
  .section {{ background: #fff; border-radius: 8px; padding: 16px 20px;
              box-shadow: 0 1px 4px rgba(0,0,0,.08); margin-bottom: 20px; }}
  small {{ color: #666; }}
  .note {{ font-size: .8rem; color: #666; margin: 6px 0 12px; }}
</style>
</head>
<body>
<h1>{html.escape(title)}</h1>

<h2>实验元数据</h2>
<div class="section">
<table>{_ov_trs(overview_rows)}</table>
</div>

<h2>总览</h2>
<div class="cards">
  <div class="card">
    <div class="val">{stats['total']}</div>
    <div class="lbl">总请求数</div>
  </div>
  <div class="card red">
    <div class="val">{stats['errors']}</div>
    <div class="lbl">HTTP 错误 ({stats['error_pct']})</div>
  </div>
  <div class="card green">
    <div class="val">{stats['clean']}</div>
    <div class="lbl">成功无降级 ({stats['clean_pct']})</div>
  </div>
  <div class="card amber">
    <div class="val">{stats['degraded']}</div>
    <div class="lbl">成功有降级 ({stats['degraded_pct']})</div>
  </div>
</div>
<p class="note">
  降级 = HTTP 2xx 但检测到业务质量退化（fallback 路径激活）。
  与 HTTP 错误互斥。具体 fallback 原因需通过 Tempo
  <code>fault.fallback_path</code> 属性确认。
</p>

<h2>性能对比</h2>
<div class="section">
<table>{_perf_trs(perf_rows)}</table>
</div>

<h2>降级信号分布</h2>
<div class="section">
<p class="note">各信号在所有降级请求中的占比（一个请求可能触发多个信号）</p>
{_signal_bars(stats['signal_counter'], stats['degraded'])}
</div>

<h2>请求时间线</h2>
<p class="note">最多显示 300 条，超出时均匀采样。绿=正常，黄=降级，红=错误</p>
{_timeline_table(rows)}

</body>
</html>"""

    out_path.write_text(html_content, encoding="utf-8")
    print(f"Report written: {out_path}")


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    csv_path = Path(sys.argv[1])
    if not csv_path.exists():
        print(f"Error: {csv_path} not found")
        sys.exit(1)
    out_path = Path(sys.argv[2]) if len(sys.argv) > 2 else csv_path.parent / "degradation_report.html"
    generate_html(csv_path, out_path)


if __name__ == "__main__":
    main()
