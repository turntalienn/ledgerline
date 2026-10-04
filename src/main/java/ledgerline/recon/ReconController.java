package ledgerline.recon;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

@RestController
@RequestMapping("/reconciliation/runs")
public class ReconController {

    private final ReconciliationService recon;

    public ReconController(ReconciliationService recon) {
        this.recon = recon;
    }

    /** Run now. date defaults to today (UTC), handy after a k6 run. */
    @PostMapping
    public ReconciliationService.Report run(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return recon.run(date == null ? LocalDate.now(ZoneOffset.UTC) : date);
    }

    @GetMapping("/{id}")
    public ReconciliationService.Report get(@PathVariable long id) {
        return recon.load(id);
    }

    /** The mismatch report as a file, for whoever works through it. */
    @GetMapping(value = "/{id}/mismatches.csv", produces = "text/csv")
    public String csv(@PathVariable long id) {
        StringBuilder out = new StringBuilder("kind,psp_reference,payment_id,account_id,ledger_amount,psp_amount\n");
        for (ReconciliationService.Mismatch m : recon.load(id).mismatches()) {
            out.append(m.kind()).append(',')
               .append(Objects.toString(m.pspReference(), "")).append(',')
               .append(Objects.toString(m.paymentId(), "")).append(',')
               .append(Objects.toString(m.accountId(), "")).append(',')
               .append(Objects.toString(m.ledgerAmount(), "")).append(',')
               .append(Objects.toString(m.pspAmount(), "")).append('\n');
        }
        return out.toString();
    }
}
