import ppnhui.*;
import java.util.*;

/**
 * Checks that the base case really is classical high-utility mining, rather
 * than merely resembling it.
 *
 * Three claims are tested against an independent brute-force enumeration over
 * every non-empty itemset, written without reference to the miner's data
 * structures:
 *
 *   1. EU(X) equals the ordinary utility u(X) = sum over T of u(X,T), with no
 *      probability weighting anywhere.
 *   2. EP(X) = 1 for every itemset, so the existential-probability filter can
 *      never reject anything, for any minProb in [0,1].
 *   3. Min-util mode returns exactly {X : u(X) >= minUtil}, the classical
 *      answer set, with no itemset missing and none added.
 *
 * The third is the one that matters: an exact miner that quietly dropped a
 * pattern would still look plausible on timing alone.
 */
public class VerifyBaseCase {

    public static void main(String[] args) throws Exception {
        String db = args[0], pt = args[1];
        double minUtil = Double.parseDouble(args[2]);

        List<Transaction> data = DataReader.readDatabase(db);
        ProfitTable profit = DataReader.readProfitTable(pt);

        // ---- brute force over every non-empty itemset -----------------------
        Set<Integer> itemSet = new TreeSet<>();
        for (Transaction t : data) for (int it : t.items) itemSet.add(it);
        List<Integer> items = new ArrayList<>(itemSet);
        if (items.size() > 20) {
            System.out.println("Refusing to brute-force " + items.size()
                + " items; use a smaller probe.");
            return;
        }

        Map<String, Double> trueUtil = new HashMap<>();
        int n = items.size();
        for (int mask = 1; mask < (1 << n); mask++) {
            List<Integer> X = new ArrayList<>();
            for (int b = 0; b < n; b++) if ((mask & (1 << b)) != 0) X.add(items.get(b));
            double u = 0;
            int support = 0;
            for (Transaction t : data) {
                Set<Integer> tx = new HashSet<>();
                for (int it : t.items) tx.add(it);
                if (!tx.containsAll(X)) continue;
                support++;
                for (int x : X) {
                    for (int k = 0; k < t.size; k++)
                        if (t.items[k] == x) { u += profit.getProfit(x) * t.qtys[k]; break; }
                }
            }
            // An itemset that occurs in no transaction is not a pattern: its
            // utility is an empty sum, not zero utility, and no miner enumerates
            // it. Including such combinations would make the reference disagree
            // with the miner at minUtil <= 0 for a reason that has nothing to do
            // with the algorithm.
            if (support > 0) trueUtil.put(key(X), u);
        }

        Set<String> expected = new TreeSet<>();
        for (Map.Entry<String, Double> e : trueUtil.entrySet())
            if (e.getValue() >= minUtil) expected.add(e.getKey());

        // ---- the miner, in min-util mode ------------------------------------
        AlgoSEQ.Result r = AlgoSEQ.mine(data, profit,
                                        MiningTarget.minUtil(minUtil), 0.7);

        Set<String> got = new TreeSet<>();
        int epViolations = 0, euViolations = 0;
        double worstEuGap = 0;
        for (TopKCollector.Pattern p : r.patterns) {
            List<Integer> X = new ArrayList<>();
            for (int it : p.itemset) X.add(it);
            Collections.sort(X);
            String k = key(X);
            got.add(k);

            if (Math.abs(p.ep - 1.0) > 1e-9) epViolations++;

            Double exp = trueUtil.get(k);
            if (exp != null) {
                double gap = Math.abs(p.eu - exp);
                worstEuGap = Math.max(worstEuGap, gap);
                if (gap > 1e-6) euViolations++;
            }
        }

        Set<String> missing = new TreeSet<>(expected); missing.removeAll(got);
        Set<String> extra   = new TreeSet<>(got);      extra.removeAll(expected);

        System.out.println("Base-case verification   minUtil = " + minUtil);
        System.out.println("  itemsets occurring at least once  " + trueUtil.size());
        System.out.println("  classical answer |{u >= mu}|  " + expected.size());
        System.out.println("  returned by the miner         " + got.size());
        System.out.println();
        System.out.println("  (1) EU == ordinary utility    "
            + (euViolations == 0 ? "PASS" : "FAIL (" + euViolations + " rows)")
            + "   worst gap " + String.format("%.3g", worstEuGap));
        System.out.println("  (2) EP == 1 everywhere        "
            + (epViolations == 0 ? "PASS" : "FAIL (" + epViolations + " rows)"));
        System.out.println("  (3) answer set exact          "
            + (missing.isEmpty() && extra.isEmpty() ? "PASS"
               : "FAIL   missing " + missing.size() + ", extra " + extra.size()));
        if (!missing.isEmpty()) System.out.println("      missing: " + trunc(missing));
        if (!extra.isEmpty())   System.out.println("      extra:   " + trunc(extra));

        boolean ok = euViolations == 0 && epViolations == 0
                  && missing.isEmpty() && extra.isEmpty();
        System.out.println();
        System.out.println(ok ? "ALL CHECKS PASSED" : "CHECKS FAILED");
        if (!ok) System.exit(1);
    }

    static String key(List<Integer> X) { return X.toString(); }

    static String trunc(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        return l.size() <= 5 ? l.toString()
                             : l.subList(0, 5) + " ... (" + l.size() + " total)";
    }
}
