package ledgerline.payment;

import static org.assertj.core.api.Assertions.assertThat;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.time.Duration;
import ledgerline.mockpsp.MockPspBooks;
import ledgerline.mockpsp.MockPspFaults;
import ledgerline.mockpsp.MockPspProperties.Faults;
import ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.ToxiproxyContainer;

/** Real TCP faults: the provider can commit even when its HTTP response never reaches us. */
class PaymentNetworkFaultTest extends IntegrationTest {
    static final ToxiproxyContainer TOXI = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0");
    static ToxiproxyContainer.ContainerProxy proxy;
    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry r) {
        // PspGateway resolves this lazily, after @BeforeEach has exposed the random server port.
        r.add("ledgerline.psp.base-url", () -> "http://" + TOXI.getHost() + ":" + proxy.getProxyPort() + "/mock-psp");
    }
    @LocalServerPort int port;
    @Autowired PaymentService payments;
    @Autowired PaymentProcessor processor;
    @Autowired PspGateway gateway;
    @Autowired MockPspFaults faults;
    @Autowired MockPspBooks books;

    @BeforeEach
    void connect() {
        if (proxy == null) {
            Testcontainers.exposeHostPorts(port);
            // Host forwarding must exist BEFORE container creation so its host mapping is installed.
            TOXI.start();
            proxy = TOXI.getProxy("host.testcontainers.internal", port);
        }
        // No mock HTTP failures; drop callbacks so only recovery through the proxied path can settle.
        faults.set(new Faults(Duration.ZERO, Duration.ZERO, 0, 0, 0, 1, 0, 0));
        gateway.circuitBreaker().reset();
    }
    @AfterEach
    void restore() throws Exception {
        proxy.setConnectionCut(false);
        for (var toxic : proxy.toxics().getAll()) toxic.remove();
        faults.set(Faults.none());
        gateway.circuitBreaker().reset();
    }
    @AfterAll static void stopProxy() { TOXI.stop(); }

    @Test
    void delayedResponseLeavesARealChargePendingThenRecoversOnce() throws Exception {
        proxy.toxics().latency("slow-response", ToxicDirection.DOWNSTREAM, 1500);
        var customer = user("network-timeout");
        var pending = payments.create(key(), customer.getId(), 700).payment();
        assertThat(pending.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(books.find(pending.pspReference())).isPresent(); // PSP did receive and commit it
        assertThat(balances.journalBalance(customer.getId())).isZero();
        proxy.toxics().get("slow-response").remove();
        processor.resolvePending(Duration.ZERO, 1000);
        assertThat(processor.get(pending.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(journalEntriesWithReference("payment:" + pending.id())).isEqualTo(1);
        assertThat(balances.journalBalance(customer.getId())).isEqualTo(700);
    }

    @Test
    void cutConnectionDoesNotLoseTheDurablePendingPayment() {
        proxy.setConnectionCut(true);
        var customer = user("network-cut");
        var pending = payments.create(key(), customer.getId(), 900).payment();
        assertThat(pending.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(books.find(pending.pspReference())).isEmpty();
        proxy.setConnectionCut(false);
        processor.resolvePending(Duration.ZERO, 1000);
        assertThat(processor.get(pending.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(balances.journalBalance(customer.getId())).isEqualTo(900);
    }
}
