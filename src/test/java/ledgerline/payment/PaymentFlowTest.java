package ledgerline.payment;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import ledgerline.account.Account;
import ledgerline.account.SystemAccounts;
import ledgerline.idempotency.IdempotencyKeyReusedException;
import ledgerline.mockpsp.MockPspBooks;
import ledgerline.mockpsp.MockPspFaults;
import ledgerline.mockpsp.MockPspProperties.Faults;
import ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pay-ins against the mock PSP with failures injected on purpose. */
class PaymentFlowTest extends IntegrationTest {

    @Autowired PaymentService payments;
    @Autowired PaymentProcessor processor;
    @Autowired PspGateway gateway;
    @Autowired MockPspFaults faults;
    @Autowired MockPspBooks pspBooks;
    @Autowired SystemAccounts systemAccounts;

    @AfterEach
    void calmDown() {
        faults.set(Faults.none());
        gateway.circuitBreaker().reset();
    }

    @Test
    void happyPathCreditsTheCustomerOnce() {
        Account customer = user("customer");
        long clearingBefore = balances.journalBalance(systemAccounts.pspClearing());

        PaymentService.Outcome outcome = payments.create(key(), customer.getId(), 5_000);

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(balances.journalBalance(customer.getId())).isEqualTo(5_000);
        assertThat(balances.journalBalance(systemAccounts.pspClearing())).isEqualTo(clearingBefore - 5_000);
        assertThat(journalEntriesWithReference("payment:" + outcome.payment().id())).isEqualTo(1);
    }

    @Test
    void sameKeyReturnsTheSamePaymentAndADifferentBodyIsRejected() {
        Account customer = user("customer");
        String k = key();

        Payment first = payments.create(k, customer.getId(), 1_000).payment();
        PaymentService.Outcome again = payments.create(k, customer.getId(), 1_000);

        assertThat(again.replayed()).isTrue();
        assertThat(again.payment().id()).isEqualTo(first.id());
        assertThatThrownBy(() -> payments.create(k, customer.getId(), 2_000)).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(balances.journalBalance(customer.getId())).isEqualTo(1_000);
    }

    @Test
    void timeoutAfterTheChargeIsRetriedWithoutChargingTwice() {
        // The PSP books the charge, then stalls past our read timeout. No webhook to rescue us.
        faults.set(new Faults(Duration.ZERO, Duration.ofMillis(600), 0, 1.0, 0, 1.0, 0, 0));
        Account customer = user("customer");

        Payment payment = payments.create(key(), customer.getId(), 7_000).payment();

        // Resilience4j retried with the same psp_reference; the PSP answered with the existing charge.
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(pspBooks.find(payment.pspReference())).isPresent();
        assertThat(journalEntriesWithReference("payment:" + payment.id())).isEqualTo(1);
        assertThat(balances.journalBalance(customer.getId())).isEqualTo(7_000);
    }

    @Test
    void pspOutageLeavesThePaymentPendingUntilTheResolverFinishesIt() {
        faults.set(new Faults(Duration.ZERO, Duration.ofMillis(600), 1.0, 0, 0, 0, 0, 0));
        Account customer = user("customer");

        Payment pending = payments.create(key(), customer.getId(), 3_000).payment();
        assertThat(pending.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(pending.lastError()).contains("503");
        assertThat(balances.journalBalance(customer.getId())).isZero();

        faults.set(Faults.none()); // PSP recovers
        processor.resolvePending(Duration.ZERO, 1_000);

        assertThat(processor.get(pending.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(balances.journalBalance(customer.getId())).isEqualTo(3_000);
    }

    @Test
    void declinedPaymentFailsAndPostsNothing() {
        faults.set(new Faults(Duration.ZERO, Duration.ofMillis(600), 0, 0, 1.0, 0, 0, 0));
        Account customer = user("customer");

        Payment payment = payments.create(key(), customer.getId(), 2_000).payment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.lastError()).contains("declined");
        assertThat(payment.journalEntryId()).isNull();
        assertThat(journalEntriesWithReference("payment:" + payment.id())).isZero();
    }

    @Test
    void duplicateConcurrentWebhooksSettleExactlyOnce() throws Exception {
        faults.set(new Faults(Duration.ZERO, Duration.ofMillis(600), 1.0, 0, 0, 0, 0, 0));
        Account customer = user("customer");
        Payment pending = payments.create(key(), customer.getId(), 4_000).payment();
        faults.set(Faults.none());

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Payment>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return processor.onPspNotification(pending.pspReference(), "SUCCEEDED");
                }));
            }
            start.countDown();
            for (Future<Payment> f : results) assertThat(f.get(30, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        }

        assertThat(journalEntriesWithReference("payment:" + pending.id())).isEqualTo(1);
        assertThat(balances.journalBalance(customer.getId())).isEqualTo(4_000);
    }

    @Test
    void circuitBreakerOpensWhenThePspKeepsFailing() {
        faults.set(new Faults(Duration.ZERO, Duration.ofMillis(600), 1.0, 0, 0, 0, 0, 0));

        List<PspGateway.ChargeOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            outcomes.add(gateway.charge(UUID.randomUUID(), 100));
        }

        assertThat(gateway.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(outcomes.getLast()).isEqualTo(new PspGateway.Unknown("PSP circuit breaker is open"));
    }
}
