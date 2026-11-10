package ledgerline.account;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Ids of the SYSTEM accounts seeded by V3__ledger_invariants.sql, looked up once and cached. */
@Component
public class SystemAccounts {

    public static final String PSP_CLEARING = "psp_clearing";
    public static final String FEE_REVENUE = "fee_revenue";

    private final AccountRepository accounts;
    private final Map<String, Long> ids = new ConcurrentHashMap<>();

    public SystemAccounts(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public long pspClearing() { return id(PSP_CLEARING); }
    public long feeRevenue() { return id(FEE_REVENUE); }

    private long id(String name) {
        return ids.computeIfAbsent(name, n -> accounts.findByTypeAndName(AccountType.SYSTEM, n)
                .orElseThrow(() -> new IllegalStateException("Missing system account " + n + "; did Flyway run?"))
                .getId());
    }
}
