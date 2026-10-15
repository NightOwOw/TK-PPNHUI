#!/usr/bin/env python3
"""
make_stats.py - write a dataset's <name>_stats.json from the data files that
are actually being measured.

WHY THIS EXISTS AND WHEN TO USE IT
    generate_dataset.py writes _stats.json as a side effect of BUILDING a
    dataset, so a dataset that was never built by it has no stats file. Liquor
    is such a dataset: it predates the current generation pipeline.

    The obvious fix - running generate_dataset.py for it - would be wrong. That
    script draws fresh quantities, probabilities and profit signs from its seed,
    so it would REPLACE the data on disk with a different dataset, silently
    invalidating every result already measured on it. This script only reads.
    It derives the same numbers from the files the experiments actually used,
    which is the stronger guarantee: the dataset table then describes the data
    that produced the results, not data that would have been produced by
    re-running a generator.

    Use it for any dataset that has _database.txt and _profit.txt but no
    _stats.json, and to re-derive a stats file whose data may have been edited
    by hand.

USAGE
    python make_stats.py --data-dir data_new --name Liquor
    python make_stats.py --data-dir data_new --all          # every missing one
    python make_stats.py --data-dir data_new --all --force  # re-derive all

    Files are read as <data-dir>/<name lowercased>_database.txt and
    _profit.txt, matching how Experiment.java resolves them, and written as
    <name lowercased>_stats.json.

OUTPUT
    Exactly the fields generate_dataset.py's stats() produces, computed by the
    same formulas, so a file written here is interchangeable with one written
    there and analyze.py consumes both without knowing the difference.
"""

import argparse
import glob
import json
import os
import statistics
import sys


# The "dataset" field carries the display name, which is what analyze.py and
# the paper's table use. File names are lowercase; display names are not
# recoverable from them, so known ones are listed and anything else falls back
# to what the caller passed.
DISPLAY = {
    "chess": "Chess", "mushroom": "Mushroom", "c20d10k": "c20d10k",
    "accidents": "Accidents", "pumsb": "Pumsb", "kosarak": "Kosarak",
    "liquor": "Liquor", "retail": "Retail", "foodmart": "foodmart",
    "t10i4d100k": "T10I4D100K", "t25i10d10k": "t25i10d10k",
}


def read_database(path):
    """Parse "item:qty:prob ..." lines.

    Returns (transaction lengths, quantities, probabilities). Malformed tokens
    are reported rather than skipped: a token this parser cannot read is one the
    Java reader may have read differently, and a stats file that quietly
    described a different dataset than the one measured would defeat the point.
    """
    lengths, qty, prob = [], [], []
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            toks = line.split()
            if not toks:
                continue
            lengths.append(len(toks))
            for tok in toks:
                parts = tok.split(":")
                if len(parts) != 3:
                    sys.exit("%s line %d: cannot parse %r (expected "
                             "item:qty:prob)" % (path, lineno, tok))
                try:
                    qty.append(int(parts[1]))
                    prob.append(float(parts[2]))
                except ValueError:
                    sys.exit("%s line %d: cannot parse %r" % (path, lineno, tok))
    if not lengths:
        sys.exit("%s contains no transactions" % path)
    return lengths, qty, prob


def read_profit(path):
    profit = {}
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            parts = line.split()
            if not parts:
                continue
            if len(parts) < 2:
                sys.exit("%s line %d: expected 'item profit'" % (path, lineno))
            try:
                profit[parts[0]] = float(parts[1])
            except ValueError:
                sys.exit("%s line %d: %r is not a number" % (path, lineno,
                                                             parts[1]))
    if not profit:
        sys.exit("%s contains no profit entries" % path)
    return profit


def stats(name, lengths, qty, prob, profit):
    """The fields generate_dataset.py's stats() writes, by the same formulas.

    density_percent divides by the size of the profit table rather than by the
    number of items seen in the transactions, exactly as the generator does, so
    the two agree even when an item appears in the profit table but in no
    transaction.
    """
    pos = [v for v in profit.values() if v > 0]
    neg = [v for v in profit.values() if v < 0]
    return {
        "dataset": name,
        "transactions": len(lengths),
        "distinct_items": len(profit),
        "avg_transaction_length": round(statistics.mean(lengths), 2),
        "max_transaction_length": max(lengths),
        "density_percent": round(100.0 * statistics.mean(lengths) / len(profit), 4),
        "negative_items": len(neg),
        "percent_negative": round(100.0 * len(neg) / len(profit), 1),
        "profit_min": min(profit.values()),
        "profit_max": max(profit.values()),
        "avg_positive_profit": round(statistics.mean(pos), 2) if pos else None,
        "avg_negative_profit": round(statistics.mean(neg), 2) if neg else None,
        "qty_mean": round(statistics.mean(qty), 2),
        "prob_min": min(prob),
        "prob_max": max(prob),
        "prob_mean": round(statistics.mean(prob), 4),
    }


def derive(data_dir, name, force):
    stem = name.lower()
    db = os.path.join(data_dir, "%s_database.txt" % stem)
    pt = os.path.join(data_dir, "%s_profit.txt" % stem)
    out = os.path.join(data_dir, "%s_stats.json" % stem)

    if not (os.path.exists(db) and os.path.exists(pt)):
        print("  %-12s SKIP  (no %s_database.txt / _profit.txt)" % (name, stem))
        return False
    if os.path.exists(out) and not force:
        print("  %-12s SKIP  (stats file already exists; --force to replace)"
              % name)
        return False

    lengths, qty, prob = read_database(db)
    profit = read_profit(pt)
    s = stats(DISPLAY.get(stem, name), lengths, qty, prob, profit)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(s, f, indent=2)

    print("  %-12s wrote %s" % (name, out))
    print("      %d transactions, %d items, avg len %.2f, density %.2f%%, "
          "%.1f%% negative" % (s["transactions"], s["distinct_items"],
                               s["avg_transaction_length"],
                               s["density_percent"], s["percent_negative"]))
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="data_new")
    ap.add_argument("--name", help="dataset to derive, e.g. Liquor")
    ap.add_argument("--all", action="store_true",
                    help="every dataset in --data-dir that has data files")
    ap.add_argument("--force", action="store_true",
                    help="overwrite an existing stats file")
    args = ap.parse_args()

    if not (args.name or args.all):
        ap.error("pass --name NAME or --all")
    if not os.path.isdir(args.data_dir):
        sys.exit("No such directory: %s" % args.data_dir)

    if args.all:
        names = sorted(os.path.basename(p)[:-len("_database.txt")]
                       for p in glob.glob(os.path.join(args.data_dir,
                                                       "*_database.txt")))
        if not names:
            sys.exit("No *_database.txt under %s" % args.data_dir)
    else:
        names = [args.name]

    written = sum(derive(args.data_dir, DISPLAY.get(n.lower(), n), args.force)
                  for n in names)
    print("\n%d stats file(s) written." % written)
    return 0


if __name__ == "__main__":
    sys.exit(main())