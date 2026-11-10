package ledgerline.account;

import ledgerline.common.NotFoundException;

public class AccountNotFoundException extends NotFoundException {
  public AccountNotFoundException(Object id) {
    super("Account not found: " + id);
  }
}
