package ledgerline.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Client-side settings for the PSP. The base URL is NOT here: it may contain ${local.server.port},
 * which only exists once the web server has started, so PspGateway reads it lazily.
 */
@ConfigurationProperties("ledgerline.psp")
public record PspProperties(@DefaultValue("500ms") Duration connectTimeout,
                            @DefaultValue("1s") Duration readTimeout,
                            @DefaultValue Retry retry,
                            @DefaultValue Breaker circuitBreaker) {

    public record Retry(@DefaultValue("4") int maxAttempts,
                        @DefaultValue("100ms") Duration initialBackoff,
                        @DefaultValue("2.0") double multiplier) {}

    public record Breaker(@DefaultValue("50") float failureRateThreshold,
                          @DefaultValue("20") int slidingWindowSize,
                          @DefaultValue("10") int minimumCalls,
                          @DefaultValue("10s") Duration openDuration) {}
}
