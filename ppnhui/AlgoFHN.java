package ppnhui;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sequential FHN-style external baseline for Top-K PPNHUI.
 *
 * FHN (Fournier-Viger et al., KBS 2016) extends the utility-list miner with the
 * Estimated Utility Co-occurrence Structure (EUCS) and its pruning (EUCP), and
 * processes items in TWU-ascending order. This class reproduces exactly that
 * behaviour on the probabilistic positive-negative problem:
 *
 *   - item order: PTWU ascending (FHM/FHN convention);
 *   - EUCP: before constructing the utility-list of an extension, the pair
 *     (last item of the prefix, extension item) is looked up in the EUCS and
 *     the branch is skipped if its bound is below the current top-k threshold;
 *   - everything else (utility-list join, EP/PTWU/PUB pruning, EU ranking, the
 *     shared top-k collector) is identical to the proposed method, so the only
 *     algorithmic difference under test is FHN's co-occurrence pruning.
 *
 * The DFS body is deliberately kept identical to the proposed method's DFS
 * except for the single EUCP pre-check, isolating EUCP as the variable.
 */
public class AlgoFHN {

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

    public static Result mine(List<Transaction> db, ProfitTable pt, int k, double minProb) {
        return mine(db, pt, k, minProb, true);   // faithful FHN: PTWU-ascending order
    }

    /**
     * @param ascending true  = faithful FHN order (PTWU ascending, FHM/FHN convention);
     *                  false = fairness ablation using the proposed method's PTWU-descending
     *                          order, so EUCP is the only remaining difference.
     */
    public static Result mine(List<Transaction> db, ProfitTable pt, int k, double minProb,
                              boolean ascending) {
        long t0 = System.currentTimeMillis();

        UPUList.BuildResult pre = UPUList.buildAll(db, pt, minProb, ascending);
        EUCS eucs = buildEUCS(db, pt, pre.rank);
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

        for (int i = 0; i < sortedItems.size(); i++) {
            UPUList prefix = lists.get(sortedItems.get(i));
            if (prefix == null) continue;
            dfs(prefix, i + 1, sortedItems, lists, collector, minProb, eucs, nodes, joins);
        }
        long t3 = System.currentTimeMillis();

        return new Result(collector.getTopK(),
            t3 - t0, t1 - t0, t2 - t1, t3 - t2,
            nodes.get(), joins.get());
    }

    /**
     * Builds the EUCS in a single pass: for every transaction, distribute its
     * PTU over all co-occurring valid-item pairs.
     */
    static EUCS buildEUCS(List<Transaction> db, ProfitTable pt, int[] rank) {
        EUCS eucs = new EUCS(rank);
        int[] buf = new int[64];
        for (Transaction t : db) {
            double ptu = 0;
            for (int x = 0; x < t.size; x++) {
                int it = t.items[x];
                if (it >= 0 && it < rank.length && pt.hasItem(it)) {
                    double pr = pt.getProfit(it);
                    if (pr > 0) ptu += pr * t.qtys[x];
                }
            }
            if (ptu <= 0) continue;

            int cnt = 0;
            for (int x = 0; x < t.size; x++) {
                int it = t.items[x];
                if (it >= 0 && it < rank.length && rank[it] >= 0) {
                    if (cnt >= buf.length) buf = Arrays.copyOf(buf, cnt * 2);
                    buf[cnt++] = it;
                }
            }
            for (int a = 0; a < cnt; a++)
                for (int b = a + 1; b < cnt; b++)
                    eucs.add(buf[a], buf[b], ptu);
        }
        return eucs;
    }

    static void dfs(UPUList prefix, int startIdx,
                    List<Integer> items,
                    Map<Integer, UPUList> lists,
                    TopKCollector collector,
                    double minProb,
                    EUCS eucs,
                    AtomicLong nodes,
                    AtomicLong joins) {
        nodes.incrementAndGet();
        if (prefix.ptwu < collector.getThreshold() - UPUList.EPSILON) return;

        int lastItem = items.get(startIdx - 1);   // most recently added item of the prefix

        for (int i = startIdx; i < items.size(); i++) {
            int extItem = items.get(i);

            // --- EUCP: FHN's co-occurrence prune (skips list construction) ---
            if (eucs.get(lastItem, extItem) < collector.getThreshold() - UPUList.EPSILON) continue;

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

            dfs(joined, i + 1, items, lists, collector, minProb, eucs, nodes, joins);
        }
    }
}
