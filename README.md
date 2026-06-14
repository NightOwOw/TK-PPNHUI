# TK-PPNHUI: Parallel Methods for Extracting Top-K Probabilistic Positive-Negative High-Utility Itemsets

[![Language](https://img.shields.io/badge/Language-Java_8+-orange.svg)](https://www.java.com/)
[![Status](https://img.shields.io/badge/Status-Research-green.svg)](README.md)

> Research implementation comparing **five parallel strategies** for top-k high-utility itemset mining on uncertain transaction databases with **positive and negative profit** items.

---

## Problem

Given an uncertain transaction database where each item has an existential probability and a profit (which may be **negative**), find the **top-k itemsets** ranked by Expected Utility (EU) subject to a minimum Existential Probability (EP) constraint.

This extends classic probabilistic HUIM (PHUIM) by allowing negative-profit items to appear in discovered patterns — expanding the search space and making parallelization more critical.

**Key metrics:**
- **EU(X)** = Σ u(X,T) × P(X,T) — expected profit, can be negative
- **EP(X)** = 1 − Π(1 − P(X,T)) — probability X appears in at least one transaction
- **PTWU(X)** — upper bound using only positive-profit utilities (valid even for negative items)
- **PUB(X)** — tighter suffix-sum upper bound

---

## Algorithms

Five methods are implemented and compared:

| ID | Name | Strategy | Threshold Sharing |
|----|------|----------|-------------------|
| SEQ | Sequential | Single-thread DFS | Full (sequential) |
| FJ | ForkJoin Work-Stealing | `ForkJoinPool` + `RecursiveAction` | Shared (all threads) |
| TPB | ThreadPool PTWU-Balanced | Fixed pool + PTWU round-robin partition | Shared (all threads) |
| PLM | Parallel Local Mining + Merge | Fixed pool + thread-local collectors | None (merge at end) |
| PC | Producer-Consumer | `BlockingQueue` + N consumers | Shared (all threads) |

All algorithms use the same three-tier pruning: **EP → PTWU → PUB** (cheapest check first).

---

## Project Structure

```
TK-PPNHUI/
├── ppnhui/
│   ├── Transaction.java        — data model: tid, items[], qtys[], probs[]
│   ├── ProfitTable.java        — item → profit (positive or negative)
│   ├── DataReader.java         — file parser: item:qty:prob format
│   ├── UPUList.java            — UPU-List data structure + buildAll() + join()
│   ├── TopKCollector.java      — thread-safe min-heap top-k collector
│   ├── AlgoSEQ.java            — [1] Sequential baseline
│   ├── AlgoFJ.java             — [2] ForkJoin work-stealing
│   ├── AlgoTPB.java            — [3] ThreadPool PTWU-balanced
│   ├── AlgoPLM.java            — [4] Parallel local mining + merge
│   ├── AlgoPC.java             — [5] Producer-Consumer
│   └── Experiment.java         — main() — runs all algorithms, writes results
├── data/                       — dataset files (not tracked in git, see below)
├── results/                    — output directory (auto-created on run)
├── sn-article-template/        — Springer Nature LaTeX template
├── CONTEXT.md                  — full technical context for this project
└── .gitignore
```

---

## How to Run

**Requirements:** Java 8 or higher. No build tools needed.

### Step 1 — Compile

In PowerShell from the project root:

```powershell
javac -d bin_ppnhui (Get-ChildItem ppnhui\*.java).FullName
```

Or with bash:

```bash
mkdir -p bin_ppnhui && javac -d bin_ppnhui ppnhui/*.java
```

### Step 2 — Configure

Edit `ppnhui/Experiment.java` to select datasets and parameters:

```java
static final String[][] DATASETS = {
    {"Chess", "data/chess_database.txt", "data/chess_profit.txt"},
    // {"Mushroom", "data/mushroom_database.txt", "data/mushroom_profit.txt"},
};

static final int[]    K_VALUES      = {50, 60, 70, 80, 90};
static final double[] MIN_PROBS     = {0.7};
static final int[]    THREAD_COUNTS = {2, 4, 8};
static final int      RUNS          = 3;   // median of 3 runs reported
```

### Step 3 — Run

**In VS Code / IntelliJ:** Click **Run** above `main()` in `Experiment.java`.

**In PowerShell:**

```powershell
java -cp bin_ppnhui ppnhui.Experiment
```

---

## Output

### Console — two tables per (k, minProb) block

**Timing table:**
```
================================================================
 Dataset: Chess  |  3196 transactions
================================================================

  k=50  minProb=0.70
  +-----------+---------+-------------+---------+----------+
  | Algorithm | Threads |    Time(ms) | Speedup | Patterns |
  +-----------+---------+-------------+---------+----------+
  | SEQ       |       1 |     177,349 |   1.00x |       50 |
  | FJ        |       2 |      11,369 |  15.60x |       50 |
  | FJ        |       4 |       8,516 |  20.85x |       50 |
  | FJ        |       8 |       2,727 |  65.03x |       50 |
  | TPB       |       2 |     129,473 |   1.37x |       50 |
  | TPB       |       4 |     102,421 |   1.73x |       50 |
  | TPB       |       8 |      15,814 |  11.21x |       50 |
  | PLM       |       2 |     200,778 |   0.88x |       50 |
  | PLM       |       4 |     220,819 |   0.80x |       50 |
  | PLM       |       8 |     108,311 |   1.64x |       50 |
  | PC        |       2 |     115,586 |   1.53x |       50 |
  | PC        |       4 |      47,736 |   3.71x |       50 |
  | PC        |       8 |      15,661 |  11.33x |       50 |
  +-----------+---------+-------------+---------+----------+
```

**Work metrics table** (printed immediately after timing table):
```
  Work metrics (total across all threads; Work Ratio = joins / SEQ joins):
  +-----------+---------+--------------------+--------------------+-----------+
  | Algorithm | Threads |     Nodes Expanded |     Joins Attempted| Work Ratio|
  +-----------+---------+--------------------+--------------------+-----------+
  | SEQ       |       1 |      1,234,567,890 |        987,654,321 |     1.000 |
  | FJ        |       8 |         61,728,394 |         49,382,716 |     0.050 |
  | PLM       |       8 |      2,469,135,780 |      1,975,308,642 |     2.000 |
  ...
  +-----------+---------+--------------------+--------------------+-----------+
```
Work Ratio < 1/threads confirms super-linear speedup via cooperative threshold escalation (FJ).
Work Ratio > 1 shows extra work from isolated thresholds (PLM).

### Files — written to `results/<DatasetName>/`

Each dataset gets its own subfolder:

```
results/
├── Chess/
│   ├── performance.csv
│   ├── patterns_Chess_SEQ_k10_p0.70_t1.txt
│   ├── patterns_Chess_FJ_k10_p0.70_t2.txt
│   └── ...
├── Mushroom/
│   ├── performance.csv
│   └── ...
```

- `performance.csv` — timing + work metrics for every (algorithm, k, minProb, threads, run); columns include `nodesExpanded` and `joinsAttempted`
- `patterns_<dataset>_<algo>_k<k>_p<minProb>_t<threads>.txt` — actual top-k patterns with EU and EP values

---

## Data Format

**Database file** — one transaction per line, items as `item:qty:prob`:
```
56:1:1.0 8:1:1.0 18:1:1.0 12:1:1.0 45:1:1.0
49:1:1.0 16:1:1.0 18:1:1.0 24:1:0.8 6:1:1.0
```

**Profit file** — one item per line, profit may be negative:
```
1  3.5
2  5.52
5 -4.83
6  9.38
```

### Datasets

Dataset files are **not included in this repository** (total size > 600 MB). Place them in the `data/` folder:

| Dataset | Transactions | Items | Avg Len | Available |
|---------|-------------|-------|---------|-----------|
| Chess | 3,196 | 75 | 37.0 | Yes |
| Mushroom | 8,124 | 119 | 23.0 | Yes |
| Retail | 88,162 | 16,470 | 10.3 | Yes |
| Liquor | 52,131 | 4,026 | 7.87 | Yes (converted) |

The Liquor dataset was derived from the [SPMF public datasets](https://www.philippe-fournier-viger.com/spmf/index.php?link=datasets.php) by merging `liquor_11.txt` (HUIM utilities) and `liquor_11frequent_uncertain.txt` (existential probabilities). Run `python convert_liquor.py` from the project root to regenerate it.

---

## Key Findings (Chess dataset, minProb=0.70)

- **FJ** achieves super-linear speedup (up to 65× at 8 threads) due to *cooperative threshold escalation*: parallel threads collectively discover high-EU patterns faster, raising the pruning threshold much higher than sequential DFS can alone.
- **PC** and **TPB** converge around 11× at 8 threads — dynamic vs. static task scheduling has diminishing returns at the coarse 1-itemset granularity.
- **PLM** is *slower than sequential* at 2–4 threads — each thread's local collector cannot benefit from other threads' discoveries, so the threshold rises slowly and far more nodes are explored. This is especially severe for PPNHUI because negative-profit items expand the search space.
- **TPB** improves moderately — PTWU-based static partitioning cannot react to runtime imbalance on Chess's dense structure.

---

## Authors

Research implementation for thesis project — HCMUT, 2024–2025.
Target journal: Springer Nature (manuscript in preparation).
