package ledgerline.ledger;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(long accountId) {
        super("Insufficient funds in account " + accountId);
    }
}
