package ledgerline.mockpsp;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/** Current fault settings. Changeable at runtime (PUT /mock-psp/faults) so chaos can start mid-load-test. */
@Component
public class MockPspFaults {

    private volatile MockPspProperties.Faults current;

    public MockPspFaults(MockPspProperties props) {
        this.current = props.faults();
    }

    public MockPspProperties.Faults get() {
        return current;
    }

    public void set(MockPspProperties.Faults faults) {
        this.current = faults;
    }

    boolean roll(double rate) {
        return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
    }
}
