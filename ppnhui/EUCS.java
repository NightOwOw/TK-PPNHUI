package ppnhui;

import java.util.HashMap;

/**
 * Estimated Utility Co-occurrence Structure (EUCS) — FHM/FHN's distinguishing
 * pruning device, adapted to the probabilistic positive-negative setting.
 *
 * For every pair of items {a,b} that co-occur in a transaction, EUCS stores the
 * accumulated PTU (positive transaction utility) of those transactions:
 *
 *     EUCS[a][b] = sum over T with a,b in T of PTU(T)
 *
 * This is a valid upper bound on the expected utility EU(X) of ANY itemset
 * X that contains both a and b, because
 *     EU(X) = sum_{T supset X} u(X,T)*P(X,T)
 *           <= sum_{T supset X} PTU(T)*1
 *           <= sum_{T supset {a,b}} PTU(T) = EUCS[a][b].
 * Hence if EUCS[a][b] < threshold, no itemset containing {a,b} can enter the
 * top-k, and the whole branch is pruned before any utility-list is built (EUCP).
 *
 * Stored sparsely (only co-occurring pairs) so it scales to sparse databases
 * such as Retail with thousands of items. Pairs are keyed by rank so each
 * unordered pair maps to a single slot.
 */
public final class EUCS {

    private final int[] rank;
    private final HashMap<Long, Double> map = new HashMap<>();

    public EUCS(int[] rank) {
        this.rank = rank;
    }

    private long key(int a, int b) {
        int ra = rank[a], rb = rank[b];
        int lo = Math.min(ra, rb), hi = Math.max(ra, rb);
        return (((long) lo) << 32) | (hi & 0xffffffffL);
    }

    /** Accumulate co-occurrence weight for the unordered pair {a,b}. */
    public void add(int a, int b, double v) {
        map.merge(key(a, b), v, Double::sum);
    }

    /** Upper bound stored for {a,b}; 0.0 if the pair never co-occurred. */
    public double get(int a, int b) {
        Double d = map.get(key(a, b));
        return d == null ? 0.0 : d;
    }

    public int pairCount() {
        return map.size();
    }
}
