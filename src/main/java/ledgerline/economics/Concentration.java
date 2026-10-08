package ledgerline.economics;

import java.util.Arrays;

/**
 * How unequally money is held across accounts.
 *
 * Gini: 0 = everyone holds the same, (n-1)/n = one account holds everything.
 *   With balances sorted ascending x_1..x_n:  G = 2 * sum(i * x_i) / (n * sum(x)) - (n + 1) / n
 * HHI (Herfindahl-Hirschman): sum of squared shares, from 1/n (equal) to 1 (one holder).
 *   Antitrust convention multiplies by 10,000; above 2,500 counts as "highly concentrated".
 *
 * Negative balances are treated as 0; USER accounts cannot go negative anyway.
 */
public record Concentration(int holders, long total, double gini, double hhi) {

    public static Concentration of(long[] balances) {
        long[] xs = Arrays.stream(balances).map(b -> Math.max(0, b)).sorted().toArray();
        int n = xs.length;
        long total = Arrays.stream(xs).sum();
        if (n == 0 || total == 0) {
            return new Concentration(n, total, 0, 0);
        }
        double weighted = 0;   // doubles: i * x_i overflows a long for big ledgers
        double hhi = 0;
        for (int i = 0; i < n; i++) {
            weighted += (i + 1) * (double) xs[i];
            double share = xs[i] / (double) total;
            hhi += share * share;
        }
        double gini = (2 * weighted) / (n * (double) total) - (n + 1.0) / n;
        return new Concentration(n, total, gini, hhi);
    }

    public double hhiPoints() {
        return hhi * 10_000;
    }
}
