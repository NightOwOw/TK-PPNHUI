package ppnhui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;

/**
 * Parallel Local Mining + Merge TK-PPNHUI.
 *
 * Each thread mines its prefix subset with a thread-LOCAL TopKCollector —
 * no shared state, no locks during mining. After all threads finish, local
 * collectors are merged into a global one.
 *
 * Trade-off vs FJ/TPB/PC: zero synchronisation during mining, but a thread
 * cannot see the other threads' discoveries, so its admission threshold rises
 * more slowly and it explores nodes that a shared threshold would have pruned.
 * The effect is amplified for PPNHUI because negative-profit items enlarge the
 * search space.
 *
 * SEEDING OF THE LOCAL COLLECTORS
 *   Every local collector is primed with the same 1-itemsets that the global
 *   collector holds after phase 2. Phase 2 runs sequentially before the work is
 *   partitioned, so this information is available to every partition at no cost,
 *   and any reasonable partition-local implementation would use it. Leaving the
 *   locals empty would start each thread at a threshold of -infinity while the
 *   threshold-sharing strategies start from the k-th best 1-itemset, so the
 *   measured penalty would partly reflect that handicap instead of the property
 *   under study. With the seeding in place, the extra work PLM performs is
 *   attributable to the loss of threshold sharing during mining and nothing
 *   else — which is the claim the paper actually makes.
 */
public class AlgoPLM {

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

        TopKCollector global = target.newCollector();
        for (int item : sortedItems) {
            UPUList ul = lists.get(item);
            if (ul != null) global.tryCollect(ul);
        }

        // Same starting threshold as the shared-threshold strategies; see the
        // class comment for why this is required for a fair comparison.
        final TopKCollector[] locals = new TopKCollector[numThreads];
        for (int b = 0; b < numThreads; b++) {
            locals[b] = target.newCollector();
            for (int item : sortedItems) {
                UPUList ul = lists.get(item);
                if (ul != null) locals[b].tryCollect(ul);
            }
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
        ExecutorService  pool    = AlgoTPB.daemonPool(numThreads, "plm");
        List<Future<?>>  futures = new ArrayList<>();

        for (int b = 0; b < numThreads; b++) {
            final int           bid    = b;
            final List<Integer> bucket = buckets[b];
            futures.add(pool.submit(() -> {
                TopKCollector local = locals[bid];
                for (int pItem : bucket) {
                    if (Cancel.isCancelled()) break;
                    UPUList pList = lists.get(pItem);
                    if (pList == null) continue;
                    int sIdx = itemIndex.get(pItem) + 1;
                    dfs(pList, sIdx, sortedItems, lists, local, minProb, nodes, joins);
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

        for (TopKCollector local : locals) global.mergeFrom(local);

        long t3 = System.currentTimeMillis();
        return new Result(global.getTopK(),
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