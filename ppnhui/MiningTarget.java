package ppnhui;

/**
 * What the miner is asked to return: a top-k set, or every itemset above a
 * fixed utility threshold.
 *
 * WHY THIS EXISTS
 *   The mining core prunes against a single number, the admission threshold
 *   read from the collector, and Theorem 1 only requires that this number never
 *   exceeds the utility of the weakest itemset in the answer. Top-k mining
 *   satisfies that with a threshold that rises as the heap fills; classical
 *   high-utility mining satisfies it with a threshold that is fixed at minUtil
 *   from the start. Neither the traversal, the joins, nor any of the three
 *   pruning tiers can tell the difference, so the same core solves both
 *   problems and this class is the only place that knows which one is being
 *   solved.
 *
 *   The point is not convenience. It lets the positive-negative uncertain
 *   miner be compared against classical high-utility miners on their own
 *   problem: set every profit positive, every probability to one, and ask for
 *   a fixed threshold, and the algorithm degenerates exactly to utility-list
 *   mining with TWU and remaining-utility pruning (see the note below). Any
 *   remaining difference in running time is then the cost of carrying the more
 *   general model, measured rather than argued.
 *
 * WHY THE BASE CASE IS EXACTLY CLASSICAL HUIM
 *   With p(i,T) = 1 for every entry, P(X,T) = 1, so EU(X) reduces to the
 *   ordinary utility u(X) and EP(X) = 1, which no minProb in [0,1] can reject.
 *   With every profit positive, PTU(T) sums all of T, so PTWU becomes the
 *   classical transaction-weighted utility and rem(X,T) becomes the classical
 *   remaining utility, making PUB the utility-list upper bound of HUI-Miner.
 *   The three tiers therefore collapse onto the standard ones rather than
 *   merely resembling them.
 */
public final class MiningTarget {

    /** Number of patterns to keep, or 0 when a fixed threshold is used. */
    public final int k;

    /** The fixed admission threshold, or NaN in top-k mode. */
    public final double minUtil;

    /** True for top-k mining, false for fixed-threshold mining. */
    public final boolean isTopK;

    private MiningTarget(int k, double minUtil, boolean isTopK) {
        this.k       = k;
        this.minUtil = minUtil;
        this.isTopK  = isTopK;
    }

    /** Return the k itemsets of highest expected utility. */
    public static MiningTarget topK(int k) {
        if (k < 1) throw new IllegalArgumentException("k must be at least 1");
        return new MiningTarget(k, Double.NaN, true);
    }

    /** Return every itemset whose expected utility is at least minUtil. */
    public static MiningTarget minUtil(double minUtil) {
        return new MiningTarget(0, minUtil, false);
    }

    /** A collector implementing this target. */
    public TopKCollector newCollector() {
        return isTopK ? TopKCollector.forTopK(k)
                      : TopKCollector.forMinUtil(minUtil);
    }

    /** The value that identifies this run in the results files. */
    public String label() {
        return isTopK ? ("k=" + k) : ("minUtil=" + minUtil);
    }

    @Override
    public String toString() { return label(); }
}
