package ledgerline.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import ledgerline.account.Account;
import ledgerline.idempotency.IdempotencyKeyReusedException;
import ledgerline.ledger.InsufficientFundsException;
import ledgerline.ledger.balance.BalanceStrategy;
import ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What every balance strategy must guarantee. RowLockTransferTest, OptimisticTransferTest and
 * ShardedTransferTest run these same tests with ledgerline.balance.strategy set to each value.
 */
abstract class TransferContract extends IntegrationTest {

  @Autowired BalanceStrategy strategy;

  abstract String expectedStrategy();

  @Test
  void theConfiguredStrategyIsActive() {
    assertThat(strategy.name()).isEqualTo(expectedStrategy());
  }

  @Test
  void fiftyConcurrentDuplicatesApplyExactlyOnce() throws Exception {
    Account from = funded("alice", 100_000);
    Account to = user("bob");
    String sameKey = "dup-" + key();

    List<TransferService.Outcome> outcomes =
        concurrently(50, i -> () -> transfers.transfer(sameKey, from.getId(), to.getId(), 1_000));

    assertThat(outcomes).filteredOn(o -> !o.replayed()).as("requests that actually ran").hasSize(1);
    assertThat(outcomes)
        .extracting(o -> o.result().journalEntryId())
        .as("everyone got the same answer")
        .containsOnly(outcomes.getFirst().result().journalEntryId());
    assertThat(journalEntriesWithReference("transfer:" + sameKey)).isEqualTo(1);
    assertBalance(from, 99_000);
    assertBalance(to, 1_000);
  }

  @Test
  void concurrentDifferentTransfersDontLoseUpdates() throws Exception {
    Account from = funded("carol", 1_000_000);
    Account to = user("dave");

    concurrently(50, i -> () -> transfers.transfer(key(), from.getId(), to.getId(), 100));

    assertBalance(to, 50 * 100);
    assertBalance(from, 1_000_000 - 50 * 100);
  }

  @Test
  void hotMerchantManyPayers() throws Exception {
    Account merchant = user("merchant");
    List<Account> payers = new ArrayList<>();
    for (int i = 0; i < 50; i++) payers.add(funded("payer" + i, 10_000));

    concurrently(
        50, i -> () -> transfers.transfer(key(), payers.get(i).getId(), merchant.getId(), 100));

    assertBalance(merchant, 50 * 100);
    payers.forEach(p -> assertBalance(p, 10_000 - 100));
  }

  @Test
  void oppositeDirectionsDoNotDeadlock() throws Exception {
    Account a = funded("east", 100_000);
    Account b = funded("west", 100_000);

    // Even i: a -> b, odd i: b -> a. Without a fixed lock order this is the classic deadlock.
    concurrently(
        50,
        i ->
            () ->
                i % 2 == 0
                    ? transfers.transfer(key(), a.getId(), b.getId(), 10)
                    : transfers.transfer(key(), b.getId(), a.getId(), 30));

    assertBalance(a, 100_000 - 25 * 10 + 25 * 30);
    assertBalance(b, 100_000 + 25 * 10 - 25 * 30);
  }

  @Test
  void sameKeySameBodyReplaysAndDifferentBodyIsRejected() {
    Account from = funded("erin", 10_000);
    Account to = user("frank");
    String k = key();

    TransferService.Outcome first = transfers.transfer(k, from.getId(), to.getId(), 100);
    TransferService.Outcome again = transfers.transfer(k, from.getId(), to.getId(), 100);

    assertThat(first.replayed()).isFalse();
    assertThat(again.replayed()).isTrue();
    assertThat(again.result()).isEqualTo(first.result());
    assertThatThrownBy(() -> transfers.transfer(k, from.getId(), to.getId(), 200))
        .isInstanceOf(IdempotencyKeyReusedException.class);
    assertBalance(to, 100);
  }

  @Test
  void overdraftIsRejectedAndTheKeyCanBeRetriedLater() {
    Account from = funded("grace", 500);
    Account to = user("heidi");
    String k = key();

    assertThatThrownBy(() -> transfers.transfer(k, from.getId(), to.getId(), 1_000))
        .isInstanceOf(InsufficientFundsException.class);
    assertThat(journalEntriesWithReference("transfer:" + k)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_key where key = ?", Integer.class, k))
        .as("a rejected request does not burn its key")
        .isZero();
    assertBalance(from, 500);

    transfers.transfer(key(), faucet().getId(), from.getId(), 1_000);
    assertThat(transfers.transfer(k, from.getId(), to.getId(), 1_000).replayed()).isFalse();
    assertBalance(from, 500);
    assertBalance(to, 1_000);
  }

  /**
   * Cached balance (what the strategy maintains) and journal balance (source of truth) must both
   * match.
   */
  void assertBalance(Account account, long expected) {
    assertThat(balances.cachedBalance(account.getId()))
        .as("cached balance of %s", account.getName())
        .isEqualTo(expected);
    assertThat(balances.journalBalance(account.getId()))
        .as("journal balance of %s", account.getName())
        .isEqualTo(expected);
  }

  /**
   * Starts n tasks on n threads, releases them at the same instant, and returns their results in
   * order.
   */
  static <T> List<T> concurrently(int n, IntFunction<Callable<T>> task) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
      for (int i = 0; i < n; i++) {
        Callable<T> work = task.apply(i);
        futures.add(
            pool.submit(() -> {
              start.await();
              return work.call();
            }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
      return results;
    }
  }
}
