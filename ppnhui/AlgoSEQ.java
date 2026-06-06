package ppnhui;

import java.util.List;
import java.util.Map;

/**
 * Sequential Top-K Probabilistic Positive-Negative HUIM (TK-PPNHUI).
 *
 * Single-threaded DFS prefix-growth baseline.
 * Three-tier pruning: EP → PTWU → PUB.
 */
public class AlgoSEQ {

    public static class Result {
        public final List<TopKCollector.Pattern> patterns;
        public final long timeTotal;   // ms
        public final long timePhase1;  // preprocessing ms
        public final long timePhase2;  // init (1-itemsets) ms
        public final long timePhase3;  // mining ms

        public Result(List<TopKCollector.Pattern> patterns,
                      long timeTotal, long timePhase1, long timePhase2, long timePhase3) {
            this.patterns   = patterns;
            this.timeTotal  = timeTotal;
            this.timePhase1 = timePhase1;
            this.timePhase2 = timePhase2;
            this.timePhase3 = timePhase3;
        }
    }

    public static Result mine(List<Transaction> db, ProfitTable pt, int k, double minProb) {
        long t0 = System.currentTimeMillis();

        // Phase 1: preprocessing
        UPUList.BuildResult pre = UPUList.buildAll(db, pt, minProb);
        long t1 = System.currentTimeMillis();

        List<Integer>         sortedItems = pre.sortedItems;
        Map<Integer, UPUList> lists       = pre.lists;
        TopKCollector         collector   = new TopKCollector(k);

        // Phase 2: seed top-k with all valid 1-itemsets
        for (int item : sortedItems) {
            UPUList ul = lists.get(item);
            if (ul != null) collector.tryCollect(ul);
        }
        long t2 = System.currentTimeMillis();

        // Phase 3: DFS pattern growth from each 1-itemset prefix
        for (int i = 0; i < sortedItems.size(); i++) {
            UPUList prefix = lists.get(sortedItems.get(i));
            if (prefix == null) continue;
            dfs(prefix, i + 1, sortedItems, lists, collector, minProb);
        }
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

            // Tier 1: EP pruning (anti-monotone)
            if (joined.ep < minProb - UPUList.EPSILON) continue;

            // Tier 2: PTWU pruning
            if (joined.ptwu < threshold - UPUList.EPSILON) continue;

            // Tier 3: PUB pruning (tighter)
            if (joined.pub < threshold - UPUList.EPSILON) continue;

            if (joined.eu >= threshold - UPUList.EPSILON) {
                collector.tryCollect(joined);
                threshold = collector.getThreshold();
            }

            dfs(joined, i + 1, items, lists, collector, minProb);
            threshold = collector.getThreshold();
        }
    }
}
