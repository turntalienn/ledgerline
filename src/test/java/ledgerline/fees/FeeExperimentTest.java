package ledgerline.fees;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import ledgerline.account.Account;
import ledgerline.account.SystemAccounts;
import ledgerline.economics.EconomicsService;
import ledgerline.support.IntegrationTest;
import ledgerline.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ledgerline.fees.enabled=true")
class FeeExperimentTest extends IntegrationTest {

    @Autowired FeeSchedule fees;
    @Autowired SystemAccounts systemAccounts;
    @Autowired EconomicsService economics;

    @Test
    void feeIsAThirdLegAndIsAttributedToThePayersArm() {
        Account payer = funded("payer", 100_000);
        Account payee = user("payee");
        long revenueBefore = balances.journalBalance(systemAccounts.feeRevenue());

        FeeQuote quote = fees.quoteShown(payer.getId(), 10_000);
        Instant from = dbNow();
        TransferService.TransferResult result = transfers.transfer(key(), payer.getId(), payee.getId(), 10_000).result();
        Instant to = dbNow();

        assertThat(quote.fee()).isPositive();
        assertThat(result.fee()).isEqualTo(quote.fee());
        assertThat(result.feeArm()).isEqualTo(quote.arm());
        assertThat(balances.journalBalance(payer.getId())).isEqualTo(100_000 - 10_000 - quote.fee());
        assertThat(balances.journalBalance(payee.getId())).isEqualTo(10_000);
        assertThat(balances.journalBalance(systemAccounts.feeRevenue())).isEqualTo(revenueBefore + quote.fee());

        EconomicsService.ArmResult arm = economics.feeExperiment(from, to).arms().stream()
                .filter(a -> a.arm().equals(quote.arm())).findFirst().orElseThrow();
        assertThat(arm.transfers()).isEqualTo(1);
        assertThat(arm.volume()).isEqualTo(10_000);
        assertThat(arm.feeRevenue()).isEqualTo(quote.fee());
        assertThat(arm.quotes()).isGreaterThanOrEqualTo(1);
    }
}
