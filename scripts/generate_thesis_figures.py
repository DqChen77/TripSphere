from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch, Rectangle


ROOT = Path(__file__).resolve().parents[1]
FIG_DIR = ROOT / "essay" / "graduation_thesis" / "figures"

MATRIX = ["baseline-low", "baseline-high", "fault-low", "fault-high"]
CHAINS = ["A", "B", "C"]

OVERVIEW = {
    "A": {
        "requests": [76, 288, 68, 272],
        "failure_rate": [1.3, 2.8, 36.8, 39.7],
        "p50_ms": [34000, 37000, 38000, 40000],
        "p95_ms": [57000, 56000, 60000, 59000],
        "degradation_rate": [10.5, 7.6, 30.9, 30.4],
    },
    "B": {
        "requests": [139, 451, 341, 1077],
        "failure_rate": [0.0, 0.0, 0.0, 0.0],
        "p50_ms": [3300, 3700, 1500, 1600],
        "p95_ms": [30000, 30000, 5000, 6200],
        "degradation_rate": [2.9, 4.0, 60.7, 55.8],
        "snapshot_rate": [97.8, 97.3, 56.9, 62.6],
    },
    "C": {
        "requests": [84, 131, 129, 208],
        "failure_rate": [0.0, 0.0, 0.0, 0.0],
        "p50_ms": [14000, 15000, 5100, 11000],
        "p95_ms": [29000, 29000, 16000, 27000],
        "degradation_rate": [1.2, 3.6, 96.2, 94.4],
        "order_submit_rate": [98.8, 96.4, 3.8, 5.6],
    },
}

COLORS = {
    "A": "#4C78A8",
    "B": "#F58518",
    "C": "#54A24B",
    "baseline": "#72B7B2",
    "fault": "#E45756",
    "muted": "#6B7280",
}


def setup_style() -> None:
    plt.rcParams.update(
        {
            "figure.dpi": 160,
            "savefig.dpi": 220,
            "font.family": "DejaVu Sans",
            "axes.spines.top": False,
            "axes.spines.right": False,
            "axes.grid": True,
            "grid.alpha": 0.22,
            "axes.axisbelow": True,
        }
    )


def save(fig: plt.Figure, filename: str) -> None:
    FIG_DIR.mkdir(parents=True, exist_ok=True)
    fig.savefig(FIG_DIR / filename, bbox_inches="tight")
    plt.close(fig)


def add_value_labels(ax: plt.Axes, bars, fmt="{:.0f}") -> None:
    for bar in bars:
        height = bar.get_height()
        ax.annotate(
            fmt.format(height),
            xy=(bar.get_x() + bar.get_width() / 2, height),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=8,
        )


def draw_layered_architecture() -> None:
    fig, ax = plt.subplots(figsize=(12.5, 7.2))
    ax.set_xlim(0, 12)
    ax.set_ylim(0, 8)
    ax.axis("off")

    layers = [
        ("User Interaction", "Web UI / REST API / CopilotKit / Chat Service", 6.65, "#E8F1FB"),
        ("Agent Orchestration", "LangGraph workflow / ReAct loop / Remote Order Agent", 5.25, "#FEF3E2"),
        ("Tool and External Services", "Map API / Attraction RPC / Hotel RPC / LLM Gateway", 3.85, "#EAF6EA"),
        ("Business Microservices", "Itinerary Service / Product Service / Order Service / Persistence", 2.45, "#FDEBEC"),
        ("Observability and Control", "Locust / fault headers / FaultRegistry / OTel / Tempo", 1.05, "#EFEAF7"),
    ]

    for title, desc, y, color in layers:
        box = FancyBboxPatch(
            (0.55, y),
            10.9,
            0.95,
            boxstyle="round,pad=0.08,rounding_size=0.1",
            linewidth=1.4,
            edgecolor="#334155",
            facecolor=color,
        )
        ax.add_patch(box)
        ax.text(0.9, y + 0.58, title, fontsize=14, weight="bold", va="center", color="#111827")
        ax.text(4.1, y + 0.58, desc, fontsize=11, va="center", color="#374151")

    for y1, y2 in [(6.65, 6.2), (5.25, 4.8), (3.85, 3.4), (2.45, 2.0)]:
        ax.add_patch(
            FancyArrowPatch(
                (6, y1),
                (6, y2),
                arrowstyle="-|>",
                mutation_scale=18,
                linewidth=1.2,
                color="#475569",
            )
        )

    side_items = [
        ("Chain A\nREST planning", 1.0, 7.45, COLORS["A"]),
        ("Chain B\nCopilot chat", 4.65, 7.45, COLORS["B"]),
        ("Chain C\nChat -> A2A -> Order", 8.3, 7.45, COLORS["C"]),
    ]
    for text, x, y, color in side_items:
        ax.add_patch(
            FancyBboxPatch(
                (x, y),
                2.7,
                0.45,
                boxstyle="round,pad=0.04,rounding_size=0.08",
                linewidth=0,
                facecolor=color,
                alpha=0.95,
            )
        )
        ax.text(x + 1.35, y + 0.23, text, fontsize=9, color="white", ha="center", va="center")

    ax.text(
        0.55,
        0.35,
        "Request-scoped experiment_id connects workload, fault injection, spans, traces and quality CSV.",
        fontsize=10,
        color="#475569",
    )
    save(fig, "tripsphere-layered-architecture.png")


def draw_p50_compare() -> None:
    fig, ax = plt.subplots(figsize=(10.8, 5.8))
    x = np.arange(len(MATRIX))
    width = 0.24
    for idx, chain in enumerate(CHAINS):
        values = np.array(OVERVIEW[chain]["p50_ms"]) / 1000
        bars = ax.bar(x + (idx - 1) * width, values, width, label=f"Chain {chain}", color=COLORS[chain])
        add_value_labels(ax, bars, "{:.1f}")

    ax.set_title("p50 Latency Across Chains and Matrix Cells", fontsize=14, weight="bold")
    ax.set_ylabel("p50 latency (s)")
    ax.set_xticks(x)
    ax.set_xticklabels(["base-low", "base-high", "fault-low", "fault-high"])
    ax.legend(ncol=3, frameon=False, loc="upper left")
    ax.set_ylim(0, 46)
    save(fig, "exp-p50-latency-compare.png")


def draw_http_vs_degradation() -> None:
    labels = [f"{chain}\n{cell.replace('-', ' ')}" for chain in CHAINS for cell in MATRIX]
    failure = [value for chain in CHAINS for value in OVERVIEW[chain]["failure_rate"]]
    degradation = [value for chain in CHAINS for value in OVERVIEW[chain]["degradation_rate"]]

    fig, ax = plt.subplots(figsize=(12.5, 6.2))
    x = np.arange(len(labels))
    width = 0.38
    bars1 = ax.bar(x - width / 2, failure, width, label="HTTP failure rate", color=COLORS["muted"])
    bars2 = ax.bar(x + width / 2, degradation, width, label="Business degradation rate", color=COLORS["fault"])
    add_value_labels(ax, bars1, "{:.1f}")
    add_value_labels(ax, bars2, "{:.1f}")
    ax.set_title("HTTP Failure Rate vs Business Degradation Rate", fontsize=14, weight="bold")
    ax.set_ylabel("rate (%)")
    ax.set_xticks(x)
    ax.set_xticklabels(labels, fontsize=8)
    ax.legend(frameon=False, loc="upper left")
    ax.set_ylim(0, 110)
    ax.axhline(0, color="#111827", linewidth=0.8)
    save(fig, "exp-http-failure-vs-degradation.png")


def draw_degradation_compare() -> None:
    fig, ax = plt.subplots(figsize=(10.8, 5.8))
    x = np.arange(len(MATRIX))
    for chain in CHAINS:
        ax.plot(
            x,
            OVERVIEW[chain]["degradation_rate"],
            marker="o",
            linewidth=2.4,
            markersize=7,
            label=f"Chain {chain}",
            color=COLORS[chain],
        )
        for xi, yi in zip(x, OVERVIEW[chain]["degradation_rate"]):
            ax.text(xi, yi + 2.2, f"{yi:.1f}%", ha="center", fontsize=8)

    ax.set_title("Business Degradation Rate Across Matrix Cells", fontsize=14, weight="bold")
    ax.set_ylabel("degradation rate (%)")
    ax.set_xticks(x)
    ax.set_xticklabels(["base-low", "base-high", "fault-low", "fault-high"])
    ax.set_ylim(0, 105)
    ax.legend(frameon=False, loc="upper left")
    save(fig, "exp-degradation-rate-compare.pdf")


def draw_chainb_latency_snapshot() -> None:
    fig, ax1 = plt.subplots(figsize=(10.8, 5.8))
    x = np.arange(len(MATRIX))
    p50 = np.array(OVERVIEW["B"]["p50_ms"]) / 1000
    p95 = np.array(OVERVIEW["B"]["p95_ms"]) / 1000
    width = 0.34
    b1 = ax1.bar(x - width / 2, p50, width, label="p50 latency", color="#9ECAE9")
    b2 = ax1.bar(x + width / 2, p95, width, label="p95 latency", color=COLORS["B"])
    add_value_labels(ax1, b1, "{:.1f}")
    add_value_labels(ax1, b2, "{:.1f}")
    ax1.set_ylabel("latency (s)")
    ax1.set_xticks(x)
    ax1.set_xticklabels(["base-low", "base-high", "fault-low", "fault-high"])
    ax1.set_ylim(0, 35)

    ax2 = ax1.twinx()
    ax2.plot(
        x,
        OVERVIEW["B"]["snapshot_rate"],
        color="#2CA02C",
        marker="o",
        linewidth=2.4,
        label="StateSnapshot update rate",
    )
    ax2.set_ylabel("StateSnapshot update rate (%)")
    ax2.set_ylim(0, 110)
    for xi, yi in zip(x, OVERVIEW["B"]["snapshot_rate"]):
        ax2.text(xi, yi + 3, f"{yi:.1f}%", ha="center", fontsize=8, color="#166534")

    lines, labels = ax1.get_legend_handles_labels()
    lines2, labels2 = ax2.get_legend_handles_labels()
    ax1.legend(lines + lines2, labels + labels2, frameon=False, loc="upper right")
    ax1.set_title("Chain B Latency Distribution and StateSnapshot Update Rate", fontsize=14, weight="bold")
    save(fig, "case-chainb-latency-snapshot.png")


def draw_chainc_submit_rate() -> None:
    actual = OVERVIEW["C"]["order_submit_rate"]
    theory_fault = (1 - 0.4) * (1 - 0.3) * (1 - 0.2) * 100
    theory = [100.0, 100.0, theory_fault, theory_fault]

    fig, ax = plt.subplots(figsize=(10.8, 5.8))
    x = np.arange(len(MATRIX))
    width = 0.36
    bars1 = ax.bar(x - width / 2, actual, width, label="Observed submit rate", color=COLORS["C"])
    bars2 = ax.bar(x + width / 2, theory, width, label="Independent-fault expectation", color="#BDB2FF")
    add_value_labels(ax, bars1, "{:.1f}")
    add_value_labels(ax, bars2, "{:.1f}")
    ax.set_title("Chain C Order Submit Rate: Observed vs Expected", fontsize=14, weight="bold")
    ax.set_ylabel("order submit rate (%)")
    ax.set_xticks(x)
    ax.set_xticklabels(["base-low", "base-high", "fault-low", "fault-high"])
    ax.set_ylim(0, 112)
    ax.legend(frameon=False, loc="upper right")
    ax.text(
        2.15,
        44,
        "Expected fault success = (1-0.4)(1-0.3)(1-0.2) = 33.6%",
        fontsize=9,
        color="#4B5563",
    )
    save(fig, "case-chainc-submit-rate.png")


def main() -> None:
    setup_style()
    draw_layered_architecture()
    draw_p50_compare()
    draw_http_vs_degradation()
    draw_degradation_compare()
    draw_chainb_latency_snapshot()
    draw_chainc_submit_rate()
    print(f"Wrote figures to {FIG_DIR}")


if __name__ == "__main__":
    main()
