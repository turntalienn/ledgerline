package ledgerline.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ledgerline.fees.FeeQuote;
import ledgerline.fees.FeeSchedule;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transfers")
@Validated
public class TransferController {

  private final TransferService service;
  private final FeeSchedule fees;

  public TransferController(TransferService service, FeeSchedule fees) {
    this.service = service;
    this.fees = fees;
  }

  public record TransferRequest(
      @NotNull Long fromAccountId, @NotNull Long toAccountId, @Positive long amount) {}

  /**
   * A replay returns the original status and body, like Stripe, plus Idempotent-Replayed: true so
   * clients and k6 can tell the two apart.
   */
  @PostMapping
  public ResponseEntity<TransferService.TransferResult> create(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody TransferRequest req) {
    TransferService.Outcome outcome =
        service.transfer(idempotencyKey, req.fromAccountId(), req.toAccountId(), req.amount());
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (outcome.replayed()) {
      response.header("Idempotent-Replayed", "true");
    }
    return response.body(outcome.result());
  }

  /**
   * What this payer would be charged. The fee experiment's k6 script calls this before deciding to
   * pay.
   */
  @GetMapping("/quote")
  public FeeQuote quote(@RequestParam Long fromAccountId, @RequestParam @Positive long amount) {
    return fees.quoteShown(fromAccountId, amount);
  }
}
