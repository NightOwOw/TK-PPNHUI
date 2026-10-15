package ppnhui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Sequential TK-PPNHUI with REVERSED top-level prefix order (SEQ-REV).
 *
 * WHY THIS BASELINE EXISTS
 *   AlgoFJ decomposes the top-level prefixes with
 *
 *       left.fork(); right.compute(); left.join();
 *
 *   so a single ForkJoin worker drains its deque LIFO and visits the top-level
 *   prefixes in exactly reverse index order. Because sortedItems is ordered by
 *   PTWU descending, FJ at one thread therefore mines the CHEAP prefixes first
 *   and the expensive ones last, while SEQ does the opposite.
 *
 *   That matters for the top-k threshold. Mining cheap prefixes first raises the
 *   admission threshold early, so by the time the largest subtree is reached the
 *   PTWU and PUB tests can prune much more of it. SEQ pays the full un-pruned
 *   cost of its most expensive subtree while the threshold is still at its
 *   starting value.
 *
 *   Without this baseline the measured advantage of FJ at one thread conflates
 *   two independent effects: the traversal order, and the ForkJoin machinery
 *   itself. SEQ-REV isolates them:
 *
 *       SEQ      -> SEQ-REV    benefit of the traversal order alone
 *       SEQ-REV  -> FJ@1t      cost of the ForkJoin machinery alone
 *       FJ@1t    -> FJ@Nt      benefit of parallelism alone
 *
 *   If SEQ-REV reproduces most of FJ's one-thread advantage, that advantage is a
 *   property of the enumeration order rather than of work stealing, and the
 *   paper must say so. If it does not, the advantage really does come from the
 *   task decomposition, and this baseline rules out the alternative explanation.
 *
 * WHAT IS AND IS NOT CHANGED
 *   Only the order of the top-level loop. The item ranking, the suffix bounds,
 *   the extension index (i + 1), the three-tier pruning and the collector are
 *   all untouched, so the search space and the returned top-k set are identical
 *   to SEQ. This is NOT the same as building the UPU-Lists in ascending PTWU
 *   order: that would renumber the items and change the search space itself.
 */
public class AlgoSEQREV {

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

        // The single difference from AlgoSEQ: descending index instead of
        // ascending. startIdx stays i + 1 so each prefix is still extended only
        // by higher-ranked items and every itemset is still enumerated once.
        for (int i = sortedItems.size() - 1; i >= 0; i--) {
            if (Cancel.isCancelled()) break;
            UPUList prefix = lists.get(sortedItems.get(i));
            if (prefix == null) continue;
            AlgoSEQ.dfs(prefix, i + 1, sortedItems, lists, collector, minProb, nodes, joins);
        }
        long t3 = System.currentTimeMillis();

        return new Result(collector.getTopK(),
            t3 - t0, t1 - t0, t2 - t1, t3 - t2,
            nodes.sum(), joins.sum());
    }
}