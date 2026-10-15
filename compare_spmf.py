#!/usr/bin/env python3
"""
compare_spmf.py - compare the itemsets our miner returns on the base case with
those an SPMF exact miner returns on the identical data.

WHY THIS IS THE COMPARISON THAT MATTERS
    A runtime table says nothing unless the two programs solved the same
    problem. This script checks that they did: same itemsets, same utilities,
    no pattern found by one and missed by the other. Agreement with an
    independently written, published exact miner is a far stronger correctness
    statement than agreement with our own sequential baseline, because the two
    implementations share no code, no data structure and no author.

    A disagreement is equally informative, and the script reports which side is
    missing what rather than only that the counts differ, because "we returned
    5 fewer patterns" and "we returned 5 different patterns" call for different
    investigations.

INPUTS
    --spmf   the output file of an SPMF miner, whose lines look like
                 1 2 3 #UTIL: 12
    --ours   a pattern file written by Experiment.java, whose lines look like
                 1      46280.0000       1.000000  [39]

USAGE
    python compare_spmf.py --spmf spmf_out.txt \\
        --ours results_base/Base/patterns_Base_SEQ_mu100000_p0.70_t1.txt
"""

import argparse
import re
import sys


def read_spmf(path):
    """{frozenset(items): utility} from an SPMF miner's output."""
    out = {}
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            if "#UTIL:" not in line:
                sys.exit("%s line %d: no '#UTIL:' field in %r"
                         % (path, lineno, line))
            left, right = line.split("#UTIL:", 1)
            try:
                items = frozenset(int(x) for x in left.split())
                util = float(right.split("#")[0].strip())
            except ValueError:
                sys.exit("%s line %d: cannot parse %r" % (path, lineno, line))
            if not items:
                continue
            out[items] = util
    return out


def read_ours(path):
    """{frozenset(items): EU} from a pattern file written by Experiment.java."""
    out = {}
    pat = re.compile(r"^\s*\d+\s+(-?[\d.]+)\s+([\d.]+)\s+\[([^\]]*)\]\s*$")
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            if line.startswith("#") or not line.strip():
                continue
            m = pat.match(line)
            if not m:
                sys.exit("%s line %d: cannot parse %r"
                         % (path, lineno, line.rstrip()))
            eu = float(m.group(1))
            body = m.group(3).strip()
            items = frozenset(int(x) for x in body.split(",")) if body else frozenset()
            if items:
                out[items] = eu
    return out


def fmt(s):
    return "{" + ", ".join(str(x) for x in sorted(s)) + "}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spmf", required=True)
    ap.add_argument("--ours", required=True)
    ap.add_argument("--tol", type=float, default=1e-6,
                    help="absolute tolerance when comparing utilities")
    ap.add_argument("--show", type=int, default=10,
                    help="how many differing itemsets to list")
    args = ap.parse_args()

    spmf = read_spmf(args.spmf)
    ours = read_ours(args.ours)

    only_spmf = set(spmf) - set(ours)
    only_ours = set(ours) - set(spmf)
    shared = set(spmf) & set(ours)

    mismatched = [(X, spmf[X], ours[X]) for X in shared
                  if abs(spmf[X] - ours[X]) > args.tol]
    worst = max((abs(a - b) for _, a, b in mismatched), default=0.0)

    print("Comparison against an independent exact miner")
    print("  SPMF returned      %d itemsets" % len(spmf))
    print("  ours returned      %d itemsets" % len(ours))
    print("  in both            %d" % len(shared))
    print()

    ok_sets = not only_spmf and not only_ours
    ok_utils = not mismatched

    print("  same itemsets      %s" % ("PASS" if ok_sets else
          "FAIL   only SPMF: %d, only ours: %d" % (len(only_spmf), len(only_ours))))
    print("  same utilities     %s" % ("PASS" if ok_utils else
          "FAIL   %d differ, worst gap %.6g" % (len(mismatched), worst)))

    if only_spmf:
        print("\n  found by SPMF but not by us:")
        for X in sorted(only_spmf, key=lambda s: -spmf[s])[:args.show]:
            print("      %-30s util %.4f" % (fmt(X), spmf[X]))
        if len(only_spmf) > args.show:
            print("      ... and %d more" % (len(only_spmf) - args.show))

    if only_ours:
        print("\n  found by us but not by SPMF:")
        for X in sorted(only_ours, key=lambda s: -ours[s])[:args.show]:
            print("      %-30s util %.4f" % (fmt(X), ours[X]))
        if len(only_ours) > args.show:
            print("      ... and %d more" % (len(only_ours) - args.show))

    if mismatched:
        print("\n  utilities that differ:")
        for X, a, b in sorted(mismatched, key=lambda t: -abs(t[1] - t[2]))[:args.show]:
            print("      %-30s SPMF %.4f   ours %.4f" % (fmt(X), a, b))
        if len(mismatched) > args.show:
            print("      ... and %d more" % (len(mismatched) - args.show))

    print()
    if ok_sets and ok_utils:
        print("IDENTICAL: the two implementations agree on every itemset and "
              "every utility.")
        return 0
    print("DIFFERENT: see above. A mismatch here means the two programs did "
          "not solve the same problem, so any runtime comparison between them "
          "is meaningless until it is resolved.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
