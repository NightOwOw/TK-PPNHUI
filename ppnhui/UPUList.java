package ppnhui;

import java.util.*;

/**
 * UPU-List (Utility-Probability-Utility List) for one itemset.
 *
 * Stores per-transaction entries sorted by TID (ascending), enabling
 * O(n+m) two-pointer join. Also caches aggregated EU, EP, PTWU, PUB.
 *
 * For PPNHUI, items with negative profit ARE included; the remaining
 * utility (suffix sum) counts only positive-profit items, so PTWU and
 * PUB remain valid upper bounds even when the itemset contains negatives.
 */
public class UPUList {

    static final double LOG_ZERO  = -700.0;
    static final double EPSILON   = 1e-9;

    // Itemset this list represents (sorted item IDs).
    public final int[] itemset;

    // Per-transaction arrays (valid range: [0, size)).
    public final int[]    tids;        // transaction IDs
    public final double[] utils;       // u(X,T)  — can be negative
    public final double[] remainings;  // positive suffix after X in T
    public final double[] logProbs;    // log P(X,T)
    public final int      size;

    // Aggregated metrics.
    public final double eu;    // Expected Utility
    public final double ep;    // Existential Probability
    public final double ptwu;  // PTWU upper bound
    public final double pub;   // Positive Upper Bound

    public UPUList(int[] itemset, int[] tids, double[] utils,
                   double[] remainings, double[] logProbs, int size,
                   double ptwu, double eu, double ep, double pub) {
        this.itemset    = itemset;
        this.tids       = tids;
        this.utils      = utils;
        this.remainings = remainings;
        this.logProbs   = logProbs;
        this.size       = size;
        this.ptwu       = ptwu;
        this.eu         = eu;
        this.ep         = ep;
        this.pub        = pub;
    }

    // -------------------------------------------------------------------------
    // Two-pointer join
    // -------------------------------------------------------------------------

    /**
     * Joins this prefix list with the UPU-List of one extension item.
     * Returns null if the joined PTWU < threshold (early pruning) or the
     * intersection is empty.
     */
    public static UPUList join(UPUList a, UPUList ext, int extItem, double threshold) {
        double joinedPTWU = Math.min(a.ptwu, ext.ptwu);
        if (joinedPTWU < threshold - EPSILON) return null;

        int maxSize = Math.min(a.size, ext.size);
        int[]    tids      = new int[maxSize];
        double[] utils     = new double[maxSize];
        double[] remainings= new double[maxSize];
        double[] logProbs  = new double[maxSize];

        double sumEU = 0, logComp = 0, posUB = 0;
        int count = 0, i = 0, j = 0;

        while (i < a.size && j < ext.size) {
            int ta = a.tids[i], tb = ext.tids[j];
            if (ta == tb) {
                double util = a.utils[i] + ext.utils[j];
                double rem  = Math.min(a.remainings[i], ext.remainings[j]);
                double lp   = a.logProbs[i] + ext.logProbs[j];

                tids[count]       = ta;
                utils[count]      = util;
                remainings[count] = rem;
                logProbs[count]   = lp;
                count++;

                double prob  = Math.exp(lp);
                sumEU += util * prob;
                double total = util + rem;
                if (total > 0) posUB += prob * total;

                // EP log-space accumulation
                if (lp > -EPSILON) {                         // prob ≈ 1 → EP = 1
                    logComp = LOG_ZERO;
                } else if (logComp > LOG_ZERO) {
                    double lc = (prob < 0.5) ? Math.log1p(-prob) : Math.log(1.0 - prob);
                    logComp += lc;
                    if (logComp < LOG_ZERO) logComp = LOG_ZERO;
                }
                i++; j++;
            } else if (ta < tb) { i++; } else { j++; }
        }

        if (count == 0) return null;

        double ep = (logComp <= LOG_ZERO) ? 1.0 : 1.0 - Math.exp(logComp);

        // New itemset = a.itemset + extItem, sorted by item ID.
        int[] newItemset = Arrays.copyOf(a.itemset, a.itemset.length + 1);
        newItemset[a.itemset.length] = extItem;
        Arrays.sort(newItemset);

        return new UPUList(newItemset,
            Arrays.copyOf(tids, count), Arrays.copyOf(utils, count),
            Arrays.copyOf(remainings, count), Arrays.copyOf(logProbs, count),
            count, joinedPTWU, sumEU, ep, posUB);
    }

    // -------------------------------------------------------------------------
    // Preprocessing: build all 1-itemset UPU-Lists from the database
    // -------------------------------------------------------------------------

    /** Result of the full preprocessing phase. */
    public static class BuildResult {
        public final List<Integer>          sortedItems;  // by PTWU desc
        public final int[]                  rank;         // item -> rank (-1 if filtered)
        public final Map<Integer, UPUList>  lists;        // item -> UPUList

        public BuildResult(List<Integer> sortedItems, int[] rank, Map<Integer, UPUList> lists) {
            this.sortedItems = sortedItems;
            this.rank        = rank;
            this.lists       = lists;
        }
    }

    /**
     * Phases 1a-1d: compute PTWU/EP, filter, rank, build UPU-Lists.
     * All items with positive profit AND items with negative profit that
     * co-occur with positive items (PTWU > 0) and EP >= minProb are included.
     */
    public static BuildResult buildAll(List<Transaction> db, ProfitTable pt, double minProb) {

        // --- Phase 1a: find max item ID ---
        int maxItem = 0;
        for (int id : pt.getItems()) if (id > maxItem) maxItem = id;

        double[] ptwu    = new double[maxItem + 1];
        double[] logComp = new double[maxItem + 1];   // accumulates log(1-p)

        // --- Phase 1b: compute PTWU and EP log-complement ---
        for (Transaction t : db) {
            // PTU(T) = sum of positive-profit utilities
            double ptu = 0;
            for (int k = 0; k < t.size; k++) {
                int item = t.items[k];
                if (item < 0 || item > maxItem || !pt.hasItem(item)) continue;
                if (pt.getProfit(item) > 0) ptu += pt.getProfit(item) * t.qtys[k];
            }
            // Distribute PTU and accumulate log-complement
            for (int k = 0; k < t.size; k++) {
                int item = t.items[k];
                if (item < 0 || item > maxItem || !pt.hasItem(item)) continue;
                ptwu[item] += ptu;
                double p = t.probs[k];
                if (p <= 0) continue;
                if (p >= 1.0) {
                    logComp[item] = LOG_ZERO; // certain existence
                } else if (logComp[item] > LOG_ZERO) {
                    double lc = (p < 0.5) ? Math.log1p(-p) : Math.log(1.0 - p);
                    logComp[item] += lc;
                    if (logComp[item] < LOG_ZERO) logComp[item] = LOG_ZERO;
                }
            }
        }

        // --- Phase 1c: filter valid items (EP >= minProb AND PTWU > 0) ---
        List<Integer> validItems = new ArrayList<>();
        for (int item : pt.getItems()) {
            if (item < 0 || item > maxItem) continue;
            double ep = (logComp[item] <= LOG_ZERO) ? 1.0 : 1.0 - Math.exp(logComp[item]);
            if (ep >= minProb - EPSILON && ptwu[item] > 0) validItems.add(item);
        }

        // --- Phase 1d: rank by PTWU descending ---
        validItems.sort((a, b) -> Double.compare(ptwu[b], ptwu[a]));

        int[] rank = new int[maxItem + 1];
        Arrays.fill(rank, -1);
        for (int r = 0; r < validItems.size(); r++) rank[validItems.get(r)] = r;

        // --- Phase 1e: collect per-item entries from DB ---
        int n = validItems.size();
        @SuppressWarnings("unchecked")
        List<double[]>[] entries = new List[n]; // index = rank; double[] = {tid, util, remaining, logP}
        for (int r = 0; r < n; r++) entries[r] = new ArrayList<>();

        int tidVal = 0;
        // Reusable sort buffers (resized as needed per transaction)
        int[] txRanks = new int[64];
        int[] txIdx   = new int[64];

        for (Transaction t : db) {
            // Collect valid item positions in this transaction
            int cnt = 0;
            for (int k = 0; k < t.size; k++) {
                int item = t.items[k];
                if (item < 0 || item > maxItem || rank[item] < 0) continue;
                if (cnt >= txRanks.length) {
                    txRanks = Arrays.copyOf(txRanks, cnt * 2);
                    txIdx   = Arrays.copyOf(txIdx,   cnt * 2);
                }
                txRanks[cnt] = rank[item];
                txIdx[cnt]   = k;
                cnt++;
            }

            if (cnt == 0) { tidVal++; continue; }

            // Sort by rank ascending (insertion sort — transactions are short)
            for (int i = 1; i < cnt; i++) {
                int kr = txRanks[i], ki = txIdx[i], j = i - 1;
                while (j >= 0 && txRanks[j] > kr) {
                    txRanks[j+1] = txRanks[j]; txIdx[j+1] = txIdx[j]; j--;
                }
                txRanks[j+1] = kr; txIdx[j+1] = ki;
            }

            // Compute suffix sums (positive utilities of items ranked AFTER position i)
            double[] sfx = new double[cnt];
            sfx[cnt - 1] = 0;
            for (int i = cnt - 2; i >= 0; i--) {
                int k    = txIdx[i + 1];
                int item = t.items[k];
                double prof = pt.getProfit(item);
                sfx[i] = sfx[i + 1] + (prof > 0 ? prof * t.qtys[k] : 0);
            }

            // Create entries
            for (int i = 0; i < cnt; i++) {
                int    k    = txIdx[i];
                int    item = t.items[k];
                double p    = t.probs[k];
                if (p <= 0) continue;
                double logP = Math.log(p);
                if (logP < LOG_ZERO) continue;

                double util = pt.getProfit(item) * t.qtys[k];
                entries[txRanks[i]].add(new double[]{tidVal, util, sfx[i], logP});
            }
            tidVal++;
        }

        // --- Phase 1f: build UPU-List objects ---
        Map<Integer, UPUList> lists = new LinkedHashMap<>();
        for (int r = 0; r < n; r++) {
            int item = validItems.get(r);
            List<double[]> ents = entries[r];
            if (ents.isEmpty()) continue;

            int sz = ents.size();
            int[]    tids      = new int[sz];
            double[] utils2    = new double[sz];
            double[] rems      = new double[sz];
            double[] lps       = new double[sz];
            double sumEU = 0, lc = 0, posUB = 0;

            for (int i = 0; i < sz; i++) {
                double[] e = ents.get(i);
                tids[i]   = (int) e[0];
                utils2[i] = e[1];
                rems[i]   = e[2];
                lps[i]    = e[3];

                double prob  = Math.exp(e[3]);
                sumEU += e[1] * prob;
                double total = e[1] + e[2];
                if (total > 0) posUB += prob * total;

                if (e[3] > -EPSILON) {
                    lc = LOG_ZERO;
                } else if (lc > LOG_ZERO) {
                    double lc2 = (prob < 0.5) ? Math.log1p(-prob) : Math.log(1.0 - prob);
                    lc += lc2;
                    if (lc < LOG_ZERO) lc = LOG_ZERO;
                }
            }

            double ep = (lc <= LOG_ZERO) ? 1.0 : 1.0 - Math.exp(lc);
            lists.put(item, new UPUList(new int[]{item}, tids, utils2, rems, lps, sz,
                                        ptwu[item], sumEU, ep, posUB));
        }

        return new BuildResult(validItems, rank, lists);
    }
}
