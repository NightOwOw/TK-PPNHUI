#!/usr/bin/env python3
"""
to_spmf.py - convert a base-case dataset into the SPMF high-utility format, so
that an independent exact HUIM implementation can be run on exactly the same
data.

WHY
    The base case (every profit positive, every probability 1) is an ordinary
    high-utility mining instance that happens to be written in the uncertain
    format. Converting it to the format SPMF's miners read lets a published
    exact algorithm - EFIM, HUI-Miner, FHM - be run on the identical input,
    which turns "our general core also solves the classical problem" from a
    claim into a measurement, and turns the correctness argument from
    self-verification into agreement with an independent implementation.

    The conversion is lossless on base-case data and this script proves it
    rather than asserting it: after writing the file it reads it back and
    checks, transaction by transaction, that the items and their utilities
    match the source, and that the declared transaction utility equals the sum
    of the item utilities. Any dataset that is not a genuine base case is
    refused, with the reason, instead of being silently mangled.

FORMAT
    SPMF utility format, one transaction per line:

        item item item:transaction_utility:util util util

    Items ascending by id; the third field lists each item's utility in the
    same order.

USAGE
    python to_spmf.py --data-dir data_base --name ChessBase --out spmf/chessbase.txt

    java -jar spmf.jar run EFIM spmf/chessbase.txt spmf_out.txt 100000

    python compare_spmf.py --spmf spmf_out.txt \\
        --ours results_base/ChessBase/patterns_ChessBase_SEQ_mu100000_p0.70_t1.txt
"""

import argparse
import os
import sys


def read_profit(path):
    profit = {}
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            parts = line.split()
            if not parts:
                continue
            if len(parts) < 2:
                sys.exit("%s line %d: expected 'item profit'" % (path, lineno))
            profit[int(parts[0])] = float(parts[1])
    if not profit:
        sys.exit("%s is empty" % path)
    return profit


def read_database(path):
    """Returns a list of [(item, qty, prob), ...] per transaction."""
    txs = []
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            toks = line.split()
            if not toks:
                continue
            tx = []
            for tok in toks:
                parts = tok.split(":")
                if len(parts) != 3:
                    sys.exit("%s line %d: cannot parse %r" % (path, lineno, tok))
                tx.append((int(parts[0]), int(parts[1]), float(parts[2])))
            txs.append(tx)
    if not txs:
        sys.exit("%s contains no transactions" % path)
    return txs


def check_base_case(txs, profit):
    """Refuse anything the SPMF format cannot represent without losing data."""
    problems = []

    neg = [i for i, v in profit.items() if v < 0]
    if neg:
        problems.append(
            "%d item(s) have a negative profit (e.g. %s). The SPMF utility "
            "format has no way to express them and classical miners assume "
            "non-negative utilities, so the converted file would describe a "
            "different problem." % (len(neg), sorted(neg)[:5]))

    uncertain = set()
    for tx in txs:
        for _, _, p in tx:
            if abs(p - 1.0) > 1e-12:
                uncertain.add(round(p, 6))
    if uncertain:
        problems.append(
            "%d distinct probability value(s) other than 1 are present (e.g. "
            "%s). Dropping them would silently turn an uncertain database into "
            "a certain one and the two have different answers."
            % (len(uncertain), sorted(uncertain)[:5]))

    fractional = set()
    for tx in txs:
        for item, qty, _ in tx:
            u = profit.get(item, 0.0) * qty
            if abs(u - round(u)) > 1e-9:
                fractional.add(item)
    if fractional:
        problems.append(
            "%d item(s) produce a non-integer utility (e.g. %s). SPMF's miners "
            "read utilities as integers, so writing these would change their "
            "values." % (len(fractional), sorted(fractional)[:5]))

    missing = set()
    for tx in txs:
        for item, _, _ in tx:
            if item not in profit:
                missing.add(item)
    if missing:
        problems.append("%d item(s) appear in the database but not in the "
                        "profit table (e.g. %s)."
                        % (len(missing), sorted(missing)[:5]))

    if problems:
        print("This dataset is not a base case and cannot be converted:\n")
        for p in problems:
            print("  - " + p + "\n")
        print("Build one with:  generate_dataset.py --neg-fraction 0 "
              "--prob-mode certain")
        sys.exit(1)


def write_spmf(txs, profit, out_path):
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    total_util = 0
    with open(out_path, "w", encoding="utf-8") as f:
        for tx in txs:
            entries = sorted(((item, int(round(profit[item] * qty)))
                              for item, qty, _ in tx), key=lambda e: e[0])
            tu = sum(u for _, u in entries)
            total_util += tu
            f.write("%s:%d:%s\n"
                    % (" ".join(str(i) for i, _ in entries), tu,
                       " ".join(str(u) for _, u in entries)))
    return total_util


def verify(out_path, txs, profit):
    """Read the written file back and compare it with the source."""
    expected = []
    for tx in txs:
        expected.append(sorted(((item, int(round(profit[item] * qty)))
                                for item, qty, _ in tx), key=lambda e: e[0]))

    with open(out_path, encoding="utf-8") as f:
        lines = [ln for ln in f.read().splitlines() if ln.strip()]

    if len(lines) != len(expected):
        sys.exit("Verification failed: wrote %d transactions, source has %d"
                 % (len(lines), len(expected)))

    for n, (line, exp) in enumerate(zip(lines, expected), 1):
        parts = line.split(":")
        if len(parts) != 3:
            sys.exit("Verification failed at line %d: %r" % (n, line))
        items = [int(x) for x in parts[0].split()]
        tu = int(parts[1])
        utils = [int(x) for x in parts[2].split()]

        if len(items) != len(utils):
            sys.exit("Verification failed at line %d: %d items but %d utilities"
                     % (n, len(items), len(utils)))
        if list(zip(items, utils)) != exp:
            sys.exit("Verification failed at line %d: content differs from the "
                     "source transaction" % n)
        if tu != sum(utils):
            sys.exit("Verification failed at line %d: declared transaction "
                     "utility %d but the item utilities sum to %d"
                     % (n, tu, sum(utils)))
    return len(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", required=True)
    ap.add_argument("--name", required=True,
                    help="dataset name; files are <name lowercased>_database.txt "
                         "and _profit.txt")
    ap.add_argument("--out", required=True, help="SPMF file to write")
    args = ap.parse_args()

    stem = args.name.lower()
    db_path = os.path.join(args.data_dir, "%s_database.txt" % stem)
    pt_path = os.path.join(args.data_dir, "%s_profit.txt" % stem)
    for p in (db_path, pt_path):
        if not os.path.exists(p):
            sys.exit("No such file: %s" % p)

    txs = read_database(db_path)
    profit = read_profit(pt_path)

    check_base_case(txs, profit)
    total = write_spmf(txs, profit, args.out)
    n = verify(args.out, txs, profit)

    items = sorted({i for tx in txs for i, _, _ in tx})
    print("wrote %s" % args.out)
    print("  %d transactions, %d distinct items" % (n, len(items)))
    print("  total database utility %d" % total)
    print("  verified: every transaction round-trips and every declared "
          "transaction utility matches its item utilities")
    print()
    print("Run an exact SPMF miner on it, for example:")
    print("  java -jar spmf.jar run EFIM %s spmf_out.txt <minUtil>" % args.out)
    print("  java -jar spmf.jar run HUI-Miner %s spmf_out.txt <minUtil>"
          % args.out)


if __name__ == "__main__":
    main()
