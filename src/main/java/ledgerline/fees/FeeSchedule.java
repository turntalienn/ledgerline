package ledgerline.fees;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fee-pricing experiment. Each PAYER is assigned to one arm for good (a between-subjects design),
 * so a payer always sees the same prices and the arms can be compared on volume and revenue.
 * Quotes shown are counted per arm, which gives the conversion rate (transfers / quotes) that the
 * economics report divides by.
 */
@Component
public class FeeSchedule {

    private final FeeProperties props;
    private final MeterRegistry meters;

    public FeeSchedule(FeeProperties props, MeterRegistry meters) {
        this.props = props;
        this.meters = meters;
    }

    public boolean enabled() {
        return props.enabled() && !props.arms().isEmpty();
    }

    public List<FeeArm> arms() {
        return props.arms();
    }

    public FeeQuote quote(long payerAccountId, long amount) {
        if (!enabled()) return FeeQuote.free(amount);
        FeeArm arm = armFor(payerAccountId);
        return new FeeQuote(amount, arm.feeFor(amount), arm.name());
    }

    /** A quote a customer actually saw (GET /transfers/quote). Counts toward the conversion denominator. */
    public FeeQuote quoteShown(long payerAccountId, long amount) {
        FeeQuote quote = quote(payerAccountId, amount);
        if (quote.arm() != null) {
            meters.counter("ledgerline.fees.quotes", "arm", quote.arm()).increment();
        }
        return quote;
    }

    FeeArm armFor(long payerAccountId) {
        // Multiply by the 64-bit golden-ratio constant (Fibonacci hashing) so sequential ids
        // spread evenly over the arms instead of alternating in lockstep with creation order.
        long mixed = payerAccountId * 0x9E3779B97F4A7C15L;
        return props.arms().get(Math.floorMod(Long.hashCode(mixed), props.arms().size()));
    }
}
