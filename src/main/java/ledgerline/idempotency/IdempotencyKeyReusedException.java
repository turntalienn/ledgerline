package ledgerline.idempotency;

/** Same Idempotency-Key, different request body. Almost always a client bug, so we refuse (422). */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request");
    }
}
