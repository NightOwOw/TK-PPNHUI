"""
inspect_datasets.py — Recover the generation protocol from the existing
converted datasets.

Run from the TK-PPNHUI project root (the folder that contains data/):

    python inspect_datasets.py

It looks for data/<name>_database.txt and data/<name>_profit.txt for each
dataset it knows about, and skips any that are missing.
"""

import glob
import os
from collections import Counter

DATA_DIR = "data_new"


def inspect(name):
    db_path = os.path.join(DATA_DIR, "%s_database.txt" % name)
    pt_path = os.path.join(DATA_DIR, "%s_profit.txt" % name)
    if not (os.path.exists(db_path) and os.path.exists(pt_path)):
        return

    qty = Counter()
    prob = Counter()
    n_tx = 0
    lengths = []

    with open(db_path, encoding="utf-8") as f:
        for line in f:
            toks = line.split()
            if not toks:
                continue
            n_tx += 1
            lengths.append(len(toks))
            for tok in toks:
                parts = tok.split(":")
                if len(parts) != 3:
                    continue
                qty[int(parts[1])] += 1
                prob[parts[2]] += 1

    profits = []
    with open(pt_path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 2:
                profits.append(float(parts[1]))

    pvals = sorted(float(p) for p in prob)
    n_prob = sum(prob.values())
    neg = [p for p in profits if p < 0]
    pos = [p for p in profits if p > 0]
    all_int = all(float(p).is_integer() for p in profits)
    decimals = Counter(len(p.split(".")[1]) if "." in p else 0 for p in prob)

    print("=== %s" % name)
    print("  transactions      %d" % n_tx)
    print("  distinct items    %d" % len(profits))
    print("  avg / max txlen   %.2f / %d"
          % (sum(lengths) / len(lengths), max(lengths)))
    print("  density           %.4f%%"
          % (100.0 * (sum(lengths) / len(lengths)) / len(profits)))
    print("  qty range         %d..%d   distinct=%d   counts=%s"
          % (min(qty), max(qty), len(qty), dict(sorted(qty.items()))))
    print("  prob range        %.4f..%.4f   distinct values=%d"
          % (pvals[0], pvals[-1], len(prob)))
    print("  prob == 1.0       %d of %d (%.2f%%)"
          % (prob.get("1.0", 0) + prob.get("1", 0) + prob.get("1.00", 0),
             n_prob,
             100.0 * (prob.get("1.0", 0) + prob.get("1", 0) + prob.get("1.00", 0))
             / n_prob))
    print("  prob >= 0.9       %d (%.2f%%)"
          % (sum(c for v, c in prob.items() if float(v) >= 0.9),
             100.0 * sum(c for v, c in prob.items() if float(v) >= 0.9) / n_prob))
    print("  prob decimals     %s" % dict(sorted(decimals.items())))
    print("  profit range      %.2f .. %.2f   all integers=%s"
          % (min(profits), max(profits), all_int))
    print("  negative items    %d / %d (%.1f%%)"
          % (len(neg), len(profits), 100.0 * len(neg) / len(profits)))
    print("  avg pos / avg neg %.2f / %.2f"
          % (sum(pos) / len(pos) if pos else 0,
             sum(neg) / len(neg) if neg else 0))
    print("  |profit| distinct %d" % len({abs(p) for p in profits}))
    print()


def main():
    if not os.path.isdir(DATA_DIR):
        raise SystemExit("No %s/ directory here. Run from the project root."
                         % DATA_DIR)

    found = sorted({os.path.basename(p)[:-len("_database.txt")]
                    for p in glob.glob(os.path.join(DATA_DIR, "*_database.txt"))})
    if not found:
        raise SystemExit("No *_database.txt files in %s/" % DATA_DIR)

    print("Found: %s\n" % ", ".join(found))
    for name in found:
        inspect(name)


if __name__ == "__main__":
    main()
