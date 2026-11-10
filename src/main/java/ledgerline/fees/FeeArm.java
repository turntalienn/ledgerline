package ledgerline.fees;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One price schedule: fee = min(cap, flat + amount * bps / 10_000), rounded half up. flat only = a
 * flat fee; bps only = a percentage; both with a cap = the usual card-style pricing.
 *
 * @param flat fixed part, in paise
 * @param bps percentage part, in basis points (50 bps = 0.5%)
 * @param cap maximum fee in paise, 0 = no cap
 */
public record FeeArm(
    String name,
    @DefaultValue("0") long flat,
    @DefaultValue("0") int bps,
    @DefaultValue("0") long cap) {

  public long feeFor(long amount) {
    long percentage = (Math.multiplyExact(amount, (long) bps) + 5_000) / 10_000;
    long fee = flat + percentage;
    return cap > 0 ? Math.min(cap, fee) : fee;
  }
}
