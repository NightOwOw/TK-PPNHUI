package ppnhui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * ThreadPool + PTWU-Balanced static partitioning TK-PPNHUI.
 *
 * Prefixes are sorted by PTWU descending (already done in preprocessing),
 * then assigned to threads via round-robin: the heaviest prefix goes to
 * thread 0, second-heaviest to thread 1, ..., cycling back to thread 0.
 * This spreads the estimated workload evenly without any dynamic overhead.
 *
 * A single shared TopKCollector allows threshold to rise globally.
 */
public class AlgoTPB {

    public static class Result {
        public final List<TopKCollector.Pattern> patterns;
        public final long timeTotal, timePhase1, timePhase2, timePhase3;

        public Result(List<TopKCollector.Pattern> patterns,
                      long timeTotal, long timePhase1, long timePhase2, long timePhase3) {
            this.patterns   = patterns;
            this.timeTotal  = timeTotal;
            this.timePhase1 = timePhase1;
            this.timePhase2 = timePhase2;
            this.timePhase3 = timePhase3;
        }
    }

    public static Result mine(List<Transaction> db, ProfitTable pt,
                               int k, double minProb, int numThreads) {
        long t0 = System.currentTimeMillis();

        UPUList.BuildResult   pre         = UPUList.buildAll(db, pt, minProb);
        long t1 = System.currentTimeMillis();

        List<Integer>         sortedItems = pre.sortedItems;  // already PTWU-desc
        Map<Integer, UPUList> lists       = pre.lists;
        TopKCollector         collector   = new TopKCollector(k);

        // Phase 2: seed with 1-itemsets
        for (int item : sortedItems) {
            UPUList ul = lists.get(item);
            if (ul != null) collector.tryCollect(ul);
        }
        long t2 = System.currentTimeMillis();

        // Phase 3: PTWU-balanced round-robin partitioning
        int n = sortedItems.size();
        @SuppressWarnings("unchecked")
        List<Integer>[] buckets = new List[numThreads];
        for (int b = 0; b < numThreads; b++) buckets[b] = new ArrayList<>();
        for (int i = 0; i < n; i++) buckets[i % numThreads].add(sortedItems.get(i));

        // Build item -> index map for O(1) startIdx lookup
        Map<Integer, Integer> itemIndex = new HashMap<>();
        for (int i = 0; i < n; i++) itemIndex.put(sortedItems.get(i), i);

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> futures = new ArrayList<>();

        for (int b = 0; b < numThreads; b++) {
            final List<Integer> bucket = buckets[b];
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    for (int pItem : bucket) {
                        UPUList pList = lists.get(pItem);
                        if (pList == null) continue;
                        int sIdx = itemIndex.get(pItem) + 1;
                        dfs(pList, sIdx, sortedItems, lists, collector, minProb);
                    }
                }
            };
            futures.add(pool.submit(task));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        pool.shutdown();
        long t3 = System.currentTimeMillis();

        return new Result(collector.getTopK(),
            t3 - t0, t1 - t0, t2 - t1, t3 - t2);
    }

    private static void dfs(UPUList prefix, int startIdx,
                             List<Integer> items,
                             Map<Integer, UPUList> lists,
                             TopKCollector collector,
                             double minProb) {

        double threshold = collector.getThreshold();
        if (prefix.ptwu < threshold - UPUList.EPSILON) return;

        for (int i = startIdx; i < items.size(); i++) {
            int extItem = items.get(i);
            UPUList ext = lists.get(extItem);
            if (ext == null) continue;

            UPUList joined = UPUList.join(prefix, ext, extItem, threshold);
            if (joined == null) continue;

            if (joined.ep   < minProb   - UPUList.EPSILON) continue;
            if (joined.ptwu < threshold - UPUList.EPSILON) continue;
            if (joined.pub  < threshold - UPUList.EPSILON) continue;

            if (joined.eu >= threshold - UPUList.EPSILON) {
                collector.tryCollect(joined);
                threshold = collector.getThreshold();
            }

            dfs(joined, i + 1, items, lists, collector, minProb);
            threshold = collector.getThreshold();
        }
    }
}
