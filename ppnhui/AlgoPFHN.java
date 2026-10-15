package ppnhui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parallel FHN baseline: FHN's mining core (EUCS/EUCP, TWU-ascending order)
 * parallelised with the SAME ForkJoin work-stealing scheme and the SAME shared
 * top-k threshold used by the proposed FJ strategy.
 *
 * Using an identical parallelisation and threshold-sharing regime is deliberate:
 * it keeps the comparison against the proposed method fair, so any difference in
 * runtime or work reflects the mining core (FHN's EUCP vs the proposed EP/PTWU/
 * PUB pruning), not the parallel machinery. The super-linear behaviour driven by
 * cooperative threshold escalation is therefore available to the baseline too.
 */
public class AlgoPFHN {

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
        return mine(db, pt, k, minProb, numThreads, true);   // faithful FHN order
    }

    public static Result mine(List<Transaction> db, ProfitTable pt,
                               int k, double minProb, int numThreads, boolean ascending) {
        long t0 = System.currentTimeMillis();

        UPUList.BuildResult   pre   = UPUList.buildAll(db, pt, minProb, ascending);
        EUCS                  eucs  = AlgoFHN.buildEUCS(db, pt, pre.rank);
        long t1 = System.currentTimeMillis();

        List<Integer>         sortedItems = pre.sortedItems;
        Map<Integer, UPUList> lists       = pre.lists;
        TopKCollector         collector   = new TopKCollector(k);

        for (int item : sortedItems) {
            UPUList ul = lists.get(item);
            if (ul != null) collector.tryCollect(ul);
        }
        long t2 = System.currentTimeMillis();

        AtomicLong nodes = new AtomicLong();
        AtomicLong joins = new AtomicLong();

        ForkJoinPool pool = new ForkJoinPool(numThreads);
        try {
            pool.invoke(new PrefixBatchTask(sortedItems, 0, sortedItems.size(),
                                            lists, collector, minProb, eucs, nodes, joins));
        } finally {
            pool.shutdown();
        }
        long t3 = System.currentTimeMillis();

        return new Result(collector.getTopK(),
            t3 - t0, t1 - t0, t2 - t1, t3 - t2,
            nodes.get(), joins.get());
    }

    private static final class PrefixBatchTask extends RecursiveAction {
        private static final long serialVersionUID = 1L;
        private static final int LEAF = 1;

        private final List<Integer>         sortedItems;
        private final int                   from, to;
        private final Map<Integer, UPUList> lists;
        private final TopKCollector         collector;
        private final double                minProb;
        private final EUCS                  eucs;
        private final AtomicLong            nodes;
        private final AtomicLong            joins;

        PrefixBatchTask(List<Integer> sortedItems, int from, int to,
                        Map<Integer, UPUList> lists,
                        TopKCollector collector, double minProb, EUCS eucs,
                        AtomicLong nodes, AtomicLong joins) {
            this.sortedItems = sortedItems;
            this.from        = from;
            this.to          = to;
            this.lists       = lists;
            this.collector   = collector;
            this.minProb     = minProb;
            this.eucs        = eucs;
            this.nodes       = nodes;
            this.joins       = joins;
        }

        @Override
        protected void compute() {
            int size = to - from;
            if (size <= LEAF) {
                int item = sortedItems.get(from);
                UPUList prefix = lists.get(item);
                if (prefix != null) {
                    AlgoFHN.dfs(prefix, from + 1, sortedItems, lists, collector,
                                minProb, eucs, nodes, joins);
                }
                return;
            }
            int mid = from + size / 2;
            PrefixBatchTask left  = new PrefixBatchTask(sortedItems, from, mid,
                                                        lists, collector, minProb, eucs, nodes, joins);
            PrefixBatchTask right = new PrefixBatchTask(sortedItems, mid, to,
                                                        lists, collector, minProb, eucs, nodes, joins);
            left.fork();
            right.compute();
            left.join();
        }
    }
}
