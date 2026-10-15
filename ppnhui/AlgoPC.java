package ppnhui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

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
 *
 * The producer and the consumers are daemon threads: when the harness abandons a
 * run on timeout it cannot stop them, and as non-daemon threads they would keep
 * the JVM alive after the sweep finished.
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
                               MiningTarget target, double minProb, int numThreads) {
        long t0 = System.currentTimeMillis();

        UPUList.BuildResult   pre         = UPUList.buildAll(db, pt, minProb);
        long t1 = System.currentTimeMillis();

        List<Integer>         sortedItems = pre.sortedItems;
        Map<Integer, UPUList> lists       = pre.lists;
        TopKCollector         collector   = target.newCollector();

        for (int item : sortedItems) {
            UPUList ul = lists.get(item);
            if (ul != null) collector.tryCollect(ul);
        }
        long t2 = System.currentTimeMillis();

        BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(sortedItems.size() + numThreads + 1);
        LongAdder nodes = new LongAdder();
        LongAdder joins = new LongAdder();

        Thread producer = new Thread(() -> {
            for (int i = 0; i < sortedItems.size(); i++) {
                try { queue.put(i); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            for (int t = 0; t < numThreads; t++) {
                try { queue.put(POISON); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }, "pc-producer");
        producer.setDaemon(true);
        producer.start();

        Thread[] consumers = new Thread[numThreads];
        for (int t = 0; t < numThreads; t++) {
            consumers[t] = new Thread(() -> {
                while (true) {
                    if (Cancel.isCancelled()) break;
                    int prefixIdx;
                    try { prefixIdx = queue.take(); } catch (InterruptedException e) { break; }
                    if (prefixIdx == POISON) break;

                    int item = sortedItems.get(prefixIdx);
                    UPUList prefix = lists.get(item);
                    if (prefix != null) {
                        dfs(prefix, prefixIdx + 1, sortedItems, lists, collector, minProb, nodes, joins);
                    }
                }
            }, "pc-consumer-" + t);
            consumers[t].setDaemon(true);
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