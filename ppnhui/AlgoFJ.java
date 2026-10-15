package ppnhui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.LongAdder;

/**
 * ForkJoin (work-stealing) parallel TK-PPNHUI.
 *
 * Each 1-itemset prefix becomes a top-level ForkJoinTask. Work-stealing
 * automatically balances load across threads. A single shared TopKCollector
 * allows every thread to benefit from the rising admission threshold.
 *
 * nodesExpanded and joinsAttempted are shared LongAdders across all tasks,
 * measuring total work done by all threads combined. LongAdder replaces
 * AtomicLong because the counters are touched once per DFS node and once per
 * join: a single atomic cell would serialise every worker on one cache line
 * and depress the measured speedup at high thread counts.
 *
 * NOTE ON TRAVERSAL ORDER
 *   compute() forks the left half and computes the right half, so with a single
 *   worker the pool's deque is drained LIFO and the top-level prefixes are
 *   visited in reverse order — that is, PTWU-ascending, the opposite of SEQ.
 *   This is why FJ already does less work than SEQ at one thread, and it is a
 *   property of the task decomposition rather than of work stealing.
 */
public class AlgoFJ {

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

        LongAdder nodes = new LongAdder();
        LongAdder joins = new LongAdder();

        ForkJoinPool pool = new ForkJoinPool(numThreads);
        try {
            pool.invoke(new PrefixBatchTask(sortedItems, 0, sortedItems.size(),
                                            lists, collector, minProb, nodes, joins));
        } finally {
            pool.shutdown();
        }
        long t3 = System.currentTimeMillis();

        return new Result(collector.getTopK(),
            t3 - t0, t1 - t0, t2 - t1, t3 - t2,
            nodes.sum(), joins.sum());
    }

    // -------------------------------------------------------------------------
    // ForkJoin task: processes a range of top-level prefixes
    // -------------------------------------------------------------------------
    private static final class PrefixBatchTask extends RecursiveAction {
        private static final long serialVersionUID = 1L;
        private static final int LEAF = 1;

        private final List<Integer>         sortedItems;
        private final int                   from, to;
        private final Map<Integer, UPUList> lists;
        private final TopKCollector         collector;
        private final double                minProb;
        private final LongAdder             nodes;
        private final LongAdder             joins;

        PrefixBatchTask(List<Integer> sortedItems, int from, int to,
                        Map<Integer, UPUList> lists,
                        TopKCollector collector, double minProb,
                        LongAdder nodes, LongAdder joins) {
            this.sortedItems = sortedItems;
            this.from        = from;
            this.to          = to;
            this.lists       = lists;
            this.collector   = collector;
            this.minProb     = minProb;
            this.nodes       = nodes;
            this.joins       = joins;
        }

        @Override
        protected void compute() {
            if (Cancel.isCancelled()) return;
            int size = to - from;
            if (size <= LEAF) {
                int item = sortedItems.get(from);
                UPUList prefix = lists.get(item);
                if (prefix != null) {
                    dfs(prefix, from + 1, sortedItems, lists, collector, minProb, nodes, joins);
                }
                return;
            }
            int mid = from + size / 2;
            PrefixBatchTask left  = new PrefixBatchTask(sortedItems, from, mid,
                                                         lists, collector, minProb, nodes, joins);
            PrefixBatchTask right = new PrefixBatchTask(sortedItems, mid,  to,
                                                         lists, collector, minProb, nodes, joins);
            left.fork();
            right.compute();
            left.join();
        }
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