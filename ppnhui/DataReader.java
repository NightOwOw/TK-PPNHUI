package ppnhui;

import java.io.*;
import java.util.*;

/**
 * Reads transaction database and profit table from files.
 *
 * Database format (one transaction per line):
 *   item:qty:prob item:qty:prob ...
 *   e.g.  1:4:0.61 3:4:0.62 5:1:0.05
 *
 * Profit format (one item per line):
 *   item profit
 *   e.g.  1 5.0
 *         3 -8.0
 */
public class DataReader {

    public static List<Transaction> readDatabase(String path) throws IOException {
        List<Transaction> db = new ArrayList<>();
        int tid = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] tokens = line.split("\\s+");
                int n = tokens.length;
                int[]    items = new int[n];
                int[]    qtys  = new int[n];
                double[] probs = new double[n];
                int count = 0;
                for (String tok : tokens) {
                    String[] parts = tok.split(":");
                    if (parts.length != 3) continue;
                    try {
                        items[count] = Integer.parseInt(parts[0]);
                        qtys[count]  = Integer.parseInt(parts[1]);
                        probs[count] = Double.parseDouble(parts[2]);
                        count++;
                    } catch (NumberFormatException ignored) {}
                }
                if (count > 0) {
                    db.add(new Transaction(tid,
                        Arrays.copyOf(items, count),
                        Arrays.copyOf(qtys,  count),
                        Arrays.copyOf(probs, count)));
                }
                tid++;
            }
        }
        return db;
    }

    public static ProfitTable readProfitTable(String path) throws IOException {
        Map<Integer, Double> profits = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                try {
                    profits.put(Integer.parseInt(parts[0]), Double.parseDouble(parts[1]));
                } catch (NumberFormatException ignored) {}
            }
        }
        return ProfitTable.of(profits);
    }
}
