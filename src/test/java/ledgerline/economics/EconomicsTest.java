package ledgerline.economics;

import ledgerline.account.Account;
import ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EconomicsTest extends IntegrationTest {

  @Autowired EconomicsService economics;

  @Test
  void velocityCountsOnlyUserToUserFlowsInsideTheWindow() {
    Account a = funded("a", 10_000); // faucet funding happens before the window
    Account b = funded("b", 10_000);

    Instant from = dbNow();
    transfers.transfer(key(), a.getId(), b.getId(), 1_000);
    transfers.transfer(key(), b.getId(), a.getId(), 300);
    Instant to = dbNow();

    EconomicsService.Summary s = economics.summary(from, to);

    assertThat(s.p2pTransfers()).isEqualTo(2);
    assertThat(s.p2pVolume()).isEqualTo(1_300);
    assertThat(s.moneyStockEnd())
        .isEqualTo(s.moneyStockStart()); // p2p moves money, it does not create it
    assertThat(s.velocity()).isPositive();
    assertThat(s.gini()).isBetween(0.0, 1.0);
    assertThat(s.hhi()).isBetween(0.0, 1.0);
  }
}
