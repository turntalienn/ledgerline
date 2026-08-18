package ledgerline.ledger;

import jakarta.persistence.*;

@Entity
@Table(name = "posting")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id")
    private JournalEntry entry;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private long amount;

    protected Posting() {}

    Posting(JournalEntry entry, Long accountId, long amount) {
        if (amount == 0) throw new IllegalArgumentException("Posting amount cannot be zero");
        this.entry = entry;
        this.accountId = accountId;
        this.amount = amount;
    }

    public Long getAccountId() { return accountId; }
    public long getAmount() { return amount; }
}
