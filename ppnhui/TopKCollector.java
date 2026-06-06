package ppnhui;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe top-k pattern collector backed by a min-heap.
 *
 * The admission threshold (= k-th highest EU) is stored as a volatile
 * field, allowing mining threads to do a fast lock-free read before
 * attempting the (expensive) locked admission.
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

    private final int k;
    private final PriorityQueue<Pattern> heap;   // min-heap by EU
    private final ReentrantLock lock = new ReentrantLock();
    private volatile double threshold = Double.NEGATIVE_INFINITY;  // k-th highest EU seen; -inf means heap not full yet

    public TopKCollector(int k) {
        this.k    = k;
        this.heap = new PriorityQueue<>(k + 1, Comparator.comparingDouble(p -> p.eu));
    }

    /** Fast lock-free read of the current admission threshold. */
    public double getThreshold() { return threshold; }

    /**
     * Tries to admit the given UPU-List as a top-k pattern.
     * Returns true if admitted (or updated).
     */
    public boolean tryCollect(UPUList list) {
        if (list.eu < threshold - UPUList.EPSILON) return false;  // fast reject

        lock.lock();
        try {
            if (list.eu < threshold - UPUList.EPSILON) return false;
            heap.offer(new Pattern(list.itemset, list.eu, list.ep));
            if (heap.size() > k) heap.poll();   // evict weakest
            threshold = heap.size() < k ? Double.NEGATIVE_INFINITY : heap.peek().eu;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Returns top-k patterns sorted by EU descending. */
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

    /** Merges another collector's results into this one (used by PLM). */
    public void mergeFrom(TopKCollector other) {
        for (Pattern p : other.getTopK()) {
            lock.lock();
            try {
                heap.offer(p);
                if (heap.size() > k) heap.poll();
                threshold = heap.size() < k ? Double.NEGATIVE_INFINITY : heap.peek().eu;
            } finally {
                lock.unlock();
            }
        }
    }

    public int size() {
        lock.lock();
        try { return heap.size(); } finally { lock.unlock(); }
    }
}
