package ledgerline.fees;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeeScheduleTest {

    @Test
    void armArithmetic() {
        assertThat(new FeeArm("flat", 500, 0, 0).feeFor(10_000)).isEqualTo(500);
        assertThat(new FeeArm("pct", 0, 50, 0).feeFor(10_000)).isEqualTo(50);
        assertThat(new FeeArm("pct", 0, 50, 0).feeFor(12_345)).isEqualTo(62);      // 61.725 rounds half up
        assertThat(new FeeArm("capped", 200, 20, 2_500).feeFor(10_000_000)).isEqualTo(2_500);
    }

    @Test
    void disabledMeansFree() {
        FeeSchedule fees = new FeeSchedule(new FeeProperties(false, List.of(new FeeArm("flat", 500, 0, 0))), new SimpleMeterRegistry());
        assertThat(fees.quote(1, 10_000)).isEqualTo(FeeQuote.free(10_000));
    }

    @Test
    void payersAreSpreadEvenlyAndStayInTheirArm() {
        List<FeeArm> arms = List.of(new FeeArm("a", 100, 0, 0), new FeeArm("b", 0, 10, 0), new FeeArm("c", 50, 5, 0));
        FeeSchedule fees = new FeeSchedule(new FeeProperties(true, arms), new SimpleMeterRegistry());

        Map<String, Integer> counts = new HashMap<>();
        for (long payer = 1; payer <= 3_000; payer++) {
            counts.merge(fees.armFor(payer).name(), 1, Integer::sum);
            assertThat(fees.armFor(payer)).isSameAs(fees.armFor(payer));
        }
        assertThat(counts.values()).allSatisfy(n -> assertThat(n).isBetween(900, 1_100));
    }
}
