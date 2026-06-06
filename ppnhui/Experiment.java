package ppnhui;

import java.io.*;
import java.util.*;

/**
 * Main experiment runner for TK-PPNHUI parallel methods comparison.
 *
 * HOW TO RUN: open this file in VS Code/IntelliJ and click "Run" on main().
 * No command-line arguments needed. All paths are relative to the project root.
 *
 * OUTPUT FILES (written to results/ folder):
 *   performance.csv   — timing + work metrics for every config
 *   patterns_<dataset>_<algo>_k<k>_p<minProb>_t<threads>.txt — top-k patterns
 */
public class Experiment {

    // -------------------------------------------------------------------------
    // Configuration — edit here to change what is tested
    // -------------------------------------------------------------------------

    static final String[][] DATASETS = {
        {"Chess",    "data/chess_database.txt",    "data/chess_profit.txt"},
        //{"Mushroom", "data/mushroom_database.txt", "data/mushroom_profit.txt"},
        //{"Retail",   "data/retail_database.txt",   "data/retail_profit.txt"},
        //{"Kosarak",  "data/kosarak_database.txt",  "data/kosarak_profit.txt"},
    };

    static final int[]    K_VALUES      = {50, 60, 70, 80, 90};
    static final double[] MIN_PROBS     = {0.7};
    static final int[]    THREAD_COUNTS = {2, 4, 8};
    static final int      RUNS          = 3;
    static final String   OUT_DIR       = "results";

    // -------------------------------------------------------------------------
    // Row: one result entry for the summary table
    // -------------------------------------------------------------------------
    static class Row {
        final String algo;
        final int    threads;
        final long   timeMs;
        final int    patterns;
        final long   nodes;
        final long   joins;

        Row(String algo, int threads, long timeMs, int patterns, long nodes, long joins) {
            this.algo     = algo;
            this.threads  = threads;
            this.timeMs   = timeMs;
            this.patterns = patterns;
            this.nodes    = nodes;
            this.joins    = joins;
        }
    }

    // -------------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        new File(OUT_DIR).mkdirs();

        String perfPath = OUT_DIR + "/performance.csv";
        try (PrintWriter perf = new PrintWriter(new FileWriter(perfPath))) {

            perf.println("dataset,algorithm,k,minProb,threads,run," +
                         "timeTotal_ms,timePhase1_ms,timePhase2_ms,timePhase3_ms," +
                         "patternsFound,nodesExpanded,joinsAttempted");

            for (String[] ds : DATASETS) {
                String dsName = ds[0];
                String dbPath = ds[1];
                String ptPath = ds[2];

                List<Transaction> db;
                ProfitTable pt;
                try {
                    db = DataReader.readDatabase(dbPath);
                    pt = DataReader.readProfitTable(ptPath);
                } catch (Exception e) {
                    System.out.println("[SKIP] Cannot load " + dsName + ": " + e.getMessage());
                    continue;
                }

                printDatasetHeader(dsName, db.size());

                for (double minProb : MIN_PROBS) {
                    for (int k : K_VALUES) {
                        List<Row> rows = new ArrayList<>();

                        Row seqRow = collectSEQ(db, pt, k, minProb, dsName, perf);
                        rows.add(seqRow);

                        for (int threads : THREAD_COUNTS)
                            rows.add(collectFJ (db, pt, k, minProb, threads, dsName, perf));
                        for (int threads : THREAD_COUNTS)
                            rows.add(collectTPB(db, pt, k, minProb, threads, dsName, perf));
                        for (int threads : THREAD_COUNTS)
                            rows.add(collectPLM(db, pt, k, minProb, threads, dsName, perf));
                        for (int threads : THREAD_COUNTS)
                            rows.add(collectPC (db, pt, k, minProb, threads, dsName, perf));

                        printTable(k, minProb, rows, seqRow.timeMs);
                        printWorkTable(rows, seqRow.joins);
                    }
                }
            }
        }
        System.out.println("\nDone. Performance written to: " + perfPath);
    }

    // -------------------------------------------------------------------------
    // Table printing
    // -------------------------------------------------------------------------

    private static void printDatasetHeader(String name, int txCount) {
        String line = " Dataset: " + name + "  |  " + txCount + " transactions ";
        int width = Math.max(line.length() + 4, 66);
        String bar = "=".repeat(width);
        System.out.println(bar);
        System.out.println(line);
        System.out.println(bar);
    }

    private static void printTable(int k, double minProb, List<Row> rows, long seqTime) {
        String sep = "  +-----------+---------+-------------+---------+----------+";
        String hdr = "  | Algorithm | Threads |    Time(ms) | Speedup | Patterns |";

        System.out.printf("%n  k=%-3d  minProb=%.2f%n", k, minProb);
        System.out.println(sep);
        System.out.println(hdr);
        System.out.println(sep);

        for (Row r : rows) {
            double speedup = (r.timeMs > 0) ? (double) seqTime / r.timeMs : 0;
            String speedupStr = String.format("%.2fx", speedup);
            System.out.printf("  | %-9s | %7d | %,11d | %7s | %8d |%n",
                r.algo, r.threads, r.timeMs, speedupStr, r.patterns);
        }

        System.out.println(sep);
    }

    private static void printWorkTable(List<Row> rows, long seqJoins) {
        String sep = "  +-----------+---------+--------------------+--------------------+-----------+";
        String hdr = "  | Algorithm | Threads |     Nodes Expanded |     Joins Attempted| Work Ratio|";

        System.out.println();
        System.out.println("  Work metrics (total across all threads; Work Ratio = joins / SEQ joins):");
        System.out.println(sep);
        System.out.println(hdr);
        System.out.println(sep);

        for (Row r : rows) {
            double ratio = (seqJoins > 0) ? (double) r.joins / seqJoins : 0;
            System.out.printf("  | %-9s | %7d | %,18d | %,18d | %9.3f |%n",
                r.algo, r.threads, r.nodes, r.joins, ratio);
        }

        System.out.println(sep);
    }

    // -------------------------------------------------------------------------
    // Per-algorithm collectors — return Row, write CSV
    // -------------------------------------------------------------------------

    private static Row collectSEQ(List<Transaction> db, ProfitTable pt,
                                   int k, double minProb,
                                   String dsName, PrintWriter perf) {
        long[] times = new long[RUNS];
        AlgoSEQ.Result last = AlgoSEQ.mine(db, pt, k, minProb);
        times[0] = last.timeTotal;
        for (int r = 1; r < RUNS; r++) {
            last = AlgoSEQ.mine(db, pt, k, minProb);
            times[r] = last.timeTotal;
        }
        AlgoSEQ.Result res = last;
        long med = median(times);
        for (int r = 0; r < RUNS; r++) {
            perf.printf("%s,SEQ,%d,%.2f,1,%d,%d,%d,%d,%d,%d,%d,%d%n",
                dsName, k, minProb, r+1,
                times[r], res.timePhase1, res.timePhase2, res.timePhase3,
                res.patterns.size(), res.nodesExpanded, res.joinsAttempted);
        }
        writePatterns(res.patterns, dsName, "SEQ", k, minProb, 1);
        return new Row("SEQ", 1, med, res.patterns.size(), res.nodesExpanded, res.joinsAttempted);
    }

    private static Row collectFJ(List<Transaction> db, ProfitTable pt,
                                  int k, double minProb, int threads,
                                  String dsName, PrintWriter perf) {
        long[] times = new long[RUNS];
        AlgoFJ.Result last = AlgoFJ.mine(db, pt, k, minProb, threads);
        times[0] = last.timeTotal;
        for (int r = 1; r < RUNS; r++) {
            last = AlgoFJ.mine(db, pt, k, minProb, threads);
            times[r] = last.timeTotal;
        }
        AlgoFJ.Result res = last;
        long med = median(times);
        for (int r = 0; r < RUNS; r++) {
            perf.printf("%s,FJ,%d,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                dsName, k, minProb, threads, r+1,
                times[r], res.timePhase1, res.timePhase2, res.timePhase3,
                res.patterns.size(), res.nodesExpanded, res.joinsAttempted);
        }
        writePatterns(res.patterns, dsName, "FJ", k, minProb, threads);
        return new Row("FJ", threads, med, res.patterns.size(), res.nodesExpanded, res.joinsAttempted);
    }

    private static Row collectTPB(List<Transaction> db, ProfitTable pt,
                                   int k, double minProb, int threads,
                                   String dsName, PrintWriter perf) {
        long[] times = new long[RUNS];
        AlgoTPB.Result last = AlgoTPB.mine(db, pt, k, minProb, threads);
        times[0] = last.timeTotal;
        for (int r = 1; r < RUNS; r++) {
            last = AlgoTPB.mine(db, pt, k, minProb, threads);
            times[r] = last.timeTotal;
        }
        AlgoTPB.Result res = last;
        long med = median(times);
        for (int r = 0; r < RUNS; r++) {
            perf.printf("%s,TPB,%d,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                dsName, k, minProb, threads, r+1,
                times[r], res.timePhase1, res.timePhase2, res.timePhase3,
                res.patterns.size(), res.nodesExpanded, res.joinsAttempted);
        }
        writePatterns(res.patterns, dsName, "TPB", k, minProb, threads);
        return new Row("TPB", threads, med, res.patterns.size(), res.nodesExpanded, res.joinsAttempted);
    }

    private static Row collectPLM(List<Transaction> db, ProfitTable pt,
                                   int k, double minProb, int threads,
                                   String dsName, PrintWriter perf) {
        long[] times = new long[RUNS];
        AlgoPLM.Result last = AlgoPLM.mine(db, pt, k, minProb, threads);
        times[0] = last.timeTotal;
        for (int r = 1; r < RUNS; r++) {
            last = AlgoPLM.mine(db, pt, k, minProb, threads);
            times[r] = last.timeTotal;
        }
        AlgoPLM.Result res = last;
        long med = median(times);
        for (int r = 0; r < RUNS; r++) {
            perf.printf("%s,PLM,%d,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                dsName, k, minProb, threads, r+1,
                times[r], res.timePhase1, res.timePhase2, res.timePhase3,
                res.patterns.size(), res.nodesExpanded, res.joinsAttempted);
        }
        writePatterns(res.patterns, dsName, "PLM", k, minProb, threads);
        return new Row("PLM", threads, med, res.patterns.size(), res.nodesExpanded, res.joinsAttempted);
    }

    private static Row collectPC(List<Transaction> db, ProfitTable pt,
                                  int k, double minProb, int threads,
                                  String dsName, PrintWriter perf) {
        long[] times = new long[RUNS];
        AlgoPC.Result last = AlgoPC.mine(db, pt, k, minProb, threads);
        times[0] = last.timeTotal;
        for (int r = 1; r < RUNS; r++) {
            last = AlgoPC.mine(db, pt, k, minProb, threads);
            times[r] = last.timeTotal;
        }
        AlgoPC.Result res = last;
        long med = median(times);
        for (int r = 0; r < RUNS; r++) {
            perf.printf("%s,PC,%d,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                dsName, k, minProb, threads, r+1,
                times[r], res.timePhase1, res.timePhase2, res.timePhase3,
                res.patterns.size(), res.nodesExpanded, res.joinsAttempted);
        }
        writePatterns(res.patterns, dsName, "PC", k, minProb, threads);
        return new Row("PC", threads, med, res.patterns.size(), res.nodesExpanded, res.joinsAttempted);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static void writePatterns(List<TopKCollector.Pattern> patterns,
                                      String ds, String algo, int k, double minProb, int threads) {
        String fname = String.format("%s/patterns_%s_%s_k%d_p%.2f_t%d.txt",
                                     OUT_DIR, ds, algo, k, minProb, threads);
        try (PrintWriter pw = new PrintWriter(new FileWriter(fname))) {
            pw.printf("# Dataset=%s  Algo=%s  k=%d  minProb=%.2f  threads=%d%n",
                      ds, algo, k, minProb, threads);
            pw.printf("# Rank  EU              EP        Itemset%n");
            int rank = 1;
            for (TopKCollector.Pattern p : patterns) {
                pw.printf("%-5d  %-15.4f  %-8.6f  %s%n",
                          rank++, p.eu, p.ep, Arrays.toString(p.itemset));
            }
        } catch (IOException e) {
            System.err.println("Warning: could not write " + fname);
        }
    }

    private static long median(long[] arr) {
        long[] sorted = arr.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
