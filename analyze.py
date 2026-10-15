#!/usr/bin/env python3
"""
analyze.py - turn Experiment.java output into the figures, LaTeX tables and
validation report the paper needs.

USAGE
    python analyze.py --results results_final_light results_final_heavy \\
                      --stats data_new --out analysis

    python analyze.py --results results_dev --out analysis    # stats optional

INPUTS
    <results>/<Dataset>/summary.csv   written by Experiment.java
    <stats>/<dataset>_stats.json      written by generate_dataset.py (optional)

OUTPUTS (all under --out)
    figures/fig_scaling.pdf       speedup vs thread count, one panel per dataset
    figures/fig_workratio.pdf     work ratio vs thread count, one panel per dataset
    figures/fig_speedup_vs_k.pdf  speedup at the highest thread count vs k
    tables/table_datasets.tex     dataset characteristics (Table 3/4)
    tables/table_main.tex         per dataset, highest thread count
    tables/table_scaling.tex      speedup at every thread count
    report.txt                    validation report, also printed to stdout

WHY SPEEDUP AND WORK RATIO ARE RECOMPUTED HERE
    The console table that Experiment.java prints is for watching a run in
    progress; it is not the source of truth. Both derived quantities are
    recomputed from summary.csv so that the figures and the tables come from one
    place and cannot drift apart. Every parallel row is matched to the SEQ row
    for its own (dataset, k, minProb) cell, never to a SEQ time measured in a
    different session: single-thread and multi-thread timings move differently
    with the machine's power state, so mixing sessions silently inflates
    speedups.

WHY THE AVERAGE OVER k IS GEOMETRIC
    Speedups and work ratios are ratios. An arithmetic mean of ratios is biased
    upward and is not symmetric under inversion, so a strategy that is 4x faster
    at one k and 4x slower at another would average to 2.1x rather than 1.0x.

THE DRIFT CHECK
    The measurements contain their own control. TPB, PLM and PC running on one
    thread explore exactly the tree SEQ explores - the recorded join counts are
    identical - so their times must equal SEQ's. Where they do not, the machine
    changed speed partway through that cell, and every timing in it is suspect:
    the speedups are divided by a baseline that was measured under different
    conditions. Such cells are detected automatically, listed in the report and
    left out of the aggregates, because a ratio between two differently-clocked
    measurements is not a property of the algorithms. Pass --keep-drifted to
    include them anyway.
"""

import argparse
import csv
import glob
import json
import math
import os
import sys
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

# ---------------------------------------------------------------------------
# Presentation order and styling
# ---------------------------------------------------------------------------

# Dense first, then sparse: this is the order the paper's discussion follows,
# because the strategies separate along the density axis.
DATASET_ORDER = ["Chess", "Mushroom", "c20d10k", "Accidents", "Pumsb",
                 "Kosarak", "Liquor", "Retail", "T10I4D100K", "t25i10d10k"]

ALGO_ORDER = ["SEQ", "SEQREV", "FJ", "TPB", "PC", "PLM"]
PARALLEL = ["FJ", "TPB", "PC", "PLM"]

STYLE = {
    "FJ":  dict(color="#1b6ca8", marker="o", linestyle="-"),
    "TPB": dict(color="#e08214", marker="s", linestyle="-"),
    "PC":  dict(color="#2a8a4a", marker="^", linestyle="-"),
    "PLM": dict(color="#b2182b", marker="D", linestyle="-"),
}

LABEL = {"FJ": "Fork/Join", "TPB": "Thread pool", "PC": "Producer-consumer",
         "PLM": "Partition-local", "SEQ": "Sequential",
         "SEQREV": "Sequential (reversed)"}

plt.rcParams.update({
    "font.size": 9,
    "axes.titlesize": 9,
    "axes.labelsize": 9,
    "legend.fontsize": 8,
    "xtick.labelsize": 8,
    "ytick.labelsize": 8,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "figure.dpi": 150,
    "savefig.bbox": "tight",
})


# ---------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------

def num(x):
    if x is None or x == "":
        return None
    try:
        return float(x)
    except ValueError:
        return None


def load(results_dirs):
    """Read every <Dataset>/summary.csv under the given directories.

    Directories are read in the order given and a later one REPLACES an earlier
    one for the same (dataset, algorithm, k, minProb, threads). That is what
    makes re-running a handful of bad cells practical: point --results at the
    original sweep followed by the re-run, and the corrected rows win without
    anyone having to edit a CSV by hand. Rows that only the re-run has are
    added; rows it does not touch keep their original measurement.

    Rows written in min-util mode are ignored: they answer the fixed-threshold
    question and belong to the base-case comparison, not to this sweep.

    Returns (rows, files, replaced) where `replaced` counts the superseded rows.
    """
    seen, order, files, replaced, skipped_modes = {}, [], [], 0, 0
    for d in results_dirs:
        for path in sorted(glob.glob(os.path.join(d, "*", "summary.csv"))):
            files.append(path)
            with open(path, newline="", encoding="utf-8-sig") as f:
                for r in csv.DictReader(f):
                    r["k"] = int(r["k"])
                    r["threads"] = int(r["threads"])
                    r["minProb"] = float(r["minProb"])
                    for key in ("mean_ms", "stdev_ms", "nodesExpanded",
                                "joinsAttempted", "peakHeapMB", "patternsFound",
                                "n"):
                        r[key] = num(r.get(key))
                    r["source"] = d
                    # Results written in min-util mode answer a different
                    # question and carry k = 0, so mixing them into this
                    # analysis would collapse every threshold into one cell and
                    # produce speedups against the wrong baseline. Files written
                    # before the mode column existed are top-k by construction.
                    if r.get("mode", "topk") != "topk":
                        skipped_modes += 1
                        continue
                    key = (r["dataset"], r["algorithm"], r["k"], r["minProb"],
                           r["threads"])
                    if key in seen:
                        replaced += 1
                    else:
                        order.append(key)
                    seen[key] = r
    if not files:
        sys.exit("No summary.csv found under: %s" % ", ".join(results_dirs))
    if skipped_modes:
        print("Ignored %d row(s) from min-util runs; this analysis is for top-k "
              "results." % skipped_modes)
    return [seen[k] for k in order], files, replaced


def cell_key(r):
    return (r["dataset"], r["k"], r["minProb"])


def detect_drift(rows, tol):
    """Find cells whose timings were taken while the machine changed speed.

    TPB, PLM and PC on one thread do exactly SEQ's work. Their join counts are
    compared first, and only rows that really did the same work are used, so a
    genuine algorithmic difference is never mistaken for drift. A relative time
    gap larger than `tol` means the cell's rows were not measured under
    comparable conditions.

    Returns {cell_key: (worst_relative_gap, [(algorithm, time), ...])}.
    """
    by = {}
    for r in rows:
        if r["status"] == "OK":
            by[(r["dataset"], r["k"], r["minProb"], r["algorithm"],
                r["threads"])] = r

    drifted = {}
    for r in rows:
        if r["algorithm"] != "SEQ" or r["status"] != "OK" or not r["mean_ms"]:
            continue
        ds, k, p = r["dataset"], r["k"], r["minProb"]
        peers, worst = [], 0.0
        for algo in ("TPB", "PLM", "PC"):
            q = by.get((ds, k, p, algo, 1))
            if not q or not q["mean_ms"] or not q["joinsAttempted"]:
                continue
            if abs(q["joinsAttempted"] - r["joinsAttempted"]) > 1:
                continue          # not the same work: not a valid control
            peers.append((algo, q["mean_ms"]))
            worst = max(worst, abs(q["mean_ms"] / r["mean_ms"] - 1.0))
        if peers and worst > tol:
            drifted[(ds, k, p)] = (worst, [("SEQ", r["mean_ms"])] + peers)
    return drifted


def derive(rows, drifted):
    """Attach speedup and work ratio, using the SEQ row of the same cell."""
    seq = {}
    for r in rows:
        if r["algorithm"] == "SEQ" and r["status"] == "OK":
            seq[cell_key(r)] = r

    for r in rows:
        base = seq.get(cell_key(r))
        r["drifted"] = cell_key(r) in drifted
        r["speedup"] = None
        r["workratio"] = None
        if r["status"] != "OK" or base is None:
            continue
        if r["mean_ms"]:
            r["speedup"] = base["mean_ms"] / r["mean_ms"]
        if base["joinsAttempted"]:
            r["workratio"] = r["joinsAttempted"] / base["joinsAttempted"]
    return rows


def geomean(vals):
    vals = [v for v in vals if v and v > 0]
    if not vals:
        return None
    return math.exp(sum(math.log(v) for v in vals) / len(vals))


def order_datasets(names):
    known = [d for d in DATASET_ORDER if d in names]
    return known + sorted(n for n in names if n not in DATASET_ORDER)


def order_algos(names):
    known = [a for a in ALGO_ORDER if a in names]
    return known + sorted(a for a in names if a not in ALGO_ORDER)


# ---------------------------------------------------------------------------
# Figures
# ---------------------------------------------------------------------------

def grid(n):
    """Panel layout: at most 3 columns, as square as possible."""
    cols = min(3, n)
    return math.ceil(n / cols), cols


def panel_figure(rows, datasets, threadvals, value_key, ylabel,
                 outfile, reference=None, logy=False):
    """One panel per dataset: value_key vs thread count, one line per strategy.

    Each point is the geometric mean over the k values measured for that
    dataset, so a panel summarises the whole k range rather than one arbitrary
    setting.
    """
    nrows, ncols = grid(len(datasets))
    fig, axes = plt.subplots(nrows, ncols, figsize=(3.1 * ncols, 2.5 * nrows),
                             squeeze=False)

    handles, labels = [], []
    for idx, ds in enumerate(datasets):
        ax = axes[idx // ncols][idx % ncols]
        for algo in PARALLEL:
            xs, ys = [], []
            for t in threadvals:
                vals = [r[value_key] for r in rows
                        if r["dataset"] == ds and r["algorithm"] == algo
                        and r["threads"] == t and r[value_key]]
                g = geomean(vals)
                if g:
                    xs.append(t)
                    ys.append(g)
            if xs:
                line, = ax.plot(xs, ys, markersize=4, linewidth=1.4,
                                label=LABEL[algo], **STYLE[algo])
                if LABEL[algo] not in labels:
                    handles.append(line)
                    labels.append(LABEL[algo])

        if reference is not None:
            ax.axhline(reference, color="#888888", linewidth=0.8,
                       linestyle=":", zorder=0)

        ax.set_title(ds)
        ax.set_xscale("log", base=2)
        ax.set_xticks(threadvals)
        ax.set_xticklabels([str(t) for t in threadvals])
        if logy:
            ax.set_yscale("log", base=2)
        if idx % ncols == 0:
            ax.set_ylabel(ylabel)
        if idx // ncols == nrows - 1:
            ax.set_xlabel("Threads")

    for idx in range(len(datasets), nrows * ncols):
        axes[idx // ncols][idx % ncols].axis("off")

    fig.legend(handles, labels, loc="lower center", ncol=min(4, len(labels)),
               frameon=False, bbox_to_anchor=(0.5, -0.02))
    fig.tight_layout()
    fig.savefig(outfile)
    plt.close(fig)
    return outfile


def speedup_vs_k(rows, datasets, outfile):
    """Speedup at the highest thread count, as a function of k."""
    nrows, ncols = grid(len(datasets))
    fig, axes = plt.subplots(nrows, ncols, figsize=(3.1 * ncols, 2.5 * nrows),
                             squeeze=False)
    handles, labels = [], []

    for idx, ds in enumerate(datasets):
        ax = axes[idx // ncols][idx % ncols]
        tmax = max((r["threads"] for r in rows
                    if r["dataset"] == ds and r["algorithm"] in PARALLEL),
                   default=None)
        for algo in PARALLEL:
            pts = sorted((r["k"], r["speedup"]) for r in rows
                         if r["dataset"] == ds and r["algorithm"] == algo
                         and r["threads"] == tmax and r["speedup"])
            if pts:
                line, = ax.plot([p[0] for p in pts], [p[1] for p in pts],
                                markersize=4, linewidth=1.4,
                                label=LABEL[algo], **STYLE[algo])
                if LABEL[algo] not in labels:
                    handles.append(line)
                    labels.append(LABEL[algo])
        ax.axhline(1.0, color="#888888", linewidth=0.8, linestyle=":", zorder=0)
        ax.set_title("%s (%s threads)" % (ds, tmax))
        if idx % ncols == 0:
            ax.set_ylabel("Speedup over sequential")
        if idx // ncols == nrows - 1:
            ax.set_xlabel("k")

    for idx in range(len(datasets), nrows * ncols):
        axes[idx // ncols][idx % ncols].axis("off")

    fig.legend(handles, labels, loc="lower center", ncol=min(4, len(labels)),
               frameon=False, bbox_to_anchor=(0.5, -0.02))
    fig.tight_layout()
    fig.savefig(outfile)
    plt.close(fig)
    return outfile


def traversal_figure(rows, datasets, outfile):
    """The reversed-order effect: SEQ-REV's work relative to SEQ, against k.

    This isolates the enumeration order from everything else. SEQ-REV differs
    from SEQ in one line - the top-level prefix loop runs backwards, which is
    the order a single fork/join worker uses - so the distance from 1.0 is the
    whole effect of visiting cheap prefixes before expensive ones. Values below
    1.0 mean the order paid for itself; above 1.0 mean it cost more than it
    saved.
    """
    have = [d for d in datasets
            if any(r["dataset"] == d and r["algorithm"] == "SEQREV"
                   and r["workratio"] for r in rows)]
    if not have:
        return None

    nrows, ncols = grid(len(have))
    fig, axes = plt.subplots(nrows, ncols, figsize=(3.1 * ncols, 2.4 * nrows),
                             squeeze=False)
    for idx, ds in enumerate(have):
        ax = axes[idx // ncols][idx % ncols]
        pts = sorted((r["k"], r["workratio"]) for r in rows
                     if r["dataset"] == ds and r["algorithm"] == "SEQREV"
                     and r["workratio"])
        ax.plot([p[0] for p in pts], [p[1] for p in pts], marker="o",
                markersize=4, linewidth=1.4, color="#6a3d9a")
        ax.axhline(1.0, color="#888888", linewidth=0.8, linestyle=":", zorder=0)
        ax.set_title(ds)
        if idx % ncols == 0:
            ax.set_ylabel("Work vs sequential")
        if idx // ncols == nrows - 1:
            ax.set_xlabel("k")
    for idx in range(len(have), nrows * ncols):
        axes[idx // ncols][idx % ncols].axis("off")
    fig.tight_layout()
    fig.savefig(outfile)
    plt.close(fig)
    return outfile


# ---------------------------------------------------------------------------
# LaTeX tables
# ---------------------------------------------------------------------------

def tex_escape(s):
    return str(s).replace("_", r"\_").replace("%", r"\%").replace("&", r"\&")


def fmt(v, nd=2, dash="--"):
    return dash if v is None else ("%.*f" % (nd, v))


def table_main(rows, datasets, outfile):
    """Per dataset and strategy at the highest thread count."""
    lines = [
        "% Generated by analyze.py -- do not edit by hand.",
        r"\begin{table}[htbp]",
        r"\centering",
        r"\caption{Performance at the highest thread count. Times are the mean "
        r"of the measured repetitions; speedup and work ratio are geometric "
        r"means over the $k$ values of each dataset. Work ratio is the number "
        r"of join attempts relative to the sequential baseline, so values above "
        r"one mean the strategy explored more of the search space than was "
        r"necessary.}",
        r"\label{tab:main}",
        r"\begin{tabular}{llrrr}",
        r"\toprule",
        r"Dataset & Strategy & Mean time (ms) & Speedup & Work ratio \\",
        r"\midrule",
    ]
    for ds in datasets:
        tmax = max((r["threads"] for r in rows
                    if r["dataset"] == ds and r["algorithm"] in PARALLEL),
                   default=None)
        first = True
        for algo in PARALLEL:
            sel = [r for r in rows if r["dataset"] == ds
                   and r["algorithm"] == algo and r["threads"] == tmax
                   and r["status"] == "OK"]
            if not sel:
                continue
            t = sum(r["mean_ms"] for r in sel) / len(sel)
            lines.append("%s & %s & %s & %s & %s \\\\" % (
                tex_escape(ds) if first else "",
                LABEL[algo],
                "%.0f" % t,
                fmt(geomean([r["speedup"] for r in sel])) + r"$\times$",
                fmt(geomean([r["workratio"] for r in sel]), 3)))
            first = False
        if not first:
            lines.append(r"\addlinespace")
    if lines[-1] == r"\addlinespace":
        lines.pop()
    lines += [r"\bottomrule", r"\end{tabular}", r"\end{table}"]
    open(outfile, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    return outfile


def table_scaling(rows, datasets, threadvals, outfile):
    """Speedup at every thread count: the direct answer to the scalability
    question the reviewers raised."""
    head = " & ".join(r"%d" % t for t in threadvals)
    lines = [
        "% Generated by analyze.py -- do not edit by hand.",
        r"\begin{table}[htbp]",
        r"\centering",
        r"\caption{Speedup over the sequential baseline at each thread count, "
        r"as a geometric mean over the $k$ values of each dataset.}",
        r"\label{tab:scaling}",
        r"\begin{tabular}{ll%s}" % ("r" * len(threadvals)),
        r"\toprule",
        r"& & \multicolumn{%d}{c}{Threads} \\" % len(threadvals),
        r"\cmidrule(lr){3-%d}" % (2 + len(threadvals)),
        r"Dataset & Strategy & %s \\" % head,
        r"\midrule",
    ]
    for ds in datasets:
        first = True
        for algo in PARALLEL:
            cells = []
            for t in threadvals:
                vals = [r["speedup"] for r in rows if r["dataset"] == ds
                        and r["algorithm"] == algo and r["threads"] == t]
                cells.append(fmt(geomean(vals)))
            if all(c == "--" for c in cells):
                continue
            lines.append("%s & %s & %s \\\\" % (
                tex_escape(ds) if first else "", LABEL[algo], " & ".join(cells)))
            first = False
        if not first:
            lines.append(r"\addlinespace")
    if lines[-1] == r"\addlinespace":
        lines.pop()
    lines += [r"\bottomrule", r"\end{tabular}", r"\end{table}"]
    open(outfile, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    return outfile


def table_traversal(rows, datasets, outfile):
    """SEQ-REV against SEQ: the enumeration-order effect on its own."""
    lines = [
        "% Generated by analyze.py -- do not edit by hand.",
        r"\begin{table}[htbp]",
        r"\centering",
        r"\caption{Effect of reversing the order in which top-level prefixes "
        r"are enumerated, measured on one thread. SEQ-REV differs from the "
        r"sequential baseline only in that it visits prefixes in ascending "
        r"rather than descending PTWU order, which is the order a single "
        r"fork/join worker uses. Values below one mean the order reduced the "
        r"work; above one mean it increased it. Both columns are geometric "
        r"means over the $k$ values of each dataset.}",
        r"\label{tab:traversal}",
        r"\begin{tabular}{lrr}",
        r"\toprule",
        r"Dataset & Work vs SEQ & Time vs SEQ \\",
        r"\midrule",
    ]
    seq = {(r["dataset"], r["k"], r["minProb"]): r for r in rows
           if r["algorithm"] == "SEQ"}
    any_row = False
    for ds in datasets:
        sel = [r for r in rows if r["dataset"] == ds
               and r["algorithm"] == "SEQREV" and r["status"] == "OK"]
        if not sel:
            continue
        w = geomean([r["workratio"] for r in sel])
        t = []
        for r in sel:
            base = seq.get((r["dataset"], r["k"], r["minProb"]))
            if base and base["mean_ms"] and r["mean_ms"]:
                t.append(r["mean_ms"] / base["mean_ms"])
        lines.append("%s & %s & %s \\\\" % (tex_escape(ds), fmt(w, 2),
                                          fmt(geomean(t), 2)))
        any_row = True
    if not any_row:
        return None
    lines += [r"\bottomrule", r"\end{tabular}", r"\end{table}"]
    open(outfile, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    return outfile


def load_stats(stats_dir, datasets):
    """Stats records for the datasets that have results, in presentation order.

    A stats file for a dataset that was probed and dropped does not belong in
    the paper's tables, and a dataset with results but no stats file is a gap
    the caller is told about rather than one that silently leaves a row out.
    """
    recs = {}
    for path in glob.glob(os.path.join(stats_dir, "*_stats.json")):
        with open(path, encoding="utf-8") as f:
            rec = json.load(f)
        recs[rec["dataset"]] = rec

    lower = {name.lower(): name for name in recs}
    rows_out, missing = [], []
    for name in datasets:
        rec = recs.get(name) or recs.get(lower.get(name.lower(), ""))
        if rec is None:
            missing.append(name)
        else:
            rows_out.append((name, rec))
    return rows_out, missing


def table_datasets(rows_out, outfile):
    """Dataset characteristics, read from the generator's own stats files so the
    numbers in the paper cannot disagree with the data that was actually used.
    """
    if not rows_out:
        return None

    lines = [
        "% Generated by analyze.py from the *_stats.json files -- do not edit.",
        r"\begin{table}[htbp]",
        r"\centering",
        r"\caption{Characteristics of the benchmark datasets. Density is the "
        r"mean transaction length as a percentage of the number of distinct "
        r"items.}",
        r"\label{tab:datasets}",
        r"\begin{tabular}{lrrrrrr}",
        r"\toprule",
        r"Dataset & $|D|$ & $|I|$ & Avg.\ len. & Max len. & Density (\%) "
        r"& Negative (\%) \\",
        r"\midrule",
    ]
    for name, rec in rows_out:
        lines.append("%s & %s & %d & %.2f & %d & %.2f & %.1f \\\\" % (
            tex_escape(name), "{:,}".format(rec["transactions"]),
            rec["distinct_items"], rec["avg_transaction_length"],
            rec["max_transaction_length"], rec["density_percent"],
            rec["percent_negative"]))
    lines += [r"\bottomrule", r"\end{tabular}", r"\end{table}"]
    open(outfile, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    return outfile


def table_parameters(rows_out, outfile):
    """The utility model each dataset carries: quantities, probabilities and the
    profit table. This is Table 4, and it is the table the paper has previously
    got wrong.

    Three statements in the earlier Section 5 described a parameter regime the
    data does not have, and every one of them would have been caught by printing
    these columns side by side: a probability range that stops at 0.80 rather
    than reaching 1.0, symmetric mean profits where the sign was assigned at
    random, and one dataset whose profits are strongly asymmetric because its
    signs follow utility. Generating the table from the stats files instead of
    from prose makes that class of error impossible to repeat.

    It also makes the one deliberate exception visible rather than buried: a
    dataset carrying real quantities and real probabilities stands out in these
    columns at a glance, which is exactly what a reader needs in order to judge
    whether the conclusions depend on synthetic parameters.
    """
    if not rows_out:
        return None
    lines = [
        "% Generated by analyze.py from the *_stats.json files -- do not edit.",
        r"\begin{table}[htbp]",
        r"\centering",
        r"\caption{The utility model of each dataset: mean purchase quantity, "
        r"the range and mean of the existence probabilities, the share of items "
        r"carrying a negative profit, and the mean profit of the positive and "
        r"negative items. Rows whose quantities and probabilities come from the "
        r"standardised generation protocol share the same parameters by "
        r"construction; a row that departs from them carries values measured "
        r"from real data.}",
        r"\label{tab:parameters}",
        r"\begin{tabular}{lrrrrrrr}",
        r"\toprule",
        r"& & \multicolumn{3}{c}{Existence probability} & & "
        r"\multicolumn{2}{c}{Mean profit} \\",
        r"\cmidrule(lr){3-5} \cmidrule(lr){7-8}",
        r"Dataset & Mean $q$ & Min & Max & Mean & Neg.\ (\%) "
        r"& Positive & Negative \\",
        r"\midrule",
    ]
    def prob(v):
        """Two decimals where that is exact, four where the data has them, so a
        generated 0.80 and a measured 0.5801 both read correctly."""
        return "%.2f" % v if abs(v * 100 - round(v * 100)) < 1e-9 else "%.4f" % v

    for name, r in rows_out:
        lines.append("%s & %.2f & %s & %s & %s & %.1f & %s & %s \\\\" % (
            tex_escape(name), r["qty_mean"], prob(r["prob_min"]),
            prob(r["prob_max"]), prob(r["prob_mean"]), r["percent_negative"],
            fmt(r.get("avg_positive_profit"), 2),
            fmt(r.get("avg_negative_profit"), 2)))
    lines += [r"\bottomrule", r"\end{tabular}", r"\end{table}"]
    open(outfile, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    return outfile


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------

def validate(rows, files, cv_warn, drifted, missing_stats, keep_drifted,
             replaced):
    """Checks that must hold before any of this is written into the paper.

    These are the questions a referee would ask about the experimental section,
    asked automatically instead of by eye over hundreds of rows.
    """
    out = ["VALIDATION REPORT", "=" * 70, "",
           "Read %d summary files, %d rows." % (len(files), len(rows))]
    if replaced:
        out.append("%d row(s) superseded by a later --results directory."
                   % replaced)
    out.append("")
    problems = 0

    ok = [r for r in rows if r["status"] == "OK"]
    timeouts = [r for r in rows if r["status"] != "OK"]

    # 0. measurement conditions
    out.append("0. Measurement conditions (one-thread control)")
    out.append("   TPB, PLM and PC on one thread do exactly SEQ's work, so their")
    out.append("   times must equal SEQ's. Where they do not, that cell's timings")
    out.append("   were taken while the machine was changing speed.")
    if drifted:
        problems += 1
        out.append("   FAIL: %d of %d cells are inconsistent:"
                   % (len(drifted), len({(r["dataset"], r["k"], r["minProb"])
                                         for r in ok})))
        for (ds, k, p), (gap, peers) in sorted(drifted.items(),
                                               key=lambda kv: -kv[1][0]):
            shown = "  ".join("%s=%.0f" % (a, t) for a, t in peers)
            out.append("      %-12s k=%-6d gap %.0f%%   %s"
                       % (ds, k, 100 * gap, shown))
        out.append("   %s" % ("These cells are INCLUDED (--keep-drifted): every "
                              "speedup in them is unreliable."
                              if keep_drifted else
                              "These cells are excluded from the figures and "
                              "tables. Re-run them under steady conditions "
                              "(mains power, performance profile, no other "
                              "load) and merge the new results in."))
    else:
        out.append("   PASS: every cell is internally consistent.")
    out.append("")

    # 0b. stats files
    if missing_stats:
        out.append("0b. Dataset table")
        out.append("   No *_stats.json for: %s" % ", ".join(missing_stats))
        out.append("   Those datasets have results but cannot be described in "
                   "the dataset table. Generate their stats files, or the table "
                   "will not match the results.")
        out.append("")

    # 1. correctness
    mism = [r for r in ok if r["matchesSEQ"] == "NO"]
    unchecked = [r for r in ok if r["matchesSEQ"] == "unchecked"]
    out.append("1. Correctness against the sequential baseline")
    if mism:
        problems += 1
        out.append("   FAIL: %d rows disagree with SEQ:" % len(mism))
        for r in mism[:20]:
            out.append("      %s %s k=%d t=%d" % (r["dataset"], r["algorithm"],
                                                  r["k"], r["threads"]))
    else:
        out.append("   PASS: all %d completed rows match SEQ." %
                   len([r for r in ok if r["matchesSEQ"] == "yes"]))
    if unchecked:
        out.append("   NOTE: %d rows had no SEQ reference (SEQ excluded or "
                   "timed out); they are unverified, not wrong." % len(unchecked))
    out.append("")

    # 2. timeouts
    out.append("2. Timeouts")
    if timeouts:
        out.append("   %d configurations abandoned:" % len(timeouts))
        by_ds = defaultdict(list)
        for r in timeouts:
            by_ds[r["dataset"]].append("%s k=%d t=%d" %
                                       (r["algorithm"], r["k"], r["threads"]))
        for ds in sorted(by_ds):
            out.append("      %-12s %s" % (ds, ", ".join(by_ds[ds])))
        out.append("   These must be reported in the paper, not silently "
                   "omitted from the figures.")
    else:
        out.append("   None.")
    out.append("")

    # 3. run-to-run stability
    out.append("3. Run-to-run variability (coefficient of variation)")
    noisy = [r for r in ok if r["mean_ms"] and r["stdev_ms"] is not None
             and r["mean_ms"] > 0
             and 100.0 * r["stdev_ms"] / r["mean_ms"] > cv_warn]
    if noisy:
        out.append("   %d rows above %.0f%%:" % (len(noisy), cv_warn))
        for r in sorted(noisy, key=lambda r: -r["stdev_ms"] / r["mean_ms"])[:15]:
            out.append("      %-12s %-7s k=%-6d t=%-2d  CV=%5.1f%%  mean=%.0f ms%s"
                       % (r["dataset"], r["algorithm"], r["k"], r["threads"],
                          100.0 * r["stdev_ms"] / r["mean_ms"], r["mean_ms"],
                          "   [drifted cell]" if r.get("drifted") else ""))
        out.append("   High variance is not automatically a defect, but each "
                   "case needs an explanation in the paper.")
    else:
        out.append("   PASS: every row below %.0f%%." % cv_warn)
    out.append("")

    # 4. sanity of the single-thread rows
    out.append("4. Single-thread sanity")
    bad = []
    for r in ok:
        if r["algorithm"] in PARALLEL and r["threads"] == 1 and r["workratio"]:
            if abs(r["workratio"] - 1.0) > 0.02 and r["algorithm"] != "FJ":
                bad.append(r)
    if bad:
        out.append("   %d rows where a one-thread strategy did not do the same "
                   "work as SEQ:" % len(bad))
        for r in bad[:15]:
            out.append("      %-12s %-7s k=%-6d work ratio=%.3f"
                       % (r["dataset"], r["algorithm"], r["k"], r["workratio"]))
        out.append("   A strategy running on one thread explores the same tree "
                   "as SEQ unless its traversal order differs, so anything "
                   "other than 1.000 needs a reason. FJ is exempt: its task "
                   "decomposition reverses the prefix order by construction.")
        problems += 1
    else:
        out.append("   PASS: one-thread work ratios are 1.000 where expected.")
    out.append("")

    # 5. coverage
    out.append("5. Coverage")
    cells = defaultdict(set)
    for r in ok:
        cells[(r["dataset"], r["k"])].add((r["algorithm"], r["threads"]))
    expect_t = sorted({r["threads"] for r in ok if r["algorithm"] in PARALLEL})
    missing = []
    for (ds, k), have in sorted(cells.items()):
        for algo in PARALLEL:
            for t in expect_t:
                if (algo, t) not in have:
                    missing.append("%s k=%d %s t=%d" % (ds, k, algo, t))
    if missing:
        out.append("   %d missing (dataset, k, strategy, threads) "
                   "combinations, first 15:" % len(missing))
        for m in missing[:15]:
            out.append("      " + m)
    else:
        out.append("   PASS: the grid is complete.")
    out.append("")

    out.append("=" * 70)
    out.append("%d blocking problem(s)." % problems if problems
               else "No blocking problems.")
    return "\n".join(out), problems


# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", nargs="+", required=True,
                    help="one or more directories containing <Dataset>/summary.csv")
    ap.add_argument("--stats", default=None,
                    help="directory holding <dataset>_stats.json (for Table 3)")
    ap.add_argument("--out", default="analysis")
    ap.add_argument("--cv-warn", type=float, default=10.0,
                    help="flag rows whose coefficient of variation exceeds this %%")
    ap.add_argument("--drift-tol", type=float, default=0.15,
                    help="relative time gap between SEQ and the one-thread "
                         "strategies above which a cell is treated as drifted "
                         "(default 0.15)")
    ap.add_argument("--keep-drifted", action="store_true",
                    help="include drifted cells in the figures and tables "
                         "instead of leaving them out")
    args = ap.parse_args()

    figdir = os.path.join(args.out, "figures")
    tabdir = os.path.join(args.out, "tables")
    os.makedirs(figdir, exist_ok=True)
    os.makedirs(tabdir, exist_ok=True)

    rows, files, replaced = load(args.results)
    drifted = detect_drift(rows, args.drift_tol)
    rows = derive(rows, drifted)

    ok = [r for r in rows if r["status"] == "OK"]
    # Everything downstream aggregates ratios, and a ratio taken across a change
    # in machine speed is not a property of the algorithms.
    usable = ok if args.keep_drifted else [r for r in ok if not r["drifted"]]

    datasets = order_datasets({r["dataset"] for r in rows})
    threadvals = sorted({r["threads"] for r in usable
                         if r["algorithm"] in PARALLEL})

    written = []
    written.append(panel_figure(
        usable, datasets, threadvals, "speedup", "Speedup over sequential",
        os.path.join(figdir, "fig_scaling.pdf"), reference=1.0))
    written.append(panel_figure(
        usable, datasets, threadvals, "workratio", "Work ratio vs sequential",
        os.path.join(figdir, "fig_workratio.pdf"), reference=1.0))
    written.append(speedup_vs_k(
        usable, datasets, os.path.join(figdir, "fig_speedup_vs_k.pdf")))
    t = traversal_figure(usable, datasets,
                         os.path.join(figdir, "fig_traversal.pdf"))
    if t:
        written.append(t)

    written.append(table_main(usable, datasets,
                              os.path.join(tabdir, "table_main.tex")))
    written.append(table_scaling(usable, datasets, threadvals,
                                 os.path.join(tabdir, "table_scaling.tex")))
    t = table_traversal(usable, datasets,
                        os.path.join(tabdir, "table_traversal.tex"))
    if t:
        written.append(t)

    missing_stats = []
    if args.stats:
        stat_rows, missing_stats = load_stats(args.stats, datasets)
        if not stat_rows:
            print("No usable *_stats.json under %s; skipping the dataset and "
                  "parameter tables." % args.stats)
        for t in (table_datasets(stat_rows,
                                 os.path.join(tabdir, "table_datasets.tex")),
                  table_parameters(stat_rows,
                                   os.path.join(tabdir, "table_parameters.tex"))):
            if t:
                written.append(t)

    report, problems = validate(rows, files, args.cv_warn, drifted,
                                missing_stats, args.keep_drifted, replaced)
    rpath = os.path.join(args.out, "report.txt")
    open(rpath, "w", encoding="utf-8").write(report + "\n")
    written.append(rpath)

    print(report)
    print()
    for w in written:
        print("wrote", w)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())