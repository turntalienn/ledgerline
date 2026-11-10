package ledgerline.mockpsp;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** The PSP's own record of charges (schema mock_psp). The ledger never reads this table. */
@Component
public class MockPspBooks {

    public record Charge(UUID reference, long amount, String status, long settledAmount) {}

    private static final RowMapper<Charge> ROW = (rs, i) -> new Charge(
            rs.getObject("reference", UUID.class), rs.getLong("amount"), rs.getString("status"), rs.getLong("settled_amount"));

    private final JdbcTemplate jdbc;

    public MockPspBooks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Charge> find(UUID reference) {
        return jdbc.query("select * from mock_psp.charge where reference = ?", ROW, reference).stream().findFirst();
    }

    /** Charge once per reference. If two calls race, the loser gets the winner's charge back. */
    public Charge chargeOnce(UUID reference, long amount, String status, long settledAmount) {
        jdbc.update("""
                insert into mock_psp.charge (reference, amount, status, settled_amount) values (?, ?, ?, ?)
                on conflict (reference) do nothing""", reference, amount, status, settledAmount);
        return find(reference).orElseThrow();
    }

    /** The day's settled charges, the way a PSP's settlement file reports them. */
    public String settlementCsv(LocalDate date) {
        StringBuilder csv = new StringBuilder("reference,amount,status\n");
        jdbc.query("""
                select reference, settled_amount, status from mock_psp.charge
                where status = 'SUCCEEDED' and created_at >= ? and created_at < ?
                order by created_at""",
                rs -> {
                    csv.append(rs.getObject(1, UUID.class)).append(',').append(rs.getLong(2)).append(',').append(rs.getString(3)).append('\n');
                },
                Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        return csv.toString();
    }
}
