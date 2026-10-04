package ledgerline.recon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/** Every night, reconcile the previous business day. */
@Component
@ConditionalOnProperty(name = "ledgerline.recon.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class ReconScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconScheduler.class);

    private final ReconciliationService recon;

    public ReconScheduler(ReconciliationService recon) {
        this.recon = recon;
    }

    @Scheduled(cron = "${ledgerline.recon.cron:0 30 0 * * *}", zone = "UTC")
    public void yesterday() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        ReconciliationService.Report report = recon.run(date);
        log.info("Reconciliation {} for {}: {} matched, {} mismatches",
                report.runId(), date, report.matched(), report.mismatches().size());
    }
}
