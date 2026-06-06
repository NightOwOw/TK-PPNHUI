package ppnhui;

/** One transaction: items with quantity and existential probability. */
public class Transaction {
    public final int      tid;
    public final int[]    items;  // item IDs
    public final int[]    qtys;   // quantities
    public final double[] probs;  // existential probabilities
    public final int      size;

    public Transaction(int tid, int[] items, int[] qtys, double[] probs) {
        this.tid   = tid;
        this.items = items;
        this.qtys  = qtys;
        this.probs = probs;
        this.size  = items.length;
    }
}
