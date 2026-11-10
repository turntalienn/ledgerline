package ledgerline.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"test", "perflens"})
class PerfLensIntegrationTest extends IntegrationTest {
    @Autowired TestRestTemplate http;
    @Test
    void realHttpTransferStoresJdbcAndHibernateQueryEvidence() {
        var from = funded("profile-from", 1000); var to = user("profile-to");
        jdbc.update("insert into perflens_control (target_key, enabled) values ('POST /transfers', true) on conflict (target_key) do update set enabled=true");
        try {
            var headers = new HttpHeaders(); headers.set("Idempotency-Key", key());
            var response = http.postForEntity("/transfers", new HttpEntity<>(Map.of("fromAccountId", from.getId(), "toAccountId", to.getId(), "amount", 100), headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("select max(query_count) from perflens_trace where target_key='POST /transfers'", Integer.class)).isGreaterThan(0);
                assertThat(jdbc.queryForObject("select count(*) from perflens_sql_event where sql_text like '%idempotency_key%'", Integer.class)).isGreaterThan(0);
                assertThat(jdbc.queryForObject("select count(*) from perflens_sql_event where sql_text like '%journal_entry%'", Integer.class)).isGreaterThan(0);
            });
        } finally { jdbc.update("update perflens_control set enabled=false where target_key='POST /transfers'"); }
    }
}
