package ledgerline.fees;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerline.fees")
public record FeeProperties(boolean enabled, List<FeeArm> arms) {

    public FeeProperties {
        arms = arms == null ? List.of() : List.copyOf(arms);
    }
}
