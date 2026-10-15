# TK-PPNHUI: reproduction package

This is the code, data and raw measurements behind the experimental section of
the TK-PPNHUI paper (parallel top-k probabilistic positive/negative high-utility
itemset mining). It lets you:

1. regenerate the paper's result tables and figures from the shipped
   measurements in under a minute, without re-running any experiment, and
2. re-run the experiments themselves (this takes many hours).

Paths below are relative to the root of the deposit. If you are reading this in
the authors' working folder rather than the deposit, see
[Working-folder paths](#working-folder-paths) at the end.

## Contents

| Path | What it is |
|---|---|
| `src/` | Java sources of the miner and the experiment harness (package `ppnhui`) |
| `src/unused/` | Exploratory sources not used by any reported result (see below) |
| `scripts/` | `generate_dataset.py`, `make_stats.py`, `analyze.py`, `to_spmf.py`, `compare_spmf.py` |
| `raw/` | Raw SPMF transaction files the benchmark datasets are built from |
| `data/` | The nine benchmark datasets (`<name>_database.txt`, `<name>_profit.txt`, `<name>_stats.json`) |
| `data_base/` | Base-case datasets (all profits positive, all probabilities 1) for Section 6.5 |
| `results/` | The four measurement directories behind the main results |
| `results_basecase/` | Measurement directories behind Section 6.5 (base case vs EFIM) |
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

Analysis needs Python 3 with `matplotlib`. The regeneration check below was
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

| Paper | LaTeX label | File under `analysis/` |
|---|---|---|
| Table [CONFIRM] | `tab:datasets` | `tables/table_datasets.tex` |
| Table [CONFIRM] | `tab:parameters` | `tables/table_parameters.tex` |
| Table [CONFIRM] | `tab:main` | `tables/table_main.tex` |
| Table [CONFIRM] | `tab:scaling` | `tables/table_scaling.tex` |
| Table [CONFIRM] | `tab:traversal` | `tables/table_traversal.tex` |
| Figure [CONFIRM] | – | `figures/fig_scaling.pdf` (speedup vs threads) |
| Figure [CONFIRM] | – | `figures/fig_workratio.pdf` (work ratio vs threads) |
| Figure [CONFIRM] | – | `figures/fig_speedup_vs_k.pdf` (speedup at 8 threads vs k) |
| Figure [CONFIRM] | – | `figures/fig_traversal.pdf` (SEQ-REV vs SEQ) |
| – | – | `report.txt` (validation report; not in the paper) |

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
only needed to check the generation protocol. Eight of the nine datasets were
generated from `raw/` with fixed seeds, as run in the working folder:

```bash
for spec in Chess:chess Mushroom:mushroom c20d10k:c20d10k Accidents:accidents \
            t25i10d10k:t25i10d10k T10I4D100K:T10I4D100K Retail:retail Kosarak:kosarak; do
  name=${spec%%:*}; raw=${spec#*:}
  python generate_dataset.py --name "$name" --input "raw/$raw.txt" \
      --profit-ref data/chess_profit.txt data/mushroom_profit.txt data/retail_profit.txt \
      --outdir data_new --seed 42
done
```

* `--profit-ref` points at the **original** Chess, Mushroom and Retail
  profit tables, from the working folder's `data/` directory. These are not
  the files of the same name in this deposit's `data/`, which are the
  regenerated benchmark versions. The generator only uses the reference
  files for the empirical distribution of profit magnitudes.
* **`raw/kosarak.txt` and `raw/accidents.txt` are already subsets:** the
  100,000- and 20,000-transaction subsets respectively, not the full SPMF
  files.
  The deposit ships them for this reason. Starting from the full public files
  would give different datasets.
* A regeneration on 15 Oct 2026 was byte-identical to `data/` for all eight
  datasets.
* **Liquor is not generated by this script.** It was converted earlier from
  SPMF's `liquor_11` utility and uncertain versions, with the 30% of items of
  lowest total utility made negative. It is shipped as measured. Its
  `liquor_stats.json` describes the shipped files and can be re-derived from
  them with `scripts/make_stats.py --data-dir data --name Liquor --force`.

The base-case datasets in `data_base/` use the same generator with
`--neg-fraction 0 --prob-mode certain` (see the docstring of
`generate_dataset.py`). Only RetailBase and T10Base enter Section 6.5;
ChessBase was probed but every run timed out.

## 5. Section 6.5: base case vs EFIM

`results_basecase/` holds the min-util-mode runs of the shipped miner on the
base-case datasets:

* `results_base/` (RetailBase) and `results_base_t10/` (T10Base): SEQ,
  SEQ-REV, PC and PLM at five minUtil thresholds each, 5 runs.
* `results_base_retail_cold/`, `results_base_t10_cold/`,
  `results_cold_retail_r{1,2,3}/`, `results_cold_t10_r{1,2,3}/` and
  `res_cold_<minUtil>/`: SEQ-only cold-start runs (1 or 3 repetitions).

The independent reference is EFIM from the SPMF library. `scripts/to_spmf.py`
converts a base-case dataset into SPMF's utility format and checks that the
conversion is lossless. `scripts/compare_spmf.py` checks that EFIM and our
miner return the same itemsets with the same utilities.

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
| `data/` | `data_new/` |
| `results/<dir>` | `<dir>` at the top level |
| `results_basecase/<dir>` | `<dir>` at the top level |
| `analysis/` | `analysis2/` |
