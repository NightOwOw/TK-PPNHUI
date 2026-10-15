package ppnhui;

/**
 * Cooperative cancellation for abandoned (timed-out) runs.
 *
 * WHY THIS IS NEEDED
 *   When a configuration exceeds the harness timeout, the harness stops waiting
 *   for it — but it cannot stop the threads the strategy created. Those threads
 *   are daemons, so they no longer keep the JVM alive at the end of a sweep, but
 *   they do keep consuming cores. Without cancellation an abandoned eight-thread
 *   run would still be spinning while the NEXT configuration is being timed, and
 *   that configuration would be reported as OK with a badly inflated time. The
 *   corruption is silent: nothing in the output marks it.
 *
 *   On the heavy datasets, where timeouts are expected rather than hypothetical,
 *   this would quietly invalidate every measurement taken after the first
 *   timeout. Hence the flag: every mining loop polls it and unwinds, so the
 *   machine is idle again before the next configuration starts.
 *
 * COST
 *   One volatile boolean read per DFS node. On x86 that is an ordinary load, and
 *   the check sits next to the PTWU test that already reads the shared threshold,
 *   so it does not add a new memory stall. The overhead was measured on a dense
 *   synthetic dataset and was within run-to-run noise.
 *
 * SCOPE
 *   Runs are executed one at a time, so a single global flag is sufficient. It
 *   is cleared before each run and set only on timeout. Cancelled runs are always
 *   discarded, never reported, so a partially explored search space can never
 *   reach the results files.
 */
public final class Cancel {

    private static volatile boolean cancelled = false;

    private Cancel() { }

    /** Called by the harness before each measured or warm-up run. */
    public static void reset() { cancelled = false; }

    /** Called by the harness when a run exceeds its timeout. */
    public static void cancel() { cancelled = true; }

    /** Polled by every mining loop. */
    public static boolean isCancelled() { return cancelled; }
}