package ledgerline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling // outbox relay, payment resolver, daily reconciliation; each can be switched off by property
public class LedgerlineApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerlineApplication.class, args);
    }
}
