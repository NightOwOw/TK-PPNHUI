"""
Verify the three falsifiable claims the new Section 6.1 makes, on the FULL
merged measurement set rather than on a single results directory.

Run from the repository root:

    python verify_superlinear.py

It reads the four directories behind the paper, applies the same supersede
order analyze.py uses (a later directory wins for the same
dataset/algorithm/k/minProb/threads), and prints:

  1. the largest single cell at 8 threads        -> paper says 7.71x
  2. every cell whose speedup exceeds its thread count by >25%
     -> paper says all seven are FJ, on Chess / Accidents / T10I4D100K
  3. the largest cell at 2 and 4 threads         -> paper says 4.29x and 5.20x

If any line disagrees with the manuscript, change the manuscript to the
printed value. These are now numbers a referee can recompute from the
deposit, so they have to be the merged ones.
"""

import csv
import glob
import os

# the paper's four directories, in merge order (later wins)
RESULTS = [
    "results_final_light_v2",
    "results_fix",
    "results_heavy_v2",
    "results_kosarak_fill",
]

ORDER_DATASETS = {"Chess", "Accidents", "T10I4D100K"}  # work vs SEQ < 1


def load():
    """Merge the summary files with analyze.py's supersede semantics."""
    merged = {}
    found_any = False
    for d in RESULTS:
        # summary.csv, or summary_<dataset>.csv if the files were renamed
        paths = sorted(
            glob.glob(os.path.join(d, "**", "summary*.csv"), recursive=True)
        )
        if not paths:
            print(f"  ! no summary file found under {d}/")
            continue
        found_any = True
        for p in paths:
            with open(p, newline="", encoding="utf-8-sig") as fh:
                for r in csv.DictReader(fh):
                    key = (
                        r["dataset"],
                        r["algorithm"],
                        r["k"],
                        r["minProb"],
                        r["threads"],
                    )
                    merged[key] = r
    if not found_any:
        raise SystemExit(
            "No summary files found. Run this from the repository root, "
            "the folder that contains results_final_light_v2/ etc."
        )
    return merged


def main():
    merged = load()
    print(f"merged rows: {len(merged)}")

    def completed(row):
        # the harness writes OK; be case- and value-tolerant, and treat a
        # file with no status column as completed by construction
        return row.get("status", "").strip().upper() in ("", "OK", "COMPLETED", "DONE")

    seq = {
        (r["dataset"], r["k"], r["minProb"]): float(r["mean_ms"])
        for r in merged.values()
        if r["algorithm"] == "SEQ" and completed(r)
    }
    if not seq:
        raise SystemExit(
            "No sequential baseline rows survived the status filter. "
            "Status values present: "
            + repr(sorted({r.get("status") for r in merged.values()}))
        )
    print(f"sequential baseline cells: {len(seq)}")

    cells = []
    for r in merged.values():
        if r["algorithm"] == "SEQ":
            continue
        key = (r["dataset"], r["k"], r["minProb"])
        if key not in seq or not completed(r):
            continue
        try:
            t = int(r["threads"])
            ms = float(r["mean_ms"])
        except (ValueError, KeyError):
            continue
        if ms <= 0:
            continue
        cells.append(
            {
                "speedup": seq[key] / ms,
                "threads": t,
                "dataset": r["dataset"],
                "algorithm": r["algorithm"],
                "k": r["k"],
            }
        )

    print("CLAIM 1 - largest single cell at each thread count")
    for t in (1, 2, 4, 8):
        sub = [c for c in cells if c["threads"] == t]
        if not sub:
            continue
        b = max(sub, key=lambda c: c["speedup"])
        print(
            f"  t={t}: {b['speedup']:5.2f}x  "
            f"{b['dataset']:<11} {b['algorithm']:<6} k={b['k']}"
        )
    print("  manuscript: 7.71x at t=8, 5.20x at t=4, 4.29x at t=2\n")

    print("CLAIM 2 - cells exceeding their thread count by more than 25%")
    hits = [c for c in cells if c["threads"] > 1 and c["speedup"] > 1.25 * c["threads"]]
    hits.sort(key=lambda c: -c["speedup"] / c["threads"])
    for c in hits:
        flag = "" if c["dataset"] in ORDER_DATASETS else "   <-- NOT an order-effect dataset"
        algo = "" if c["algorithm"] == "FJ" else "   <-- NOT fork-join"
        print(
            f"  {c['speedup']:5.2f}x on {c['threads']} threads  "
            f"{c['dataset']:<11} {c['algorithm']:<6} k={c['k']:<5} "
            f"({c['speedup'] / c['threads']:.2f}x linear){flag}{algo}"
        )
    print(f"  total: {len(hits)}   manuscript: 7, all FJ, all on Chess/Accidents/T10I4D100K")
    bad = [c for c in hits if c["algorithm"] != "FJ" or c["dataset"] not in ORDER_DATASETS]
    if bad:
        print("  !! the 'confined to one strategy' sentence is NOT safe as written")
    else:
        print("  OK: the sentence holds on the merged data")
    print()

    print("CLAIM 3 - any cell at or above its thread count at 8 threads")
    over8 = [c for c in cells if c["threads"] == 8 and c["speedup"] > 8]
    if over8:
        print("  !! super-linear at 8 threads:")
        for c in over8:
            print(f"     {c['speedup']:.2f}x  {c['dataset']} {c['algorithm']} k={c['k']}")
        print("  the sentence 'at eight threads no strategy exceeds its thread count' is false")
    else:
        print("  OK: no cell exceeds 8x at 8 threads")


if __name__ == "__main__":
    main()