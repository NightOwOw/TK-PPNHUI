package ppnhui;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.*;
import java.util.concurrent.*;

/**
 * Experiment runner for the TK-PPNHUI parallel strategies.
 *
 * Everything is configurable from the command line so that a full sweep can be
 * driven from a script instead of by editing this file.
 *
 *   java -cp out ppnhui.Experiment
 *   java -cp out ppnhui.Experiment --datasets Chess,Mushroom --k 10,20,30 \
 *        --threads 1,2,4,8 --runs 10 --warmup 2 --timeout 600
 *
 * OPTIONS
 *   --data-dir DIR     where <name>_database.txt and <name>_profit.txt live
 *   --out DIR          output directory                       (default results)
 *   --datasets LIST    comma-separated dataset names          (default: all known)
 *   --k LIST           comma-separated k values; if omitted, the per-dataset
 *                      default from DEFAULT_K is used
 *   --minutil LIST     comma-separated minimum-utility thresholds. Switches the
 *                      run from top-k mining to classical fixed-threshold
 *                      mining, in which every itemset at or above the threshold
 *                      is returned. Mutually exclusive with --k.
 *   --minprob LIST     comma-separated minProb values         (default 0.7)
 *   --threads LIST     comma-separated thread counts          (default 1,2,4,8)
 *   --runs N           measured repetitions per configuration (default 10)
 *   --warmup N         discarded warm-up repetitions          (default 2)
 *   --timeout SEC      per-run wall-clock limit, 0 disables   (default 900)
 *   --algos LIST       subset of SEQ,SEQREV,FJ,TPB,PLM,PC     (default all)
 *   --no-patterns      do not write the top-k pattern files
 *
 * WHY THE WARM-UP MATTERS
 *   The JVM interprets bytecode before the JIT compiles the hot paths, so the
 *   first repetition of a configuration can be several times slower than the
 *   steady state. Averaging it in inflates both the mean and the standard
 *   deviation. Warm-up repetitions are executed and thrown away.
 *
 * WHY SEQ-REV IS INCLUDED
 *   SEQ-REV is SEQ with the top-level prefix loop reversed, which is the order a
 *   single ForkJoin worker actually uses. It separates the benefit of the
 *   traversal order from the benefit of the parallel machinery; see the comment
 *   in AlgoSEQREV.java. It is sequential, so it is measured at one thread only.
 *
 * WHY 1 THREAD IS INCLUDED
 *   Running each parallel strategy at one thread separates the cost of the
 *   parallel machinery from the benefit of parallelism: the gap between SEQ
 *   and a strategy at one thread is pure overhead.
 *
 * THE TWO MINING MODES
 *   By default the harness sweeps k and the miner returns the k best itemsets.
 *   With --minutil it sweeps a fixed utility threshold instead and the miner
 *   returns everything above it, which is the classical high-utility
 *   formulation. The mining core is the same in both: it prunes against
 *   whatever number the collector publishes, and that number simply stops
 *   moving in the second mode. Combined with a dataset whose profits are all
 *   positive and whose probabilities are all 1, this lets the same
 *   implementation be measured on the base-case problem that classical
 *   high-utility miners solve, so that the cost of the more general model can
 *   be measured rather than asserted.
 *
 *   The results files gain a mode column and a minUtil column at the end, and
 *   the k column holds 0 in min-util mode.
 *
 * THE matchesSEQ COLUMN
 *   "reference"  this row is the SEQ run the others are checked against
 *   "yes" / "NO" the top-k signature does or does not match SEQ
 *   "unchecked"  no usable SEQ reference for this (dataset, k, minProb) cell,
 *                either because SEQ was excluded by --algos or because it timed
 *                out. Absence of a reference must not be reported as a mismatch.
 */
public class Experiment {

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    /** The nine datasets of the benchmark, densest first. */
    static final String[] ALL_DATASETS = {
        "Chess", "Mushroom", "c20d10k", "Accidents",
        "t25i10d10k", "T10I4D100K", "Liquor", "Retail", "Kosarak"
    };

    /**
     * Per-dataset k ranges, five points each.
     *
     * Each range was chosen from a sequential probe so that the running time
     * grows by at least roughly a factor of two from the smallest to the
     * largest k. That matters because on the sparse synthetic datasets a range
     * such as 10-30 leaves the time almost flat, which would mean the reported
     * speedups describe the fixed loading cost rather than the mining work.
     * Dense datasets need smaller k than sparse ones to stay tractable.
     */
    static final Map<String, int[]> DEFAULT_K = new LinkedHashMap<>();
    static {
        // dense
        DEFAULT_K.put("Chess",       new int[]{10, 20, 30, 40, 50});
        DEFAULT_K.put("Mushroom",    new int[]{30, 40, 50, 60, 70});
        DEFAULT_K.put("c20d10k",     new int[]{10, 20, 30, 40, 50});
        DEFAULT_K.put("Accidents",   new int[]{10, 15, 20, 25, 30});
        // sparse
        DEFAULT_K.put("Kosarak",     new int[]{10, 15, 20, 25, 30});
        DEFAULT_K.put("Liquor",      new int[]{500, 600, 700, 800, 900});
        DEFAULT_K.put("Retail",      new int[]{500, 600, 700, 800, 900});
        DEFAULT_K.put("T10I4D100K",  new int[]{500, 875, 1250, 1625, 2000});
        DEFAULT_K.put("t25i10d10k",  new int[]{500, 875, 1250, 1625, 2000});
        // included but not expected to complete; see the timeout note
        DEFAULT_K.put("Pumsb",       new int[]{10, 20, 30, 40, 50});
    }

    static String   dataDir      = "data";
    static String   outDir       = "results";
    static String[] datasets     = ALL_DATASETS;
    static int[]    kOverride    = null;
    static double[] minUtils     = null;   // non-null switches to min-util mode
    static double[] minProbs     = {0.7};
    static int[]    threadCounts = {1, 2, 4, 8};
    static int      runs         = 10;
    static int      warmup       = 2;
    static int      timeoutSec   = 900;
    static String[] algos        = {"SEQ", "SEQREV", "FJ", "TPB", "PLM", "PC"};
    static boolean  writePatterns = true;

    // -------------------------------------------------------------------------
    // A single measured execution
    // -------------------------------------------------------------------------

    static class Run {
        long timeTotal, timeP1, timeP2, timeP3;
        long nodes, joins;
        long peakHeapMB;
        List<TopKCollector.Pattern> patterns;
    }

    /** Adapter so the five algorithms can be driven through one code path. */
    interface Miner {
        Run mine(List<Transaction> db, ProfitTable pt, MiningTarget target,
                 double minProb, int threads);
    }

    /** True for baselines that ignore the thread count. */
    static boolean isSequential(String algo) {
        return algo.equals("SEQ") || algo.equals("SEQREV");
    }

    static Miner minerFor(String algo) {
        switch (algo) {
            case "SEQ": return (db, pt, tgt, p, t) -> {
                AlgoSEQ.Result r = AlgoSEQ.mine(db, pt, tgt, p);
                return pack(r.patterns, r.timeTotal, r.timePhase1, r.timePhase2,
                            r.timePhase3, r.nodesExpanded, r.joinsAttempted);
            };
            case "SEQREV": return (db, pt, tgt, p, t) -> {
                AlgoSEQREV.Result r = AlgoSEQREV.mine(db, pt, tgt, p);
                return pack(r.patterns, r.timeTotal, r.timePhase1, r.timePhase2,
                            r.timePhase3, r.nodesExpanded, r.joinsAttempted);
            };
            case "FJ": return (db, pt, tgt, p, t) -> {
                AlgoFJ.Result r = AlgoFJ.mine(db, pt, tgt, p, t);
                return pack(r.patterns, r.timeTotal, r.timePhase1, r.timePhase2,
                            r.timePhase3, r.nodesExpanded, r.joinsAttempted);
            };
            case "TPB": return (db, pt, tgt, p, t) -> {
                AlgoTPB.Result r = AlgoTPB.mine(db, pt, tgt, p, t);
                return pack(r.patterns, r.timeTotal, r.timePhase1, r.timePhase2,
                            r.timePhase3, r.nodesExpanded, r.joinsAttempted);
            };
            case "PLM": return (db, pt, tgt, p, t) -> {
                AlgoPLM.Result r = AlgoPLM.mine(db, pt, tgt, p, t);
                return pack(r.patterns, r.timeTotal, r.timePhase1, r.timePhase2,
                            r.timePhase3, r.nodesExpanded, r.joinsAttempted);
            };
            case "PC": return (db, pt, tgt, p, t) -> {
                AlgoPC.Result r = AlgoPC.mine(db, pt, tgt, p, t);
                return pack(r.patterns, r.timeTotal, r.timePhase1, r.timePhase2,
                            r.timePhase3, r.nodesExpanded, r.joinsAttempted);
            };
            default: throw new IllegalArgumentException("Unknown algorithm: " + algo);
        }
    }

    static Run pack(List<TopKCollector.Pattern> patterns, long tt, long p1, long p2,
                    long p3, long nodes, long joins) {
        Run r = new Run();
        r.patterns = patterns;
        r.timeTotal = tt; r.timeP1 = p1; r.timeP2 = p2; r.timeP3 = p3;
        r.nodes = nodes; r.joins = joins;
        return r;
    }

    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        parseArgs(args);
        if (minUtils != null && kOverride != null) {
            System.err.println("--k and --minutil are mutually exclusive: a run "
                + "answers either the top-k question or the fixed-threshold one.");
            System.exit(1);
        }
        new File(outDir).mkdirs();
        printEnvironment();

        for (String dsName : datasets) {
            String dbPath = dataDir + "/" + dsName.toLowerCase() + "_database.txt";
            String ptPath = dataDir + "/" + dsName.toLowerCase() + "_profit.txt";

            List<Transaction> db;
            ProfitTable pt;
            try {
                db = DataReader.readDatabase(dbPath);
                pt = DataReader.readProfitTable(ptPath);
            } catch (Exception e) {
                System.out.println("[SKIP] " + dsName + ": " + e.getMessage());
                continue;
            }

            // One sweep point per target: either the k values for this dataset
            // or the requested minUtil values. The two are mutually exclusive,
            // because a run answers one question or the other.
            List<MiningTarget> targets = new ArrayList<>();
            if (minUtils != null) {
                for (double mu : minUtils) targets.add(MiningTarget.minUtil(mu));
            } else {
                int[] kValues = kOverride != null ? kOverride
                              : DEFAULT_K.getOrDefault(dsName, new int[]{50});
                for (int kv : kValues) targets.add(MiningTarget.topK(kv));
            }

            String dsDir = outDir + "/" + dsName;
            new File(dsDir).mkdirs();

            try (PrintWriter perf = new PrintWriter(new FileWriter(dsDir + "/performance.csv"), true);
                 PrintWriter summ = new PrintWriter(new FileWriter(dsDir + "/summary.csv"), true)) {

                // The leading columns are unchanged from the previous harness
                // so that existing analysis scripts keep working; mode and
                // minUtil are appended at the end, where a reader that does not
                // know about them ignores them. In min-util mode the k column
                // holds 0, so a top-k analysis that is accidentally pointed at
                // min-util results collapses them into one cell rather than
                // mixing them with real k values.
                perf.println("dataset,algorithm,k,minProb,threads,run,"
                           + "timeTotal_ms,timePhase1_ms,timePhase2_ms,timePhase3_ms,"
                           + "patternsFound,nodesExpanded,joinsAttempted,"
                           + "peakHeapMB,status,mode,minUtil");
                summ.println("dataset,algorithm,k,minProb,threads,"
                           + "n,mean_ms,stdev_ms,min_ms,max_ms,"
                           + "patternsFound,nodesExpanded,joinsAttempted,"
                           + "peakHeapMB,matchesSEQ,status,mode,minUtil");

                header(dsName, db.size(), targets);

                for (double minProb : minProbs) {
                    for (MiningTarget target : targets) {
                        // SEQ first: its pattern set is the correctness reference
                        // and its mean time is the speedup denominator.
                        List<String> report = new ArrayList<>();
                        String seqSignature = null;
                        long seqMean = 0, seqJoins = 0;

                        if (Arrays.asList(algos).contains("SEQ")) {
                            Config c = measure(dsName, "SEQ", db, pt, target, minProb, 1,
                                               perf, summ, true, null, dsDir);
                            // Only adopt the signature when SEQ actually finished.
                            // On timeout the Config still carries the empty initial
                            // signature, and using it would mark every later row as
                            // a mismatch.
                            if (c.status.equals("OK")) {
                                seqSignature = c.signature;
                                seqMean  = (long) c.mean;
                                seqJoins = c.joins;
                            }
                            report.add(c.line(seqMean, seqJoins));
                        }

                        for (String algo : algos) {
                            if (algo.equals("SEQ")) continue;
                            // A sequential baseline has nothing to vary; running
                            // it at every thread count would just repeat one
                            // measurement four times.
                            int[] ts = isSequential(algo) ? new int[]{1} : threadCounts;
                            for (int t : ts) {
                                Config c = measure(dsName, algo, db, pt, target, minProb, t,
                                                   perf, summ, false, seqSignature, dsDir);
                                report.add(c.line(seqMean, seqJoins));
                                if (c.status.equals("TIMEOUT")) {
                                    System.out.println("    " + algo + " t=" + t
                                        + " timed out; skipping larger thread counts");
                                    break;
                                }
                            }
                        }

                        printReport(target, minProb, report);
                    }
                }
            }
            System.out.println("  -> " + dsDir + "/");
        }
        System.out.println("\nDone.");
    }

    // -------------------------------------------------------------------------
    // Measurement
    // -------------------------------------------------------------------------

    static class Config {
        String algo; int threads;
        double mean, stdev; long min, max;
        long nodes, joins, peakHeapMB;
        int patterns;
        String signature = "";
        String matchesSEQ = "";
        String status = "OK";

        String line(long seqMean, long seqJoins) {
            String speed = (status.equals("OK") && mean > 0 && seqMean > 0)
                         ? String.format("%.2fx", seqMean / mean) : "-";
            String ratio = (status.equals("OK") && seqJoins > 0)
                         ? String.format("%.3f", (double) joins / seqJoins) : "-";
            return String.format("  | %-6s | %7d | %,12.0f | %,10.0f | %7s | %8s | %8d | %-9s |",
                algo, threads, mean, stdev, speed, ratio, patterns,
                status.equals("OK") ? matchesSEQ : status);
        }
    }

    /**
     * @param isReference   true for the SEQ row that the others are compared to
     * @param seqSignature  the reference signature, or null when none is available
     */
    static Config measure(String dsName, String algo,
                          List<Transaction> db, ProfitTable pt,
                          MiningTarget target, double minProb, int threads,
                          PrintWriter perf, PrintWriter summ,
                          boolean isReference, String seqSignature, String dsDir) {
        Config c = new Config();
        c.algo = algo; c.threads = threads;
        Miner miner = minerFor(algo);

        // Warm-up: executed and discarded so the JIT has compiled the hot paths
        // before anything is recorded.
        for (int w = 0; w < warmup; w++) {
            if (runWithTimeout(miner, db, pt, target, minProb, threads) == null) {
                c.status = "TIMEOUT";
                writeTimeout(perf, summ, dsName, algo, target, minProb, threads);
                return c;
            }
        }

        List<Long> times = new ArrayList<>();
        Run last = null;
        for (int i = 0; i < runs; i++) {
            Run r = runWithTimeout(miner, db, pt, target, minProb, threads);
            if (r == null) {
                c.status = "TIMEOUT";
                writeTimeout(perf, summ, dsName, algo, target, minProb, threads);
                return c;
            }
            last = r;
            times.add(r.timeTotal);
            perf.printf("%s,%s,%d,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,OK,%s,%s%n",
                dsName, algo, target.k, minProb, threads, i + 1,
                r.timeTotal, r.timeP1, r.timeP2, r.timeP3,
                r.patterns.size(), r.nodes, r.joins, r.peakHeapMB,
                modeOf(target), minUtilOf(target));
        }

        c.mean  = mean(times);
        c.stdev = stdev(times, c.mean);
        c.min   = Collections.min(times);
        c.max   = Collections.max(times);
        c.nodes = last.nodes;
        c.joins = last.joins;
        c.peakHeapMB = last.peakHeapMB;
        c.patterns = last.patterns.size();
        c.signature = signature(last.patterns);
        c.matchesSEQ = isReference        ? "reference"
                     : seqSignature == null ? "unchecked"
                     : seqSignature.equals(c.signature) ? "yes" : "NO";

        summ.printf("%s,%s,%d,%.2f,%d,%d,%.1f,%.1f,%d,%d,%d,%d,%d,%d,%s,OK,%s,%s%n",
            dsName, algo, target.k, minProb, threads, times.size(),
            c.mean, c.stdev, c.min, c.max,
            c.patterns, c.nodes, c.joins, c.peakHeapMB, c.matchesSEQ,
            modeOf(target), minUtilOf(target));

        if (writePatterns)
            dumpPatterns(last.patterns, dsName, dsDir, algo, target, minProb, threads);
        return c;
    }

    /**
     * Runs one repetition, giving up after timeoutSec.
     *
     * A timed-out worker cannot be forced to stop, because the mining loops do
     * not poll the interrupt flag. Every thread the strategies create is a
     * daemon, so an abandoned run will not keep the JVM alive at the end of the
     * sweep, but it does keep consuming CPU until it finishes. That is why the
     * caller abandons the whole configuration on the first timeout instead of
     * retrying.
     */
    static Run runWithTimeout(Miner miner, List<Transaction> db, ProfitTable pt,
                              MiningTarget target, double minProb, int threads) {
        Callable<Run> task = () -> {
            resetPeakHeap();
            Run r = miner.mine(db, pt, target, minProb, threads);
            r.peakHeapMB = peakHeapMB();
            return r;
        };

        Cancel.reset();

        if (timeoutSec <= 0) {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mining-worker");
            t.setDaemon(true);
            return t;
        });
        Future<Run> fut = ex.submit(task);
        try {
            return fut.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            // Raise the flag and WAIT for the machine to go idle again before
            // the next configuration is timed; an abandoned run still competing
            // for cores would inflate it.
            //
            // The wait is on the EXECUTOR, not on the worker thread. Joining the
            // thread would hang for the whole grace period even after the task
            // had finished, because a pool thread does not die when its task
            // ends -- it parks waiting for the next one, and the pool is only
            // shut down afterwards. awaitTermination returns as soon as the task
            // has actually unwound.
            Cancel.cancel();
            ex.shutdownNow();
            try {
                if (!ex.awaitTermination(DRAIN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    System.out.println("    WARNING: abandoned run still active after "
                        + (DRAIN_GRACE_MS / 1000) + "s; later timings in this cell "
                        + "may be affected");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            ex.shutdownNow();
        }
    }

    /** How long to wait for an abandoned run to unwind before warning. */
    static final long DRAIN_GRACE_MS = 120_000;

    static void writeTimeout(PrintWriter perf, PrintWriter summ, String ds,
                             String algo, MiningTarget target, double minProb,
                             int threads) {
        perf.printf("%s,%s,%d,%.2f,%d,0,,,,,,,,,TIMEOUT,%s,%s%n",
                    ds, algo, target.k, minProb, threads,
                    modeOf(target), minUtilOf(target));
        summ.printf("%s,%s,%d,%.2f,%d,0,,,,,,,,,,TIMEOUT,%s,%s%n",
                    ds, algo, target.k, minProb, threads,
                    modeOf(target), minUtilOf(target));
    }

    static String modeOf(MiningTarget t)    { return t.isTopK ? "topk" : "minutil"; }
    static String minUtilOf(MiningTarget t) { return t.isTopK ? "" : String.valueOf(t.minUtil); }

    // -------------------------------------------------------------------------
    // Correctness signature
    // -------------------------------------------------------------------------

    /**
     * A canonical string for a top-k result: each itemset sorted, then the whole
     * list sorted, so two runs match only if they found the same patterns with
     * the same utilities. This turns "all strategies are exact" into a checked
     * claim rather than an assertion.
     *
     * Ties are not disambiguated: if more than k itemsets share the k-th highest
     * expected utility, two exact strategies may legitimately return different
     * sets. No such case has been observed on the benchmark, but the paper should
     * state the definition rather than rely on it not happening.
     */
    static String signature(List<TopKCollector.Pattern> patterns) {
        List<String> parts = new ArrayList<>(patterns.size());
        for (TopKCollector.Pattern p : patterns) {
            int[] items = p.itemset.clone();
            Arrays.sort(items);
            parts.add(Arrays.toString(items) + String.format("|%.6f", p.eu));
        }
        Collections.sort(parts);
        return String.join(";", parts);
    }

    // -------------------------------------------------------------------------
    // Memory
    // -------------------------------------------------------------------------

    static void resetPeakHeap() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            if (p.getType() == MemoryType.HEAP) p.resetPeakUsage();
    }

    static long peakHeapMB() {
        long peak = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            if (p.getType() == MemoryType.HEAP && p.getPeakUsage() != null)
                peak += p.getPeakUsage().getUsed();
        return peak / (1024 * 1024);
    }

    // -------------------------------------------------------------------------
    // Statistics
    // -------------------------------------------------------------------------

    static double mean(List<Long> xs) {
        double s = 0;
        for (long x : xs) s += x;
        return s / xs.size();
    }

    /** Sample standard deviation (n-1), which is what should be reported. */
    static double stdev(List<Long> xs, double mean) {
        if (xs.size() < 2) return 0;
        double s = 0;
        for (long x : xs) s += (x - mean) * (x - mean);
        return Math.sqrt(s / (xs.size() - 1));
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    static void printEnvironment() {
        System.out.println("JVM        " + System.getProperty("java.version")
                         + "  (" + System.getProperty("java.vm.name") + ")");
        System.out.println("OS         " + System.getProperty("os.name")
                         + " " + System.getProperty("os.arch"));
        System.out.println("Processors " + Runtime.getRuntime().availableProcessors());
        System.out.println("Max heap   " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        System.out.println("Settings   runs=" + runs + " warmup=" + warmup
                         + " timeout=" + timeoutSec + "s threads="
                         + Arrays.toString(threadCounts));
        System.out.println();
    }

    static void header(String name, int txCount, List<MiningTarget> targets) {
        StringBuilder sb = new StringBuilder();
        for (MiningTarget t : targets) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.label());
        }
        String line = " " + name + "  |  " + txCount + " transactions  |  "
                    + sb + " ";
        String bar = "=".repeat(Math.max(line.length() + 2, 70));
        System.out.println(bar);
        System.out.println(line);
        System.out.println(bar);
    }

    static void printReport(MiningTarget target, double minProb, List<String> rows) {
        String sep = "  +--------+---------+--------------+------------+---------+----------+----------+-----------+";
        System.out.printf("%n  %s  minProb=%.2f%n", target.label(), minProb);
        System.out.println(sep);
        System.out.println("  | Algo   | Threads |     Mean(ms) |  Stdev(ms) | Speedup | WorkRatio| Patterns | ==SEQ     |");
        System.out.println(sep);
        for (String r : rows) System.out.println(r);
        System.out.println(sep);
    }

    static void dumpPatterns(List<TopKCollector.Pattern> patterns, String ds,
                             String dsDir, String algo, MiningTarget target,
                             double minProb, int threads) {
        String tag = target.isTopK ? ("k" + target.k)
                                   : ("mu" + (long) target.minUtil);
        String fname = String.format("%s/patterns_%s_%s_%s_p%.2f_t%d.txt",
                                     dsDir, ds, algo, tag, minProb, threads);
        try (PrintWriter pw = new PrintWriter(new FileWriter(fname))) {
            pw.printf("# Dataset=%s Algo=%s %s minProb=%.2f threads=%d%n",
                      ds, algo, target.label(), minProb, threads);
            pw.printf("# Rank  EU              EP        Itemset%n");
            int rank = 1;
            for (TopKCollector.Pattern p : patterns)
                pw.printf("%-5d  %-15.4f  %-8.6f  %s%n",
                          rank++, p.eu, p.ep, Arrays.toString(p.itemset));
        } catch (IOException e) {
            System.err.println("Warning: could not write " + fname);
        }
    }

    // -------------------------------------------------------------------------
    // Command line
    // -------------------------------------------------------------------------

    static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--data-dir":    dataDir = args[++i]; break;
                case "--out":         outDir  = args[++i]; break;
                case "--datasets":    datasets = args[++i].split(","); break;
                case "--algos":       algos = args[++i].split(","); break;
                case "--k":           kOverride = ints(args[++i]); break;
                case "--minutil":     minUtils = doubles(args[++i]); break;
                case "--threads":     threadCounts = ints(args[++i]); break;
                case "--minprob":     minProbs = doubles(args[++i]); break;
                case "--runs":        runs = Integer.parseInt(args[++i]); break;
                case "--warmup":      warmup = Integer.parseInt(args[++i]); break;
                case "--timeout":     timeoutSec = Integer.parseInt(args[++i]); break;
                case "--no-patterns": writePatterns = false; break;
                case "--help":
                    System.out.println("See the comment at the top of Experiment.java");
                    System.exit(0);
                    break;
                default:
                    System.err.println("Unknown option: " + a);
                    System.exit(1);
            }
        }
    }

    static int[] ints(String csv) {
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }

    static double[] doubles(String csv) {
        String[] parts = csv.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i].trim());
        return out;
    }
}