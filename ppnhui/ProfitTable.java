package ppnhui;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Maps item ID to external profit (can be negative for PPNHUI). */
public class ProfitTable {
    private final Map<Integer, Double> profits;

    public ProfitTable(Map<Integer, Double> profits) {
        this.profits = profits;
    }

    public double getProfit(int item) {
        return profits.getOrDefault(item, 0.0);
    }

    public boolean hasItem(int item) {
        return profits.containsKey(item);
    }

    public Set<Integer> getItems() {
        return profits.keySet();
    }

    public static ProfitTable of(Map<Integer, Double> map) {
        return new ProfitTable(new HashMap<>(map));
    }
}
