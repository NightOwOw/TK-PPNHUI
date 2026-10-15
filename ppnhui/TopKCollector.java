package ppnhui;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe result collector, in either of two modes.
 *
 * TOP-K MODE
 *   Keeps the k highest-EU patterns in a min-heap and publishes the k-th
 *   highest EU as the admission threshold. The threshold starts at -infinity
 *   and rises as the heap fills, which is what the parallel strategies share.
 *
 * MIN-UTIL MODE
 *   Keeps every pattern whose EU reaches a threshold that is fixed at
 *   construction and never changes, which is the classical high-utility
 *   formulation. This exists so that the same mining core can solve the base
 *   case and be compared against classical miners on their own problem; see
 *   MiningTarget.
 *
 * The two modes differ only in whether the threshold moves. Every caller reads
 * it through getThreshold(), so the traversal, the joins and the three pruning
 * tiers are identical in both, and Theorem 1 covers both: its proof needs only
 * that the threshold never decreases and never exceeds the utility of the
 * weakest itemset in the answer. In top-k mode that holds because the heap only
 * improves; in min-util mode a constant trivially never decreases, and it never
 * exceeds the weakest answer because the answer is by definition everything at
 * or above it.
 *
 * The threshold is volatile so that mining threads can reject a candidate
 * without taking the lock, and so that the heap update that accompanies a
 * threshold change is published to any thread that observes the new value.
 */
public class TopKCollector {

    public static class Pattern {
        public final int[]  itemset;
        public final double eu;
        public final double ep;

        public Pattern(int[] itemset, double eu, double ep) {
            this.itemset = itemset;
            this.eu      = eu;
            this.ep      = ep;
        }
    }

    /** Initial heap capacity in min-util mode, where the result is unbounded. */
    private static final int MINUTIL_INITIAL_CAPACITY = 1024;

    private final int     k;              // meaningful only in top-k mode
    private final boolean fixedThreshold; // true in min-util mode
    private final PriorityQueue<Pattern> heap;   // min-heap by EU
    private final ReentrantLock lock = new ReentrantLock();
    private volatile double threshold;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    private TopKCollector(int k, boolean fixedThreshold, double initialThreshold,
                          int capacity) {
        this.k              = k;
        this.fixedThreshold = fixedThreshold;
        this.threshold      = initialThreshold;
        this.heap           = new PriorityQueue<>(capacity,
                                  Comparator.comparingDouble(p -> p.eu));
    }

    /** Top-k mode: keep the k best, threshold rises with the heap. */
    public static TopKCollector forTopK(int k) {
        return new TopKCollector(k, false, Double.NEGATIVE_INFINITY, k + 1);
    }

    /** Min-util mode: keep everything at or above a threshold that never moves. */
    public static TopKCollector forMinUtil(double minUtil) {
        return new TopKCollector(0, true, minUtil, MINUTIL_INITIAL_CAPACITY);
    }

    /**
     * Retained so that existing call sites keep working; equivalent to
     * {@link #forTopK(int)}.
     */
    public TopKCollector(int k) {
        this(k, false, Double.NEGATIVE_INFINITY, k + 1);
    }

    // -------------------------------------------------------------------------

    /** Fast lock-free read of the current admission threshold. */
    public double getThreshold() { return threshold; }

    /** True when this collector's threshold is fixed (min-util mode). */
    public boolean isFixedThreshold() { return fixedThreshold; }

    /**
     * Tries to admit the given UPU-List. Returns true if it was kept.
     *
     * In min-util mode nothing is ever evicted and the threshold is never
     * touched, so the locked section reduces to a heap insertion; the lock is
     * still required because the heap itself is shared.
     */
    public boolean tryCollect(UPUList list) {
        if (list.eu < threshold - UPUList.EPSILON) return false;  // fast reject

        lock.lock();
        try {
            if (list.eu < threshold - UPUList.EPSILON) return false;
            heap.offer(new Pattern(list.itemset, list.eu, list.ep));
            if (!fixedThreshold) {
                if (heap.size() > k) heap.poll();   // evict weakest
                threshold = heap.size() < k ? Double.NEGATIVE_INFINITY
                                            : heap.peek().eu;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** The result, sorted by EU descending. */
    public List<Pattern> getTopK() {
        lock.lock();
        try {
            List<Pattern> result = new ArrayList<>(heap);
            result.sort((a, b) -> Double.compare(b.eu, a.eu));
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** Canonical identity of a pattern: its itemset, order-independent. */
    private static String idOf(Pattern p) {
        int[] items = p.itemset.clone();
        Arrays.sort(items);
        return Arrays.toString(items);
    }

    /**
     * Merges another collector's results into this one (used by PLM).
     *
     * Itemsets already present are skipped. Partition-local collectors are
     * primed with the same 1-itemsets as the global collector, so without this
     * check the merge would insert several copies of the same itemset and evict
     * genuine patterns, producing a result that differs from the sequential
     * reference. Deduplication is the right semantics regardless: the union of
     * several result sets is a set of itemsets, not a multiset.
     *
     * In min-util mode no eviction occurs and the threshold never moves, so the
     * merge is a plain set union.
     */
    public void mergeFrom(TopKCollector other) {
        List<Pattern> incoming = other.getTopK();   // snapshot before locking

        lock.lock();
        try {
            Set<String> present = new HashSet<>();
            for (Pattern p : heap) present.add(idOf(p));

            for (Pattern p : incoming) {
                if (!present.add(idOf(p))) continue;   // already held
                heap.offer(p);
                if (!fixedThreshold) {
                    if (heap.size() > k) {
                        Pattern evicted = heap.poll();
                        present.remove(idOf(evicted));
                    }
                    threshold = heap.size() < k ? Double.NEGATIVE_INFINITY
                                                : heap.peek().eu;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try { return heap.size(); } finally { lock.unlock(); }
    }
}
