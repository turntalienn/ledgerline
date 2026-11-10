package ledgerline.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ledgerline.account.Account;
import ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database refuses bad money even when the application code is bypassed
 * (V3__ledger_invariants.sql).
 */
class LedgerInvariantsTest extends IntegrationTest {

  @Autowired TransactionTemplate tx;

  @Test
  void unbalancedEntryIsRejectedAtCommit() {
    Account a = user("a");
    Account b = user("b");
    String reference = "raw:" + key();

    assertThatThrownBy(() ->
            tx.executeWithoutResult(status -> {
              Long id =
                  jdbc.queryForObject(
                      "insert into journal_entry (reference, kind) values (?, 'TRANSFER') returning id",
                      Long.class,
                      reference);
              jdbc.update(
                  "insert into posting (journal_entry_id, account_id, amount) values (?, ?, ?)",
                  id,
                  a.getId(),
                  -500);
              jdbc.update(
                  "insert into posting (journal_entry_id, account_id, amount) values (?, ?, ?)",
                  id,
                  b.getId(),
                  400);
            }))
        .hasStackTraceContaining("unbalanced");

    assertThat(journalEntriesWithReference(reference)).isZero();
  }

  @Test
  void journalIsAppendOnly() {
    Account from = funded("payer", 1_000);
    Account to = user("payee");
    long entryId =
        transfers.transfer(key(), from.getId(), to.getId(), 100).result().journalEntryId();

    assertThatThrownBy(() ->
            jdbc.update(
                "update posting set amount = amount + 1 where journal_entry_id = ?", entryId))
        .hasStackTraceContaining("append-only");
    assertThatThrownBy(() -> jdbc.update("delete from posting where journal_entry_id = ?", entryId))
        .hasStackTraceContaining("append-only");
    assertThatThrownBy(
            () -> jdbc.update("update journal_entry set reference = 'x' where id = ?", entryId))
        .hasStackTraceContaining("append-only");
  }

  @Test
  void userBalanceCannotGoNegativeEvenFromRawSql() {
    Account a = user("raw");
    assertThatThrownBy(() -> jdbc.update("update account set balance = -1 where id = ?", a.getId()))
        .hasStackTraceContaining("account_user_non_negative");
  }
}
