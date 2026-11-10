package ledgerline.observability;

import io.perflens.autoconfigure.PerfLensControlService;
import io.perflens.autoconfigure.PerfLensTraceStore;
import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.model.TraceBoundary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Optional bridge to the real PerfLens starter. Its DataSource wrapper sees BOTH Hibernate and
 * JdbcTemplate queries, including repeated transaction attempts. Only explicitly enabled HTTP
 * boundaries are captured, keeping background relay/health traffic out of the experiment.
 */
@Configuration
@ConditionalOnProperty(name = "perflens.enabled", havingValue = "true")
public class PerfLensConfiguration implements WebMvcConfigurer {
  private final PerfLensControlService control;
  private final PerfLensTraceStore store;
  private final Clock clock;

  public PerfLensConfiguration(
      PerfLensControlService control, PerfLensTraceStore store, Clock clock) {
    this.control = control;
    this.store = store;
    this.clock = clock;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              private static final String STARTED = "ledgerline.perflens.started";

              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                String route =
                    String.valueOf(
                        request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
                String target = request.getMethod() + " " + route;
                if (control.isEnabled(target)) {
                  PerfLensTraceContext.startRoot(
                      target,
                      target,
                      TraceBoundary.HTTP_REQUEST,
                      route,
                      request.getMethod(),
                      clock);
                  request.setAttribute(STARTED, true);
                }
                return true;
              }

              public void afterCompletion(
                  HttpServletRequest request,
                  HttpServletResponse response,
                  Object handler,
                  Exception error) {
                if (!Boolean.TRUE.equals(request.getAttribute(STARTED))) return;
                // Close first: report persistence must not count its own SQL or leak ThreadLocal
                // state.
                var report =
                    PerfLensTraceContext.closeRoot(
                        response.getStatus(),
                        error == null ? null : error.getClass().getName(),
                        clock);
                store.save(report);
              }
            })
        .addPathPatterns("/transfers", "/payments", "/accounts/{id}");
  }
}
