package ppnhui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Sequential Top-K Probabilistic Positive-Negative HUIM (TK-PPNHUI).
 *
 * Single-threaded DFS prefix-growth baseline.
 * Three-tier pruning: EP → PTWU → PUB.
 * Records nodesExpanded and joinsAttempted for work-metric analysis.
 *
 * NOTE ON INSTRUMENTATION
 *   The work counters use LongAdder rather than AtomicLong in every strategy,
 *   including this sequential one. Under an AtomicLong every DFS node and every
 *   join is a compare-and-swap on one shared cache line, so the instrumentation
 *   cost grows with the thread count and depresses the measured speedup of the
 *   parallel strategies. Using the same LongAdder in the baseline keeps the
 *   measurement overhead identical across strategies, so the reported speedups
 *   are not an artefact of how the work was counted.
 */
public class AlgoSEQ {

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

    public static Result mine(List<Transaction> db, ProfitTable pt, MiningTarget target, double minProb) {
        long t0 = System.currentTimeMillis();

        UPUList.BuildResult pre = UPUList.buildAll(db, pt, minProb);
        long t1 = System.currentTimeMillis();

        List<Integer>         sortedItems = pre.sortedItems;
        Map<Integer, UPUList> lists       = pre.lists;
        TopKCollector         collector   = target.newCollector();

        for (int item : sortedItems) {
            UPUList ul = lists.get(item);
            if (ul != null) collector.tryCollect(ul);
        }
        long t2 = System.currentTimeMillis();

        LongAdder nodes = new LongAdder();
        LongAdder joins = new LongAdder();

        for (int i = 0; i < sortedItems.size(); i++) {
            if (Cancel.isCancelled()) break;
            UPUList prefix = lists.get(sortedItems.get(i));
            if (prefix == null) continue;
            dfs(prefix, i + 1, sortedItems, lists, collector, minProb, nodes, joins);
        }
        long t3 = System.currentTimeMillis();

        return new Result(collector.getTopK(),
            t3 - t0, t1 - t0, t2 - t1, t3 - t2,
            nodes.sum(), joins.sum());
    }

    static void dfs(UPUList prefix, int startIdx,
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