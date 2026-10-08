package ledgerline.economics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/** from/to are ISO-8601 instants. Default window: the last 24 hours. */
@RestController
@RequestMapping("/economics")
public class EconomicsController {

    private final EconomicsService economics;

    public EconomicsController(EconomicsService economics) {
        this.economics = economics;
    }

    @GetMapping("/summary")
    public EconomicsService.Summary summary(@RequestParam(required = false) Instant from,
                                            @RequestParam(required = false) Instant to) {
        Instant end = to == null ? Instant.now() : to;
        return economics.summary(from == null ? end.minus(Duration.ofDays(1)) : from, end);
    }

    @GetMapping("/fee-experiment")
    public EconomicsService.FeeExperiment feeExperiment(@RequestParam(required = false) Instant from,
                                                        @RequestParam(required = false) Instant to) {
        Instant end = to == null ? Instant.now() : to;
        return economics.feeExperiment(from == null ? end.minus(Duration.ofDays(1)) : from, end);
    }
}
