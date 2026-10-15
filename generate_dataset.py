"""
generate_dataset.py — Build a TK-PPNHUI benchmark from a raw SPMF transaction file.

Reproduces the protocol recovered from the four original datasets and applies
it identically to every benchmark, so that the whole suite is comparable:

    quantity     q(i,T) ~ UniformInt(1, 5)
    probability  p(i,T) = round(Uniform(0.01, 0.80), 2)
    profit       |pr(i)| resampled from the empirical magnitude distribution
                 of the original datasets (--profit-ref), or drawn from a
                 log-normal fitted to it (--profit-dist lognormal)
    sign         NEG_FRACTION of items are negated. Two rules are available:
                 "random"  (default) a uniformly random subset, which is what
                           the original synthetic benchmarks actually used --
                           their average positive and negative profits are
                           symmetric, which only happens if the sign is
                           independent of the magnitude
                 "lowest"  the items with the lowest total utility, the rule
                           used for Liquor

THE BASE CASE
  Two switches build the ordinary high-utility mining instance that classical
  miners solve, so that the general model can be measured against them on their
  own problem rather than only described:

    --neg-fraction 0      every profit stays positive
    --prob-mode certain   p(i,T) = 1 for every entry

  Together these make the expected utility equal the ordinary utility, the
  existential-probability filter vacuous, PTWU the classical
  transaction-weighted utility and rem the classical remaining utility. The
  resulting files are ordinary HUIM input that happens to be written in the
  uncertain format.

Everything is driven by --seed, so a run is exactly reproducible.

INPUT
  A raw SPMF transaction file: one transaction per line, space-separated
  integer item ids. This is the plain format, not the utility format.

OUTPUT
  <outdir>/<name>_database.txt   "item:qty:prob ..."
  <outdir>/<name>_profit.txt     "item profit"
  <outdir>/<name>_stats.json     numbers for Table 3 and Table 4

EXAMPLE
  python generate_dataset.py --name Kosarak --input raw/kosarak.txt \
      --profit-ref data/chess_profit.txt data/mushroom_profit.txt \
                   data/retail_profit.txt \
      --outdir data --seed 42

  python generate_dataset.py --name ChessBase --input raw/chess.txt \
      --profit-ref data/chess_profit.txt \
      --neg-fraction 0 --prob-mode certain --outdir data_base --seed 42
"""

import argparse
import json
import math
import os
import random
import statistics
from collections import defaultdict

NEG_FRACTION = 0.30          # identical for every dataset
QTY_MIN, QTY_MAX = 1, 5
PROB_MIN, PROB_MAX = 0.01, 0.80
PROB_DECIMALS = 2

# The base case: no negative profits and no uncertainty. With every profit
# positive, PTU(T) sums the whole transaction, so PTWU becomes the classical
# transaction-weighted utility and rem the classical remaining utility; with
# every probability 1, the expected utility reduces to the ordinary utility and
# the existential-probability filter can never reject anything.
CERTAIN_PROB = 1.0


# --------------------------------------------------------------------------
# Profit magnitudes
# --------------------------------------------------------------------------

def load_reference_magnitudes(paths):
    """Pool the |profit| values of the reference profit tables.

    Resampling from these guarantees that new datasets carry the same profit
    magnitude distribution as the original benchmarks, without having to
    guess which parametric family produced them.
    """
    mags = []
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                if len(parts) >= 2:
                    mags.append(abs(float(parts[1])))
    if not mags:
        raise SystemExit("No profit values found in the reference tables.")
    return mags


def sample_magnitudes(n, reference, rng, dist):
    if dist == "empirical":
        return [rng.choice(reference) for _ in range(n)]

    # Log-normal fitted to the reference magnitudes, then rounded to integers
    # and clipped to the observed range.
    logs = [math.log(m) for m in reference if m > 0]
    mu = statistics.mean(logs)
    sigma = statistics.pstdev(logs)
    lo, hi = min(reference), max(reference)
    out = []
    while len(out) < n:
        v = round(math.exp(rng.gauss(mu, sigma)))
        if lo <= v <= hi:
            out.append(float(v))
    return out


# --------------------------------------------------------------------------
# Generation
# --------------------------------------------------------------------------

def read_transactions(path):
    txs = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            items = line.split()
            if items:
                # de-duplicate while preserving order; SPMF files occasionally
                # repeat an item within a transaction
                seen = set()
                uniq = [i for i in items if not (i in seen or seen.add(i))]
                txs.append(uniq)
    if not txs:
        raise SystemExit("No transactions read from %s" % path)
    return txs


def generate(txs, reference, rng, dist, sign_mode,
             neg_fraction=NEG_FRACTION, prob_mode="uniform"):
    items = sorted({i for tx in txs for i in tx},
                   key=lambda x: int(x) if x.lstrip("-").isdigit() else x)

    magnitudes = sample_magnitudes(len(items), reference, rng, dist)
    magnitude = dict(zip(items, magnitudes))

    # quantities and probabilities, drawn once per (item, transaction)
    qty = []
    prob = []
    for tx in txs:
        qty.append([rng.randint(QTY_MIN, QTY_MAX) for _ in tx])
        if prob_mode == "certain":
            prob.append([CERTAIN_PROB for _ in tx])
        else:
            prob.append([round(rng.uniform(PROB_MIN, PROB_MAX), PROB_DECIMALS)
                         for _ in tx])

    n_neg = int(round(len(items) * neg_fraction))

    if n_neg == 0:
        negative = set()                 # base case: every profit stays positive
    elif sign_mode == "lowest":
        total_util = defaultdict(float)
        for tx, qs in zip(txs, qty):
            for it, q in zip(tx, qs):
                total_util[it] += magnitude[it] * q
        ordered = sorted(items, key=lambda it: total_util[it])
        negative = set(ordered[:n_neg])
    else:
        negative = set(rng.sample(items, n_neg))

    profit = {it: (-magnitude[it] if it in negative else magnitude[it])
              for it in items}
    return items, qty, prob, profit, negative


def write_outputs(name, txs, qty, prob, profit, outdir):
    os.makedirs(outdir, exist_ok=True)
    db_path = os.path.join(outdir, "%s_database.txt" % name.lower())
    pt_path = os.path.join(outdir, "%s_profit.txt" % name.lower())

    with open(db_path, "w", encoding="utf-8") as f:
        for tx, qs, ps in zip(txs, qty, prob):
            f.write(" ".join("%s:%d:%s" % (it, q, ("%.2f" % p).rstrip("0").rstrip("."))
                             for it, q, p in zip(tx, qs, ps)) + "\n")

    with open(pt_path, "w", encoding="utf-8") as f:
        for it in sorted(profit,
                         key=lambda x: int(x) if x.lstrip("-").isdigit() else x):
            f.write("%s %.1f\n" % (it, profit[it]))

    return db_path, pt_path


def stats(name, txs, qty, prob, profit, negative):
    lengths = [len(tx) for tx in txs]
    flat_prob = [p for ps in prob for p in ps]
    flat_qty = [q for qs in qty for q in qs]
    pos = [v for v in profit.values() if v > 0]
    neg = [v for v in profit.values() if v < 0]
    return {
        "dataset": name,
        "transactions": len(txs),
        "distinct_items": len(profit),
        "avg_transaction_length": round(statistics.mean(lengths), 2),
        "max_transaction_length": max(lengths),
        "density_percent": round(100.0 * statistics.mean(lengths) / len(profit), 4),
        "negative_items": len(negative),
        "percent_negative": round(100.0 * len(negative) / len(profit), 1),
        "profit_min": min(profit.values()),
        "profit_max": max(profit.values()),
        "avg_positive_profit": round(statistics.mean(pos), 2) if pos else None,
        "avg_negative_profit": round(statistics.mean(neg), 2) if neg else None,
        "qty_mean": round(statistics.mean(flat_qty), 2),
        "prob_min": min(flat_prob),
        "prob_max": max(flat_prob),
        "prob_mean": round(statistics.mean(flat_prob), 4),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--input", required=True, help="raw SPMF transaction file")
    ap.add_argument("--profit-ref", nargs="+", required=True,
                    help="existing *_profit.txt files to take magnitudes from")
    ap.add_argument("--outdir", default="data")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--profit-dist", choices=("empirical", "lognormal"),
                    default="empirical")
    ap.add_argument("--neg-fraction", type=float, default=NEG_FRACTION,
                    help="fraction of items given a negative profit; 0 builds "
                         "the positive-only base case (default %.2f)"
                         % NEG_FRACTION)
    ap.add_argument("--prob-mode", choices=("uniform", "certain"),
                    default="uniform",
                    help="uniform: p ~ round(Uniform(%.2f, %.2f), %d), the "
                         "standard protocol; certain: p = 1 for every entry, "
                         "which removes uncertainty and makes the expected "
                         "utility equal the ordinary utility"
                         % (PROB_MIN, PROB_MAX, PROB_DECIMALS))
    ap.add_argument("--sign-mode", choices=("random", "lowest"),
                    default="random",
                    help="random: a uniformly random subset is negated, "
                         "matching the original synthetic benchmarks; "
                         "lowest: the lowest-total-utility items are negated")
    args = ap.parse_args()

    if not 0.0 <= args.neg_fraction <= 1.0:
        raise SystemExit("--neg-fraction must be between 0 and 1")

    rng = random.Random(args.seed)
    txs = read_transactions(args.input)
    reference = load_reference_magnitudes(args.profit_ref)

    items, qty, prob, profit, negative = generate(txs, reference, rng,
                                                  args.profit_dist,
                                                  args.sign_mode,
                                                  args.neg_fraction,
                                                  args.prob_mode)
    db_path, pt_path = write_outputs(args.name, txs, qty, prob, profit,
                                     args.outdir)

    s = stats(args.name, txs, qty, prob, profit, negative)
    with open(os.path.join(args.outdir, "%s_stats.json" % args.name.lower()),
              "w", encoding="utf-8") as f:
        json.dump(s, f, indent=2)

    print("wrote %s and %s" % (db_path, pt_path))
    for key in ("transactions", "distinct_items", "avg_transaction_length",
                "density_percent", "percent_negative", "profit_min",
                "profit_max", "avg_positive_profit", "avg_negative_profit"):
        print("  %-24s %s" % (key, s[key]))


if __name__ == "__main__":
    main()