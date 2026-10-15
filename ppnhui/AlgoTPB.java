package ppnhui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;

/**
 * ThreadPool + PTWU-Balanced static partitioning TK-PPNHUI.
 *
 * Prefixes sorted PTWU-desc are assigned to threads via round-robin so the
 * estimated workload is spread evenly without dynamic overhead.
 *
 * A single shared TopKCollector lets the threshold rise globally.
 * nodesExpanded and joinsAttempted count total work across all threads.
 *
 * NOTE ON DAEMON WORKERS
 *   The pool is built with a daemon thread factory. When the experiment harness
 *   abandons a run on timeout it can only interrupt its own worker; the mining
 *   loops do not poll the interrupt flag, so these threads keep running. As
 *   non-daemon threads they would prevent the JVM from exiting at the end of the
 *   sweep, losing the whole batch. As daemons they merely consume CPU until they
 *   finish, which is why the harness abandons the configuration rather than
 *   retrying it.
 */
public class AlgoTPB {

    public static class Result {
        public final List<TopKCollector.Pattern> patterns;
        public final long timeTotal, timePhase1, timePhase2, timePhase3;
        public final long nodesExpanded;
        public final long joinsAttempted;

        public Result(List<TopKCollector.Pattern> patterns,
                      long timeTotal, long timePhase1, long timePhase2, long timePhase3,
                      long nodesExpanded, long joinsAttempted) {
            this.patterns       = patterns;
            this.timeTotal      = timeTotal;
            this.timePhase1     = timePhase1;
            this.timePhase2     = timePhase2;
            this.timePhase3     = timePhase3;
            this.nodesExpanded  = nodesExpanded;
            this.joinsAttempted = joinsAttempted;
        }
    }

    /** Daemon workers, named for readability in thread dumps. */
    static ExecutorService daemonPool(int numThreads, String prefix) {
        final int[] seq = {0};
        return Executors.newFixedThreadPool(numThreads, r -> {
            Thread t = new Thread(r, prefix + "-" + (seq[0]++));
            t.setDaemon(true);
            return t;
        });
    }

    public static Result mine(List<Transaction> db, ProfitTable pt,
                               MiningTarget target, double minProb, int numThreads) {
        long t0 = System.currentTimeMillis();

        UPUList.BuildResult   pre         = UPUList.buildAll(db, pt, minProb);
        long t1 = System.currentTimeMillis();

        List<Integer>         sortedItems = pre.sortedItems;
        Map<Integer, UPUList> lists       = pre.lists;
        TopKCollector         collector   = target.newCollector();

        for (int item : sortedItems) {
            UPUList ul = lists.get(item);
            if (ul != null) collector.tryCollect(ul);
        }
        long t2 = System.currentTimeMillis();

        int n = sortedItems.size();
        @SuppressWarnings("unchecked")
        List<Integer>[] buckets = new List[numThreads];
        for (int b = 0; b < numThreads; b++) buckets[b] = new ArrayList<>();
        for (int i = 0; i < n; i++) buckets[i % numThreads].add(sortedItems.get(i));

        Map<Integer, Integer> itemIndex = new HashMap<>();
        for (int i = 0; i < n; i++) itemIndex.put(sortedItems.get(i), i);

        LongAdder        nodes   = new LongAdder();
        LongAdder        joins   = new LongAdder();
        ExecutorService  pool    = daemonPool(numThreads, "tpb");
        List<Future<?>>  futures = new ArrayList<>();

        for (int b = 0; b < numThreads; b++) {
            final List<Integer> bucket = buckets[b];
            futures.add(pool.submit(() -> {
                for (int pItem : bucket) {
                    if (Cancel.isCancelled()) break;
                    UPUList pList = lists.get(pItem);
                    if (pList == null) continue;
                    int sIdx = itemIndex.get(pItem) + 1;
                    dfs(pList, sIdx, sortedItems, lists, collector, minProb, nodes, joins);
                }
            }));
        }

        try {
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        } finally {
            pool.shutdown();
        }
        long t3 = System.currentTimeMillis();

        return new Result(collector.getTopK(),
            t3 - t0, t1 - t0, t2 - t1, t3 - t2,
            nodes.sum(), joins.sum());
    }

    private static void dfs(UPUList prefix, int startIdx,
                             List<Integer> items,
                             Map<Integer, UPUList> lists,
                             TopKCollector collector,
                             double minProb,
                             LongAdder nodes,
                             LongAdder joins) {
        if (Cancel.isCancelled()) return;   // abandoned run: unwind
        nodes.increment();
        if (prefix.ptwu < collector.getThreshold() - UPUList.EPSILON) return;

        for (int i = startIdx; i < items.size(); i++) {
            // Checked here, not only on entry: a cancelled run must not keep
            // paying for joins. Returning from the recursive call is not enough,
            // because this loop would carry on building the remaining
            // extensions of the current prefix, and a join is the single most
            // expensive thing the miner does.
            if (Cancel.isCancelled()) return;
            int extItem = items.get(i);
            UPUList ext = lists.get(extItem);
            if (ext == null) continue;

            joins.increment();
            UPUList joined = UPUList.join(prefix, ext, extItem, collector.getThreshold());
            if (joined == null) continue;

            if (joined.ep   < minProb                  - UPUList.EPSILON) continue;
            if (joined.ptwu < collector.getThreshold() - UPUList.EPSILON) continue;
            if (joined.pub  < collector.getThreshold() - UPUList.EPSILON) continue;

            if (joined.eu  >= collector.getThreshold() - UPUList.EPSILON) {
                collector.tryCollect(joined);
            }

            dfs(joined, i + 1, items, lists, collector, minProb, nodes, joins);
        }
    }
}