package ppnhui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

/**
 * ForkJoin (work-stealing) parallel TK-PPNHUI.
 *
 * Each 1-itemset prefix becomes a top-level ForkJoinTask. Work-stealing
 * automatically balances load across threads. A single shared TopKCollector
 * (with volatile threshold + ReentrantLock) is used so every thread benefits
 * from the rising admission threshold.
 */
public class AlgoFJ {

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

        List<Integer>         sortedItems = pre.sortedItems;
        Map<Integer, UPUList> lists       = pre.lists;
        TopKCollector         collector   = new TopKCollector(k);

        // Phase 2: seed with 1-itemsets (sequential — fast)
        for (int item : sortedItems) {
            UPUList ul = lists.get(item);
            if (ul != null) collector.tryCollect(ul);
        }
        long t2 = System.currentTimeMillis();

        // Phase 3: parallel prefix mining via ForkJoin
        ForkJoinPool pool = new ForkJoinPool(numThreads);
        try {
            pool.invoke(new PrefixBatchTask(sortedItems, 0, sortedItems.size(),
                                            lists, collector, minProb));
        } finally {
            pool.shutdown();
        }
        long t3 = System.currentTimeMillis();

        return new Result(collector.getTopK(),
            t3 - t0, t1 - t0, t2 - t1, t3 - t2);
    }

    // -------------------------------------------------------------------------
    // ForkJoin task: processes a range of top-level prefixes
    // -------------------------------------------------------------------------
    private static final class PrefixBatchTask extends RecursiveAction {
        private static final long serialVersionUID = 1L;
        private static final int LEAF = 1;  // each prefix is its own leaf

        private final List<Integer>         sortedItems;
        private final int                   from, to;
        private final Map<Integer, UPUList> lists;
        private final TopKCollector         collector;
        private final double                minProb;

        PrefixBatchTask(List<Integer> sortedItems, int from, int to,
                        Map<Integer, UPUList> lists,
                        TopKCollector collector, double minProb) {
            this.sortedItems = sortedItems;
            this.from        = from;
            this.to          = to;
            this.lists       = lists;
            this.collector   = collector;
            this.minProb     = minProb;
        }

        @Override
        protected void compute() {
            int size = to - from;
            if (size <= LEAF) {
                // Process one prefix sequentially
                int prefixIdx = from;
                int item      = sortedItems.get(prefixIdx);
                UPUList prefix = lists.get(item);
                if (prefix != null) {
                    dfs(prefix, prefixIdx + 1, sortedItems, lists, collector, minProb);
                }
                return;
            }
            int mid = from + size / 2;
            PrefixBatchTask left  = new PrefixBatchTask(sortedItems, from, mid,  lists, collector, minProb);
            PrefixBatchTask right = new PrefixBatchTask(sortedItems, mid,  to,   lists, collector, minProb);
            left.fork();
            right.compute();
            left.join();
        }
    }

    static void dfs(UPUList prefix, int startIdx,
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
