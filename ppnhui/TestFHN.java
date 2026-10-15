package ppnhui;

import java.util.*;

/**
 * Correctness harness for the FHN baseline on a synthetic uncertain database
 * containing both positive- and negative-profit items.
 *
 * Because SEQ, AlgoFHN and AlgoPFHN solve the identical top-k PPNHUI problem,
 * they MUST return the same set of top-k patterns (same itemsets, EU and EP).
 * Any difference indicates an unsound upper bound or a mining bug. Enumeration
 * order and parallelism must not change the result.
 */
public class TestFHN {

    public static void main(String[] args) {
        Random rnd = new Random(42);
        int numItems = 14, numTx = 400;

        // Profit table: items 1..9 positive, items 10..14 negative.
        Map<Integer, Double> profit = new HashMap<>();
        for (int it = 1; it <= numItems; it++) {
            double p = (it <= 9) ? (1 + rnd.nextInt(10)) : -(1 + rnd.nextInt(6));
            profit.put(it, p);
        }
        ProfitTable pt = ProfitTable.of(profit);

        // Random uncertain transactions.
        List<Transaction> db = new ArrayList<>();
        for (int tid = 0; tid < numTx; tid++) {
            List<Integer> its = new ArrayList<>();
            for (int it = 1; it <= numItems; it++) if (rnd.nextDouble() < 0.40) its.add(it);
            if (its.isEmpty()) its.add(1 + rnd.nextInt(numItems));
            int n = its.size();
            int[] items = new int[n];
            int[] qtys  = new int[n];
            double[] probs = new double[n];
            for (int i = 0; i < n; i++) {
                items[i] = its.get(i);
                qtys[i]  = 1 + rnd.nextInt(3);
                probs[i] = 0.5 + rnd.nextDouble() * 0.5;   // [0.5, 1.0]
            }
            db.add(new Transaction(tid, items, qtys, probs));
        }

        int k = 12;
        double minProb = 0.5;

        // SEQ now takes a MiningTarget so that the same core can also answer the
        // fixed-threshold question; AlgoFHN and AlgoPFHN are unchanged and still
        // take k directly, since they exist only for this top-k harness.
        AlgoSEQ.Result  seq   = AlgoSEQ.mine(db, pt, MiningTarget.topK(k), minProb);
        AlgoFHN.Result  fhnA  = AlgoFHN.mine(db, pt, k, minProb, true);   // faithful (ascending)
        AlgoFHN.Result  fhnD  = AlgoFHN.mine(db, pt, k, minProb, false);  // ablation (descending)
        AlgoPFHN.Result pfhnA = AlgoPFHN.mine(db, pt, k, minProb, 8, true);
        AlgoPFHN.Result pfhnD = AlgoPFHN.mine(db, pt, k, minProb, 8, false);

        System.out.println("Synthetic DB: " + numTx + " tx, " + numItems
                + " items (5 negative-profit)   k=" + k + "  minProb=" + minProb);

        boolean ok1 = sameTopK(seq.patterns, fhnA.patterns);
        boolean ok2 = sameTopK(seq.patterns, pfhnA.patterns);
        boolean ok3 = sameTopK(seq.patterns, fhnD.patterns);
        boolean ok4 = sameTopK(seq.patterns, pfhnD.patterns);
        System.out.println("SEQ == FHN  ascending  (seq) : " + (ok1 ? "PASS" : "FAIL"));
        System.out.println("SEQ == PFHN ascending  (8t)  : " + (ok2 ? "PASS" : "FAIL"));
        System.out.println("SEQ == FHN  descending (seq) : " + (ok3 ? "PASS" : "FAIL"));
        System.out.println("SEQ == PFHN descending (8t)  : " + (ok4 ? "PASS" : "FAIL"));
        boolean okAll = ok1 && ok2 && ok3 && ok4;

        System.out.println("\nTop-" + k + " (by SEQ):");
        for (TopKCollector.Pattern p : seq.patterns)
            System.out.printf("  %-22s EU=%10.4f  EP=%.4f%n",
                    Arrays.toString(p.itemset), p.eu, p.ep);

        System.out.printf("%nWork metrics (nodes / joins):%n");
        System.out.printf("  SEQ            : %,d / %,d%n", seq.nodesExpanded,   seq.joinsAttempted);
        System.out.printf("  FHN  ascending : %,d / %,d%n", fhnA.nodesExpanded,  fhnA.joinsAttempted);
        System.out.printf("  FHN  descending: %,d / %,d%n", fhnD.nodesExpanded,  fhnD.joinsAttempted);
        System.out.printf("  PFHN ascending : %,d / %,d%n", pfhnA.nodesExpanded, pfhnA.joinsAttempted);
        System.out.printf("  PFHN descending: %,d / %,d%n", pfhnD.nodesExpanded, pfhnD.joinsAttempted);

        if (!okAll) {
            System.out.println("\nRESULT: FAIL");
            System.exit(1);
        }
        System.out.println("\nRESULT: ALL PASS");
    }

    /** Order-independent multiset comparison of (itemset, EU, EP). */
    static boolean sameTopK(List<TopKCollector.Pattern> a, List<TopKCollector.Pattern> b) {
        if (a.size() != b.size()) return false;
        List<TopKCollector.Pattern> bb = new ArrayList<>(b);
        for (TopKCollector.Pattern pa : a) {
            boolean found = false;
            for (int i = 0; i < bb.size(); i++) {
                TopKCollector.Pattern pb = bb.get(i);
                if (Arrays.equals(pa.itemset, pb.itemset)
                        && Math.abs(pa.eu - pb.eu) < 1e-6
                        && Math.abs(pa.ep - pb.ep) < 1e-6) {
                    bb.remove(i);
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }
}