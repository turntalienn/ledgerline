package ledgerline.mockpsp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** The webhook URL is read lazily (it may contain ${local.server.port}), so it is not bound here. */
@ConfigurationProperties("ledgerline.mock-psp")
public record MockPspProperties(@DefaultValue("true") boolean enabled, @DefaultValue Faults faults) {

    /**
     * Every rate is a probability from 0 to 1, rolled independently per first-time charge.
     * Replays of a known reference never fail: a real PSP's idempotency works the same way.
     *
     * @param latency              added to every call
     * @param hang                 how long a "timeout" charge stalls before answering (set above the client read timeout)
     * @param failureRate          503 BEFORE charging. Safe to retry.
     * @param timeoutRate          charge, then hang. The client gives up although the money moved: the nasty case.
     * @param declineRate          402, a definitive answer
     * @param webhookDropRate      never send the webhook
     * @param webhookDuplicateRate send the webhook twice
     * @param settlementSkewRate   settlement file reports 1% less than charged (an unexpected PSP fee)
     */
    public record Faults(@DefaultValue("0ms") Duration latency,
                         @DefaultValue("2s") Duration hang,
                         double failureRate,
                         double timeoutRate,
                         double declineRate,
                         double webhookDropRate,
                         double webhookDuplicateRate,
                         double settlementSkewRate) {

        public static Faults none() {
            return new Faults(Duration.ZERO, Duration.ofSeconds(2), 0, 0, 0, 0, 0, 0);
        }
    }
}
