package ledgerline.fees;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("ledgerline.fees")
public record FeeProperties(boolean enabled, List<FeeArm> arms) {

    public FeeProperties {
        arms = arms == null ? List.of() : List.copyOf(arms);
    }
}
