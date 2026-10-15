# TK-PPNHUI: reproduction package

This is the code, data and raw measurements behind the experimental section of
the TK-PPNHUI paper (parallel top-k probabilistic positive/negative high-utility
itemset mining). It lets you:

1. regenerate the paper's result tables and figures from the shipped
   measurements in under a minute, without re-running any experiment,
2. rebuild every dataset from its raw source and check it against the shipped
   files, and
3. re-run the experiments themselves (this takes many hours).

Paths below are relative to the root of the deposit. If you are reading this in
the authors' working folder rather than the deposit, see
[Working-folder paths](#working-folder-paths) at the end.

## Contents

| Path | What it is |
|---|---|
| `src/` | Java sources of the miner and the experiment harness (package `ppnhui`) |
| `src/unused/` | Exploratory sources not used by any reported result (see below) |
| `scripts/` | `generate_dataset.py`, `convert_liquor.py`, `make_stats.py`, `analyze.py`, `to_spmf.py`, `compare_spmf.py` |
| `raw/` | Raw SPMF transaction files the generated datasets are built from |
| `profit_ref/` | The three reference profit tables `generate_dataset.py` samples profit magnitudes from |
| `data/` | The nine benchmark datasets plus Pumsb (`<name>_database.txt`, `<name>_profit.txt`, `<name>_stats.json`) |
| `data_base/` | Base-case datasets (all profits positive, all probabilities 1) for Section 6.5 |
| `spmf/` | The base-case datasets converted to SPMF's utility format, as given to EFIM |
| `results/` | The four measurement directories behind the main results |
| `results_basecase/` | Measurement directories behind Section 6.5, and EFIM's outputs in `efim/` |
| `analysis/` | Tables, figures and validation report produced by `analyze.py` from `results/` |

## Measurement environment

All reported times were taken on:

* **JDK:** OpenJDK 25 (Eclipse Temurin 25.0.4.1+1 LTS), HotSpot 64-bit Server VM.
  The run logs report `JVM 25.0.4.1 (OpenJDK 64-Bit Server VM)`.
* **CPU:** Intel Core i7-12700H (14 cores / 20 hardware threads; the JVM
  reports 20 processors)
* **Memory:** 16 GB. The JVM ran with its default heap; the logs report a
  maximum heap of 4024 MB, and no `-Xmx` was given.
* **OS:** Windows 11, amd64

Thread counts 1, 2, 4 and 8 were measured for every parallel strategy.
Absolute times on other hardware will differ. Speedups and work ratios are
the quantities the paper compares.

Analysis needs Python 3 with `matplotlib`. The regeneration checks below were
done with Python 3.14.3 and matplotlib 3.11.1.

## 1. Build

From the deposit root, with JDK 25 on the `PATH`:

```bash
javac -d out src/*.java
```

On Windows PowerShell:

```powershell
javac -d out (Get-ChildItem src\*.java).FullName
```

This compiles the 14 sources in `src/` into `out/ppnhui/`. The files in
`src/unused/` are deliberately left out.

> **Do not use `bin_ppnhui/`** if you find one in the authors' working folder.
> It is a stale build from before `AlgoSEQREV` existed and does not match the
> sources that produced the results. Always build into `out/` as above.

## 2. Regenerate the tables and figures from the shipped measurements

The paper's numbers come from four measurement directories, merged in this
order. A later directory replaces an earlier one for the same
(dataset, algorithm, k, minProb, threads) cell. The command as it was run in
the working folder:

```bash
python analyze.py --results results_final_light_v2 results_fix \
    results_heavy_v2 results_kosarak_fill --stats data_new --out analysis2
```

The same command in the deposit layout:

```bash
python scripts/analyze.py --results results/results_final_light_v2 results/results_fix \
    results/results_heavy_v2 results/results_kosarak_fill --stats data --out analysis_regen
```

Running this on the shipped measurements reproduces `analysis/` exactly:
`report.txt` and all five `.tex` tables are byte-identical, and the four
figure PDFs differ only in their embedded creation timestamp.

What each directory contributes:

| Dataset | k values | Source directory |
|---|---|---|
| Chess | 10, 20, 50 | `results_final_light_v2` |
| Chess | 30, 40 | `results_fix` (replaces the earlier rows) |
| Mushroom, c20d10k, Liquor, T10I4D100K, t25i10d10k | all | `results_final_light_v2` |
| Accidents, Retail | all | `results_heavy_v2` |
| Kosarak | 25 | `results_fix` |
| Kosarak | 10, 15, 20, 30 | `results_kosarak_fill` |

### Which file is which table or figure

| Paper | Label | Source |
|---|---|---|
| Table 4 | `tab:datasets` | `analysis/tables/table_datasets.tex` |
| Table 5 | `tab:parameters` | `analysis/tables/table_parameters.tex` |
| Table 6 | `tab:scaling` | `analysis/tables/table_scaling.tex`, verbatim |
| Table 7 | `tab:order` | Assembled from three generated tables: columns 2–3 from `table_traversal.tex` ("Implied speedup" is the reciprocal of its "Time vs SEQ"), column 4 from the FJ 1-thread column of `table_scaling.tex`, column 5 from the FJ work ratio in `table_main.tex` |
| Table 8 | `tab:sharing` | Assembled: density from `table_datasets.tex`; TPB and PLM work ratios from `table_main.tex`; "Saving" is PLM ÷ TPB |
| Table 9 | `tab:main` | `analysis/tables/table_main.tex`, verbatim |
| Table 10 | `tab:basecase` | Section 6.5; see [below](#5-section-65-base-case-vs-efim) |
| Figure 4 | `fig:scaling` | `analysis/figures/fig_scaling.pdf` |
| Figure 5 | `fig:workratio` | `analysis/figures/fig_workratio.pdf` |
| Figure 6 | `fig:traversal` | `analysis/figures/fig_traversal.pdf` |

`analysis/figures/fig_speedup_vs_k.pdf` and `analysis/report.txt` (the
validation report) are produced by the same run but are not in the paper.

## 3. Re-run the experiments

Each command writes `<out>/<Dataset>/summary.csv`, `performance.csv` and,
unless `--no-patterns` is given, one `patterns_*.txt` per configuration.
Each sweep takes hours. Before any timed run the harness performs `--warmup`
discarded repetitions per configuration. Every row is checked against the
sequential baseline (`matchesSEQ` column).

Options not shown were left at the harness defaults: `--threads 1,2,4,8`,
`--minprob 0.7`, all six algorithms, and the per-dataset k values in
`Experiment.DEFAULT_K`. Where an option's original value was not recorded, the
note under the command says so.

**`results_final_light_v2`**

```bash
java -cp out ppnhui.Experiment --data-dir data --out results/results_final_light_v2 \
    --datasets Chess,Mushroom,c20d10k,t25i10d10k,T10I4D100K,Liquor --runs 5
```

* `--runs 5` is recorded. **The warm-up count was not recorded.** The line
  as written uses the harness default of 2, but it is not known whether the
  original run used 2.
* The timeout was not recorded. The harness default is 900 s, and no row
  timed out, so the value did not affect any reported number.
* This directory has no `patterns_*.txt` files.
* Its `summary.csv` lacks the trailing `mode`/`minUtil` columns, so it was
  written by an earlier revision of `Experiment.java` than the one shipped,
  before min-util mode was added. `analyze.py` reads both layouts and treats
  files without a `mode` column as top-k.

**`results_fix`** (two invocations, because `--k` applies to every dataset in
a run)

```bash
java -cp out ppnhui.Experiment --data-dir data --out results/results_fix \
    --datasets Chess --k 30,40 --runs 5
java -cp out ppnhui.Experiment --data-dir data --out results/results_fix \
    --datasets Kosarak --k 25 --runs 5
```

* `--runs 5` is recorded. **The warm-up count was not recorded**, as above.
* The timeout was not recorded and no row timed out.
* There are no pattern files, and the earlier `summary.csv` layout applies,
  as above.

**`results_heavy_v2`**

```bash
java -cp out ppnhui.Experiment --data-dir data --out results/results_heavy_v2 \
    --datasets Retail,Accidents --runs 3 --warmup 1
```

* The timeout was not recorded and no row timed out.
* The dataset order is inferred from file timestamps: Retail finished
  first. Results do not depend on it.

**`results_kosarak_fill`**

```bash
java -cp out ppnhui.Experiment --data-dir data --out results/results_kosarak_fill \
    --datasets Kosarak --k 10,15,20,30 --runs 5 --warmup 2 --timeout 1800
```

* The run log header confirms `runs=5 warmup=2 timeout=1800s threads=[1, 2, 4, 8]`.

## 4. Rebuild the datasets

`data/` already contains the exact files that were measured. Rebuilding is
only needed to check where they came from.

### Generated datasets

Eight of the nine benchmark datasets, plus Pumsb, are generated from `raw/`
with a fixed seed. From the deposit root:

```bash
for spec in Chess:chess Mushroom:mushroom c20d10k:c20d10k Accidents:accidents \
            t25i10d10k:t25i10d10k T10I4D100K:T10I4D100K Retail:retail Kosarak:kosarak \
            Pumsb:pumsb; do
  name=${spec%%:*}; raw=${spec#*:}
  python scripts/generate_dataset.py --name "$name" --input "raw/$raw.txt" \
      --profit-ref profit_ref/chess_profit.txt profit_ref/mushroom_profit.txt \
                   profit_ref/retail_profit.txt \
      --outdir data_regen --seed 42
done
```

* The output is byte-identical to `data/` for all nine.
* **`--profit-ref` must point at `profit_ref/`, not `data/`.** The
  `*_profit.txt` files in `data/` are the generated benchmark tables. The ones
  in `profit_ref/` are the original Chess, Mushroom and Retail profit tables,
  which the generator samples profit magnitudes from.
* **Some raw files are already subsets.** `raw/kosarak.txt` has 100,000
  transactions, `raw/accidents.txt` has 20,000, and `raw/pumsb.txt` has
  15,000. Starting from the full public files would give different datasets.

### Liquor

Liquor is the one dataset with real quantities, probabilities and utilities.
It is not generated: `scripts/convert_liquor.py` converts it from SPMF's
Liquor utility dataset and its uncertain version. The 30% of items with the
lowest average utility are given negative profits, and every quantity is 1.
The script expects its two inputs in `data/` and writes into `data/`, so run
it in a scratch directory:

```bash
mkdir -p liquor_regen/data
# put the two SPMF files here (names as the script expects them):
#   liquor_regen/data/liquor_11.txt                     sha256 349407998330c9297fcb36f170b5ac0b97bbfc9f745e00b6182e4e7b728db69b
#   liquor_regen/data/liquor_11frequent_uncertain.txt   sha256 808df56957d184ef998c5cca53882a9384e695182670ce89488ee790c5d507f2
cd liquor_regen && python ../scripts/convert_liquor.py
```

With these inputs the output is byte-identical to `data/liquor_database.txt`
and `data/liquor_profit.txt`. `data/liquor_stats.json` describes the shipped
files and can be re-derived with
`scripts/make_stats.py --data-dir data --name Liquor --force`.

### Pumsb

The paper reports that no strategy finishes Pumsb within 600 s at k = 10,
and that sampling it to 30% of its transactions does not help.
`data/pumsb_*` is that 30% sample: 15,000 of Pumsb's 49,046 transactions,
generated as above from `raw/pumsb.txt`.

### Base-case datasets

`data_base/` uses the same generator with `--neg-fraction 0
--prob-mode certain` (see the docstring of `generate_dataset.py`). Only
RetailBase and T10Base enter Section 6.5. ChessBase was probed but every run
timed out.

## 5. Section 6.5: base case vs EFIM

### Our miner

`results_basecase/` holds min-util-mode runs of the shipped miner on the
base-case datasets:

* `results_cold_retail_r{1,2,3}/` and `results_cold_t10_r{1,2,3}/`: three
  separate invocations of SEQ at each threshold. **The "Ours (ms)" column of
  Table 10 is the median of r1–r3**, and |H| is their `patternsFound`.
* `results_base/` (RetailBase) and `results_base_t10/` (T10Base): SEQ,
  SEQ-REV, PC and PLM at {1, 2, 4, 8} threads, 5 runs each. These are the
  source of the statement that every work ratio in fixed-threshold mode is
  exactly 1.000.
* `results_base_retail_cold/`, `results_base_t10_cold/` and
  `res_cold_<minUtil>/`: further SEQ cold-start runs, not used in Table 10.

### EFIM

EFIM is run from SPMF, which is not redistributed here. Cite it as:

> P. Fournier-Viger et al., *SPMF: an open-source data mining library*,
> version 97. Download: https://www.philippe-fournier-viger.com/spmf/index.php?link=download.php

The `spmf/*.txt` inputs were produced by `scripts/to_spmf.py`, which checks
that the conversion is lossless. EFIM was run as:

```bash
java -jar spmf.jar run EFIM <in> <out> <minUtil>
# e.g. java -jar spmf.jar run EFIM spmf/retailbase.txt efim_33000_1.txt 33000
```

Its outputs are in `results_basecase/efim/`:

* `efim_<minUtil>[_r].txt` are for RetailBase.
* `efim_t10_<minUtil>_<r>.txt` are for T10Base.
* The suffixes `_1`, `_2` and `_3` are the three invocations.

`scripts/compare_spmf.py` compares an EFIM output with one of our pattern
files itemset by itemset.

## Unused sources

`src/unused/AlgoFHN.java`, `AlgoPFHN.java`, `EUCS.java` and `TestFHN.java` are
exploratory and unused: nothing reachable from `Experiment.java` references
them, and they correspond to no reported result.

## Working-folder paths

In the authors' working folder, the deposit paths correspond to:

| Deposit | Working folder |
|---|---|
| `src/*.java` | `ppnhui/*.java` |
| `scripts/*.py` | `*.py` at the top level |
| `profit_ref/*_profit.txt` | `data/*_profit.txt` |
| `data/` | `data_new/` |
| `results/<dir>` | `<dir>` at the top level |
| `results_basecase/<dir>` | `<dir>` at the top level |
| `results_basecase/efim/` | `efim_*.txt` at the top level |
| `analysis/` | `analysis2/` |
