package ppnhui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Producer-Consumer TK-PPNHUI.
 *
 * A single producer thread enqueues each valid 1-itemset prefix as a task
 * into a BlockingQueue. N consumer threads pull tasks and mine them via DFS.
 * Consumers share a single TopKCollector (threshold visible globally).
 *
 * Compared with FJ:
 *   - No recursive task decomposition (simpler implementation)
 *   - Granularity is fixed at the 1-itemset level
 *   - Queue overhead is bounded (at most |sortedItems| tasks ever enqueued)
 *
 * Compared with TPB:
 *   - Fully dynamic: fast prefixes complete early and consumers pick up more
 *   - Handles uneven workloads better than static partitioning
 */
public class AlgoPC {

    public static class Result {
        public final List<TopKCollector.Pattern> patterns;
        public final long timeTotal, timePhase1, timePhase2, timePhase3;

        public Result(List<TopKCollector.Pattern> patterns,
                      long timeTotal, long timePhase1, long timePhase2, long timePhase3) {
            this.patterns   = patterns;
            this.timeTotal  = timeTotal;
            this.timePhase1 = timePhase1;
            this.timePhase2 = timePhase2;
            this.timePhase3 = timePhase3;
        }
    }

    /** Sentinel task that signals a consumer to shut down. */
    private static final int POISON = -1;

    public static Result mine(List<Transaction> db, ProfitTable pt,
                               int k, double minProb, int numThreads) {
        long t0 = System.currentTimeMillis();

        UPUList.BuildResult   pre         = UPUList.buildAll(db, pt, minProb);
        long t1 = System.currentTimeMillis();

        List<Integer>         sortedItems = pre.sortedItems;
        Map<Integer, UPUList> lists       = pre.lists;
        TopKCollector         collector   = new TopKCollector(k);

        // Phase 2: seed with 1-itemsets
        for (int item : sortedItems) {
            UPUList ul = lists.get(item);
            if (ul != null) collector.tryCollect(ul);
        }
        long t2 = System.currentTimeMillis();

        // Phase 3: producer-consumer
        // Queue capacity: all prefixes + one POISON per thread
        BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(sortedItems.size() + numThreads + 1);

        // Producer: enqueue prefix item indices (position in sortedItems)
        Thread producer = new Thread(() -> {
            for (int i = 0; i < sortedItems.size(); i++) {
                try { queue.put(i); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            // Enqueue one POISON pill per consumer
            for (int t = 0; t < numThreads; t++) {
                try { queue.put(POISON); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        });
        producer.start();

        // Consumers: pull prefix index, run DFS
        Thread[] consumers = new Thread[numThreads];
        for (int t = 0; t < numThreads; t++) {
            consumers[t] = new Thread(() -> {
                while (true) {
                    int prefixIdx;
                    try { prefixIdx = queue.take(); } catch (InterruptedException e) { break; }
                    if (prefixIdx == POISON) break;

                    int item = sortedItems.get(prefixIdx);
                    UPUList prefix = lists.get(item);
                    if (prefix != null) {
                        dfs(prefix, prefixIdx + 1, sortedItems, lists, collector, minProb);
                    }
                }
            });
            consumers[t].start();
        }

        try {
            producer.join();
            for (Thread c : consumers) c.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long t3 = System.currentTimeMillis();
        return new Result(collector.getTopK(),
            t3 - t0, t1 - t0, t2 - t1, t3 - t2);
    }

    private static void dfs(UPUList prefix, int startIdx,
                             List<Integer> items,
                             Map<Integer, UPUList> lists,
                             TopKCollector collector,
                             double minProb) {

        double threshold = collector.getThreshold();
        if (prefix.ptwu < threshold - UPUList.EPSILON) return;

        for (int i = startIdx; i < items.size(); i++) {
            int extItem = items.get(i);
            UPUList ext = lists.get(extItem);
            if (ext == null) continue;

            UPUList joined = UPUList.join(prefix, ext, extItem, threshold);
            if (joined == null) continue;

            if (joined.ep   < minProb   - UPUList.EPSILON) continue;
            if (joined.ptwu < threshold - UPUList.EPSILON) continue;
            if (joined.pub  < threshold - UPUList.EPSILON) continue;

            if (joined.eu >= threshold - UPUList.EPSILON) {
                collector.tryCollect(joined);
                threshold = collector.getThreshold();
            }

            dfs(joined, i + 1, items, lists, collector, minProb);
            threshold = collector.getThreshold();
        }
    }
}
