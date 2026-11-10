package ledgerline.economics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ConcentrationTest {

    @Test
    void perfectEquality() {
        Concentration c = Concentration.of(new long[]{100, 100, 100, 100});
        assertThat(c.gini()).isCloseTo(0, within(1e-12));
        assertThat(c.hhi()).isCloseTo(0.25, within(1e-12)); // 1/n
    }

    @Test
    void oneHolderHasEverything() {
        Concentration c = Concentration.of(new long[]{0, 0, 0, 400});
        assertThat(c.gini()).isCloseTo(0.75, within(1e-12)); // (n-1)/n
        assertThat(c.hhi()).isCloseTo(1.0, within(1e-12));
        assertThat(c.hhiPoints()).isCloseTo(10_000, within(1e-9));
    }

    @Test
    void textbookExample() {
        // Shares 10%, 20%, 30%, 40%: HHI = 0.01 + 0.04 + 0.09 + 0.16 = 0.30; Gini = 0.25.
        Concentration c = Concentration.of(new long[]{40, 10, 30, 20});
        assertThat(c.gini()).isCloseTo(0.25, within(1e-12));
        assertThat(c.hhi()).isCloseTo(0.30, within(1e-12));
    }

    @Test
    void emptyAndNegativeInputs() {
        assertThat(Concentration.of(new long[]{}).gini()).isZero();
        assertThat(Concentration.of(new long[]{0, 0}).hhi()).isZero();
        assertThat(Concentration.of(new long[]{-50, 100}).gini()).isCloseTo(0.5, within(1e-12));
    }
}
