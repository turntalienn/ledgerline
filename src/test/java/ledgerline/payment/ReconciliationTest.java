package ledgerline.payment;

import ledgerline.account.Account;
import ledgerline.mockpsp.MockPspBooks;
import ledgerline.mockpsp.MockPspFaults;
import ledgerline.mockpsp.MockPspProperties.Faults;
import ledgerline.recon.ReconciliationService;
import ledgerline.recon.ReconciliationService.Kind;
import ledgerline.recon.ReconciliationService.Mismatch;
import ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationTest extends IntegrationTest {

    @Autowired PaymentService payments;
    @Autowired PaymentProcessor processor;
    @Autowired ReconciliationService recon;
    @Autowired MockPspFaults faults;
    @Autowired MockPspBooks pspBooks;

    @AfterEach
    void calmDown() {
        faults.set(Faults.none());
    }

    @Test
    void findsEveryKindOfMismatch() {
        Account customer = user("recon");

        Payment matched = payments.create(key(), customer.getId(), 1_000).payment();

        // The PSP quietly kept 1%: settlement says 1,980 for a 2,000 charge.
        Payment skewed = payments.create(key(), customer.getId(), 2_000).payment();
        jdbc.update("update mock_psp.charge set settled_amount = 1980 where reference = ?", skewed.pspReference());

        // We credited the customer, but the PSP has no such charge.
        Payment missingAtPsp = payments.create(key(), customer.getId(), 3_000).payment();
        jdbc.update("delete from mock_psp.charge where reference = ?", missingAtPsp.pspReference());

        // The worst one: we gave up on a payment the PSP did end up charging.
        faults.set(new Faults(Duration.ZERO, Duration.ofMillis(600), 1.0, 0, 0, 0, 0, 0));
        Payment gaveUp = payments.create(key(), customer.getId(), 4_000).payment();
        faults.set(Faults.none());
        processor.settleFailed(gaveUp.id(), "gave up");
        pspBooks.chargeOnce(gaveUp.pspReference(), 4_000, "SUCCEEDED", 4_000);

        // A cached balance that no longer matches the journal.
        Account drifted = user("drift");
        jdbc.update("update account set balance = balance + 7 where id = ?", drifted.getId());

        ReconciliationService.Report report = recon.run(LocalDate.now(ZoneOffset.UTC));

        assertThat(report.mismatches())
                .contains(new Mismatch(Kind.AMOUNT_MISMATCH, skewed.pspReference(), skewed.id(), null, 2_000L, 1_980L))
                .contains(new Mismatch(Kind.MISSING_AT_PSP, missingAtPsp.pspReference(), missingAtPsp.id(), null, 3_000L, null))
                .contains(new Mismatch(Kind.MISSING_IN_LEDGER, gaveUp.pspReference(), gaveUp.id(), null, null, 4_000L))
                .contains(new Mismatch(Kind.BALANCE_DRIFT, null, null, drifted.getId(), 7L, 0L));
        assertThat(report.mismatches()).extracting(Mismatch::pspReference).doesNotContain(matched.pspReference());
        assertThat(recon.load(report.runId()).mismatches()).hasSameSizeAs(report.mismatches());
    }
}
