package ledgerline.mockpsp;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Sends the PSP's async notifications: late, sometimes twice, sometimes never. */
@Component
@ConditionalOnProperty(name = "ledgerline.mock-psp.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookSender {

    record Webhook(UUID reference, String status) {}

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final MockPspFaults faults;
    private final Environment env;
    private final RestClient.Builder builder;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "mock-psp-webhooks");
        t.setDaemon(true);
        return t;
    });
    private volatile RestClient client;

    public WebhookSender(MockPspFaults faults, Environment env, RestClient.Builder builder) {
        this.faults = faults;
        this.env = env;
        this.builder = builder;
    }

    void schedule(UUID reference, String status) {
        MockPspProperties.Faults f = faults.get();
        if (faults.roll(f.webhookDropRate())) return;
        int copies = faults.roll(f.webhookDuplicateRate()) ? 2 : 1;
        for (int i = 0; i < copies; i++) {
            executor.schedule(() -> send(reference, status), ThreadLocalRandom.current().nextLong(50, 300), TimeUnit.MILLISECONDS);
        }
    }

    private void send(UUID reference, String status) {
        try {
            client().post().body(new Webhook(reference, status)).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.debug("Webhook for {} not delivered: {}", reference, e.getMessage());
        }
    }

    private RestClient client() {
        if (client == null) {
            client = builder.clone().baseUrl(env.getRequiredProperty("ledgerline.mock-psp.webhook-url")).build();
        }
        return client;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
