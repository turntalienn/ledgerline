package ledgerline.payment;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP client for the PSP, wrapped as Retry(CircuitBreaker(call)).
 *
 * Retry only what is safe and worth retrying: I/O errors, timeouts and 5xx. Every retry sends the
 * SAME psp_reference, which the PSP treats as an idempotency key, so a request that timed out
 * after the PSP charged gets back the existing charge instead of creating a second one.
 * A 4xx (decline) is an answer, not a failure: never retried, and not counted against the breaker.
 *
 * The breaker sits inside the retry, so it sees every attempt. When the PSP is clearly down it
 * opens and calls fail immediately (CallNotPermittedException, not retried): request threads stop
 * piling up behind a dead dependency, and payments stay PENDING for the resolver to finish later.
 */
@Component
public class PspGateway {

    public sealed interface ChargeOutcome permits Charged, Declined, Unknown {}
    /** The PSP confirmed the charge. */
    public record Charged() implements ChargeOutcome {}
    /** The PSP definitively refused it. */
    public record Declined(String reason) implements ChargeOutcome {}
    /** We do not know whether the PSP charged. The payment stays PENDING. */
    public record Unknown(String reason) implements ChargeOutcome {}

    record ChargeRequest(UUID reference, long amount) {}
    record ChargeResponse(UUID reference, String status) {}

    private static final Logger log = LoggerFactory.getLogger(PspGateway.class);

    private final Environment env;
    private final RestClient.Builder builder;
    private final PspProperties props;
    private final Retry retry;
    private final CircuitBreaker breaker;
    private volatile RestClient client;

    public PspGateway(Environment env, RestClient.Builder builder, PspProperties props) {
        this.env = env;
        this.builder = builder;
        this.props = props;

        this.retry = Retry.of("psp", RetryConfig.custom()
                .maxAttempts(props.retry().maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        props.retry().initialBackoff(), props.retry().multiplier(), 0.5))
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());

        this.breaker = CircuitBreaker.of("psp", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(props.circuitBreaker().slidingWindowSize())
                .minimumNumberOfCalls(props.circuitBreaker().minimumCalls())
                .failureRateThreshold(props.circuitBreaker().failureRateThreshold())
                .waitDurationInOpenState(props.circuitBreaker().openDuration())
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
        breaker.getEventPublisher().onStateTransition(e -> log.warn("PSP circuit breaker: {}", e.getStateTransition()));
    }

    public ChargeOutcome charge(UUID reference, long amount) {
        Supplier<ChargeResponse> call = () -> client().post().uri("/charges")
                .body(new ChargeRequest(reference, amount))
                .retrieve()
                .body(ChargeResponse.class);
        try {
            ChargeResponse response = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, call)).get();
            return response != null && "SUCCEEDED".equals(response.status())
                    ? new Charged()
                    : new Declined(response == null ? "empty response" : response.status());
        } catch (HttpClientErrorException e) {
            return new Declined(e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        } catch (CallNotPermittedException e) {
            return new Unknown("PSP circuit breaker is open");
        } catch (RestClientException e) {
            return new Unknown(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** The PSP's settlement report for one day, as CSV: reference,amount,status */
    public String settlementCsv(LocalDate date) {
        return client().get().uri("/settlements/{date}", date).retrieve().body(String.class);
    }

    CircuitBreaker circuitBreaker() {
        return breaker;
    }

    private RestClient client() {
        RestClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    HttpClient http = HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(props.connectTimeout())
                            .build();
                    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
                    factory.setReadTimeout(props.readTimeout());
                    client = builder.clone()
                            .baseUrl(env.getRequiredProperty("ledgerline.psp.base-url"))
                            .requestFactory(factory)
                            .build();
                }
                c = client;
            }
        }
        return c;
    }
}
