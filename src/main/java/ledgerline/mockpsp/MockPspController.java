package ledgerline.mockpsp;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * A fake payment provider served by the same app under /mock-psp, so the whole failure lab runs
 * with one process. The ledger only talks to it over HTTP (PspGateway), as it would to a real PSP.
 */
@RestController
@RequestMapping("/mock-psp")
@ConditionalOnProperty(name = "ledgerline.mock-psp.enabled", havingValue = "true", matchIfMissing = true)
public class MockPspController {

    public record ChargeRequest(UUID reference, long amount) {}
    public record ChargeResponse(UUID reference, String status) {}

    private final MockPspBooks books;
    private final MockPspFaults faults;
    private final WebhookSender webhooks;

    public MockPspController(MockPspBooks books, MockPspFaults faults, WebhookSender webhooks) {
        this.books = books;
        this.faults = faults;
        this.webhooks = webhooks;
    }

    @PostMapping("/charges")
    public ResponseEntity<ChargeResponse> charge(@RequestBody ChargeRequest req) {
        MockPspProperties.Faults f = faults.get();
        pause(f.latency());

        // Idempotency on the PSP side: a known reference gets the original answer and no new charge.
        Optional<MockPspBooks.Charge> existing = books.find(req.reference());
        if (existing.isPresent()) {
            return respond(existing.get());
        }

        if (faults.roll(f.failureRate())) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build(); // nothing charged
        }

        String status = faults.roll(f.declineRate()) ? "DECLINED" : "SUCCEEDED";
        long settled = faults.roll(f.settlementSkewRate()) ? req.amount() - Math.max(1, req.amount() / 100) : req.amount();
        MockPspBooks.Charge charge = books.chargeOnce(req.reference(), req.amount(), status, settled);
        webhooks.schedule(charge.reference(), charge.status());

        if (faults.roll(f.timeoutRate())) {
            pause(f.hang()); // the money moved, but the caller stops waiting before we answer
        }
        return respond(charge);
    }

    @GetMapping("/charges/{reference}")
    public ResponseEntity<ChargeResponse> get(@PathVariable UUID reference) {
        return books.find(reference).map(this::respond).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/settlements/{date}", produces = "text/csv")
    public String settlement(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return books.settlementCsv(date);
    }

    @GetMapping("/faults")
    public MockPspProperties.Faults getFaults() {
        return faults.get();
    }

    @PutMapping("/faults")
    public MockPspProperties.Faults setFaults(@RequestBody MockPspProperties.Faults next) {
        faults.set(next);
        return next;
    }

    private ResponseEntity<ChargeResponse> respond(MockPspBooks.Charge charge) {
        HttpStatus status = "SUCCEEDED".equals(charge.status()) ? HttpStatus.OK : HttpStatus.PAYMENT_REQUIRED;
        return ResponseEntity.status(status).body(new ChargeResponse(charge.reference(), charge.status()));
    }

    private static void pause(Duration d) {
        if (d == null || d.isZero()) return;
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
