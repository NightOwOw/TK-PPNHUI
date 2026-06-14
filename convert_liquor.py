"""
Convert liquor SPMF datasets to TK-PPNHUI format.

Inputs:
  data/liquor_11.txt               - HUIM format: "items : total_util : item_utils"
  data/liquor_11frequent_uncertain.txt - uncertain format: "tid item:prob ..."

Outputs:
  data/liquor_database.txt         - "item:qty:prob item:qty:prob ..." (qty=1 always)
  data/liquor_profit.txt           - "item profit" (some items get negative profit)

Negative profit assignment:
  Items whose average per-transaction utility is below the global median
  get a NEGATIVE profit (negated). This gives ~50% negative-profit items,
  which stresses the PPNHUI search space and tests correctness of PPNHUI vs PHUIM.
  Change NEG_FRACTION below to adjust.
"""

import statistics

HUIM_FILE      = "data/liquor_11.txt"
UNCERTAIN_FILE = "data/liquor_11frequent_uncertain.txt"
DB_OUT         = "data/liquor_database.txt"
PROFIT_OUT     = "data/liquor_profit.txt"

# Fraction of items (by ascending avg utility) that get NEGATIVE profit
NEG_FRACTION = 0.30   # 30% lowest-utility items become negative

# ── parse both files ────────────────────────────────────────────────────────

print("Reading files...")
with open(HUIM_FILE, encoding="utf-8") as f:
    huim_lines = f.readlines()

with open(UNCERTAIN_FILE, encoding="utf-8") as f:
    unc_lines = f.readlines()

assert len(huim_lines) == len(unc_lines), "Transaction count mismatch!"

# ── first pass: accumulate per-item utilities ────────────────────────────────

item_utils = {}   # item_id -> list of per-transaction utilities

for line in huim_lines:
    parts = line.strip().split(":")
    items = parts[0].split()
    utils = parts[2].split()
    for item, util in zip(items, utils):
        item_utils.setdefault(item, []).append(float(util))

# average utility per item
item_avg_util = {item: statistics.mean(vals) for item, vals in item_utils.items()}

# ── decide which items get negative profit ──────────────────────────────────

sorted_items = sorted(item_avg_util.items(), key=lambda x: x[1])
cutoff = int(len(sorted_items) * NEG_FRACTION)
negative_items = {item for item, _ in sorted_items[:cutoff]}

print(f"Total items: {len(item_avg_util)}")
print(f"Negative-profit items: {len(negative_items)} ({NEG_FRACTION*100:.0f}%)")

# ── write profit table ───────────────────────────────────────────────────────

with open(PROFIT_OUT, "w", encoding="utf-8") as pf:
    for item, avg_util in item_avg_util.items():
        profit = -round(avg_util, 2) if item in negative_items else round(avg_util, 2)
        pf.write(f"{item} {profit}\n")

print(f"Written: {PROFIT_OUT}")

# ── second pass: write database ──────────────────────────────────────────────

with open(DB_OUT, "w", encoding="utf-8") as db:
    for huim_line, unc_line in zip(huim_lines, unc_lines):
        # parse probabilities from uncertain file
        unc_tokens = unc_line.strip().split()
        prob_map = {}
        for token in unc_tokens[1:]:          # skip leading tid
            item_id, prob = token.split(":")
            prob_map[item_id] = prob

        # parse items from HUIM file (order matches uncertain file)
        items = huim_line.strip().split(":")[0].split()

        db_tokens = []
        for item in items:
            prob = prob_map.get(item, "0.1")  # fallback (should never happen)
            db_tokens.append(f"{item}:1:{prob}")

        db.write(" ".join(db_tokens) + "\n")

print(f"Written: {DB_OUT}")
print("Done.")
