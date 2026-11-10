package ledgerline.payment;

import java.time.Instant;
import java.util.UUID;

public record Payment(
    long id,
    String idempotencyKey,
    String requestHash,
    long accountId,
    long amount,
    PaymentStatus status,
    UUID pspReference,
    int attempts,
    String lastError,
    Long journalEntryId,
    Instant createdAt,
    Instant updatedAt) {}
