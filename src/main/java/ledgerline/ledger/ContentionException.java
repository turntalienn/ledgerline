package ledgerline.ledger;

/**
 * Retries ran out while competing for the same rows. The client may retry with the same
 * Idempotency-Key.
 */
public class ContentionException extends RuntimeException {
  public ContentionException(String operation, int attempts, Throwable cause) {
    super(operation + " gave up after " + attempts + " attempts under contention", cause);
  }
}
