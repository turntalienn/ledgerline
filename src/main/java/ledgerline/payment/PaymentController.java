package ledgerline.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {

    public record PaymentRequest(@NotNull Long accountId, @Positive long amount) {}

    public record PspWebhook(@NotNull UUID reference, @NotBlank String status) {}

    public record PaymentView(long id, long accountId, long amount, PaymentStatus status, UUID pspReference,
                              int attempts, String lastError, Long journalEntryId, Instant createdAt) {
        static PaymentView of(Payment p) {
            return new PaymentView(p.id(), p.accountId(), p.amount(), p.status(), p.pspReference(),
                    p.attempts(), p.lastError(), p.journalEntryId(), p.createdAt());
        }
    }

    private final PaymentService service;
    private final PaymentProcessor processor;

    public PaymentController(PaymentService service, PaymentProcessor processor) {
        this.service = service;
        this.processor = processor;
    }

    /** 201 when the payment is final, 202 while it is still PENDING (poll GET /payments/{id}). */
    @PostMapping("/payments")
    public ResponseEntity<PaymentView> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @Valid @RequestBody PaymentRequest req) {
        PaymentService.Outcome outcome = service.create(idempotencyKey, req.accountId(), req.amount());
        HttpStatus status = outcome.payment().status() == PaymentStatus.PENDING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(PaymentView.of(outcome.payment()));
    }

    @GetMapping("/payments/{id}")
    public PaymentView get(@PathVariable long id) {
        return PaymentView.of(processor.get(id));
    }

    /**
     * Called by the PSP. Production would verify an HMAC signature header before trusting this;
     * the mock PSP does not sign, so that is left out here.
     */
    @PostMapping("/webhooks/psp")
    public ResponseEntity<Void> webhook(@Valid @RequestBody PspWebhook hook) {
        processor.onPspNotification(hook.reference(), hook.status());
        return ResponseEntity.ok().build();
    }
}
