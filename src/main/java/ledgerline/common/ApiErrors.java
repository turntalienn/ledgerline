package ledgerline.common;

import ledgerline.idempotency.IdempotencyKeyReusedException;
import ledgerline.ledger.ContentionException;
import ledgerline.ledger.InsufficientFundsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Domain exceptions to RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiErrors {

  @ExceptionHandler(NotFoundException.class)
  ProblemDetail notFound(NotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, "not_found", e);
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  ProblemDetail keyReused(IdempotencyKeyReusedException e) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused", e);
  }

  @ExceptionHandler(InsufficientFundsException.class)
  ProblemDetail insufficientFunds(InsufficientFundsException e) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient_funds", e);
  }

  /** 409: the request lost too many races; retrying with the same Idempotency-Key is safe. */
  @ExceptionHandler(ContentionException.class)
  ProblemDetail contention(ContentionException e) {
    return problem(HttpStatus.CONFLICT, "contention", e);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    return problem(HttpStatus.BAD_REQUEST, "bad_request", e);
  }

  private static ProblemDetail problem(HttpStatus status, String code, Exception e) {
    ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
    p.setTitle(code);
    return p;
  }
}
