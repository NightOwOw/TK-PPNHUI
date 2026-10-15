"""
extract_raw.py — Recover the raw SPMF transaction files from the converted ones.

The converted format "item:qty:prob" preserves the original item list of every
transaction, so the raw file can be reconstructed exactly by dropping the
quantity and probability fields. No download needed for datasets that have
already been converted.

Run from the project root:

    python extract_raw.py

Reads  data/<name>_database.txt
Writes raw/<name>.txt
"""

import glob
import os

DATA_DIR = "data"
RAW_DIR = "raw"


def extract(db_path, out_path):
    n_tx = 0
    n_items = 0
    bad = 0
    with open(db_path, encoding="utf-8") as fin, \
         open(out_path, "w", encoding="utf-8") as fout:
        for line in fin:
            toks = line.split()
            if not toks:
                continue
            items = []
            for tok in toks:
                parts = tok.split(":")
                if len(parts) != 3:
                    bad += 1
                    continue
                items.append(parts[0])
            if items:
                fout.write(" ".join(items) + "\n")
                n_tx += 1
                n_items += len(items)
    return n_tx, n_items, bad


def main():
    if not os.path.isdir(DATA_DIR):
        raise SystemExit("No %s/ directory here. Run from the project root."
                         % DATA_DIR)
    os.makedirs(RAW_DIR, exist_ok=True)

    paths = sorted(glob.glob(os.path.join(DATA_DIR, "*_database.txt")))
    if not paths:
        raise SystemExit("No *_database.txt files in %s/" % DATA_DIR)

    for db_path in paths:
        name = os.path.basename(db_path)[:-len("_database.txt")]
        out_path = os.path.join(RAW_DIR, "%s.txt" % name)
        n_tx, n_items, bad = extract(db_path, out_path)
        note = "   (%d malformed tokens skipped)" % bad if bad else ""
        print("%-10s %7d transactions, %9d item occurrences -> %s%s"
              % (name, n_tx, n_items, out_path, note))


if __name__ == "__main__":
    main()
