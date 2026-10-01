package ledgerline.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically finishes payments left PENDING by PSP outages, timeouts and lost webhooks. */
@Component
@ConditionalOnProperty(name = "ledgerline.payments.resolver.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentResolver {

    private static final Logger log = LoggerFactory.getLogger(PaymentResolver.class);

    private final PaymentProcessor processor;
    private final PaymentsProperties props;

    public PaymentResolver(PaymentProcessor processor, PaymentsProperties props) {
        this.processor = processor;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${ledgerline.payments.resolver.interval-ms:15000}")
    public void run() {
        int n = processor.resolvePending(props.resolver().olderThan(), props.resolver().batchSize());
        if (n > 0) log.info("Resolver retried {} pending payments", n);
    }
}
