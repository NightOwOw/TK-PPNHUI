package ppnhui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * Compared with TPB:
 *   - Fully dynamic: fast prefixes complete early and consumers pick up more
 *   - Handles uneven workloads better than static partitioning
 *
 * nodesExpanded and joinsAttempted count total work across all consumer threads.
 */
public class AlgoPC {

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

    private static final int POISON = -1;

    public static Result mine(List<Transaction> db, ProfitTable pt,
                               int k, double minProb, int numThreads) {
        long t0 = System.currentTimeMillis();

        UPUList.BuildResult   pre         = UPUList.buildAll(db, pt, minProb);
        long t1 = System.currentTimeMillis();

        List<Integer>         sortedItems = pre.sortedItems;
        Map<Integer, UPUList> lists       = pre.lists;
        TopKCollector         collector   = new TopKCollector(k);

        for (int item : sortedItems) {
            UPUList ul = lists.get(item);
            if (ul != null) collector.tryCollect(ul);
        }
        long t2 = System.currentTimeMillis();

        BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(sortedItems.size() + numThreads + 1);
        AtomicLong nodes = new AtomicLong();
        AtomicLong joins = new AtomicLong();

        Thread producer = new Thread(() -> {
            for (int i = 0; i < sortedItems.size(); i++) {
                try { queue.put(i); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            for (int t = 0; t < numThreads; t++) {
                try { queue.put(POISON); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        });
        producer.start();

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
                        dfs(prefix, prefixIdx + 1, sortedItems, lists, collector, minProb, nodes, joins);
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
            t3 - t0, t1 - t0, t2 - t1, t3 - t2,
            nodes.get(), joins.get());
    }

    private static void dfs(UPUList prefix, int startIdx,
                             List<Integer> items,
                             Map<Integer, UPUList> lists,
                             TopKCollector collector,
                             double minProb,
                             AtomicLong nodes,
                             AtomicLong joins) {
        nodes.incrementAndGet();
        if (prefix.ptwu < collector.getThreshold() - UPUList.EPSILON) return;

        for (int i = startIdx; i < items.size(); i++) {
            int extItem = items.get(i);
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

            dfs(joined, i + 1, items, lists, collector, minProb, nodes, joins);
        }
    }
}
