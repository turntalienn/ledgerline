package ledgerline.fees;

/**
 * @param arm the experiment arm that priced it, or null when fees are off
 */
public record FeeQuote(long amount, long fee, String arm) {

  public static FeeQuote free(long amount) {
    return new FeeQuote(amount, 0, null);
  }

  /** What leaves the payer's account. */
  public long total() {
    return Math.addExact(amount, fee);
  }
}
