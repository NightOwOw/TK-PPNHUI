package ppnhui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ThreadPool + PTWU-Balanced static partitioning TK-PPNHUI.
 *
 * Prefixes sorted PTWU-desc are assigned to threads via round-robin so the
 * estimated workload is spread evenly without dynamic overhead.
 *
 * A single shared TopKCollector lets the threshold rise globally.
 * nodesExpanded and joinsAttempted count total work across all threads.
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

    public static Result mine(List<Transaction> db, ProfitTable pt,
                               int k, double minProb, int numThreads) {
        long t0 = System.currentTimeMillis();

        UPUList.BuildResult   pre         = UPUList.buildAll(db, pt, minProb);
        long t1 = System.currentTimeMillis();

        List<Integer>         sortedItems = pre.sortedItems;
        Map<Integer, UPUList> lists       = pre.lists;
        TopKCollector         collector   = new TopKCollector(k);

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

        AtomicLong       nodes   = new AtomicLong();
        AtomicLong       joins   = new AtomicLong();
        ExecutorService  pool    = Executors.newFixedThreadPool(numThreads);
        List<Future<?>>  futures = new ArrayList<>();

        for (int b = 0; b < numThreads; b++) {
            final List<Integer> bucket = buckets[b];
            futures.add(pool.submit(() -> {
                for (int pItem : bucket) {
                    UPUList pList = lists.get(pItem);
                    if (pList == null) continue;
                    int sIdx = itemIndex.get(pItem) + 1;
                    dfs(pList, sIdx, sortedItems, lists, collector, minProb, nodes, joins);
                }
            }));
        }

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
        pool.shutdown();
        long t3 = System.currentTimeMillis();

        return new Result(collector.getTopK(),
            t3 - t0, t1 - t0, t2 - t1, t3 - t2,
            nodes.get(), joins.get());
    }

    private static void dfs(UPUList prefix, int startIdx,
                             List<Integer> items,
                             Map<Integer, UPUList> lists,
                             TopKCollector collector,
                             double minProb,
                             AtomicLong nodes,
                             AtomicLong joins) {
        nodes.incrementAndGet();
        if (prefix.ptwu < collector.getThreshold() - UPUList.EPSILON) return;

        for (int i = startIdx; i < items.size(); i++) {
            int extItem = items.get(i);
            UPUList ext = lists.get(extItem);
            if (ext == null) continue;

            joins.incrementAndGet();
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
