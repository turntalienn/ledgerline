package ledgerline.ledger;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One business event. Immutable once written: corrections are new entries, never updates. */
@Entity
@Table(name = "journal_entry")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EntryKind kind;

    /** Which fee-experiment arm priced this entry, or null when fees are off. */
    @Column(name = "fee_arm")
    private String feeArm;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    private List<Posting> postings = new ArrayList<>();

    protected JournalEntry() {}

    public JournalEntry(String reference, EntryKind kind) {
        this(reference, kind, null);
    }

    public JournalEntry(String reference, EntryKind kind, String feeArm) {
        this.reference = reference;
        this.kind = kind;
        this.feeArm = feeArm;
    }

    public void addPosting(Long accountId, long amount) {
        postings.add(new Posting(this, accountId, amount));
    }

    /** The core invariant of double-entry bookkeeping. V3__ledger_invariants.sql enforces it again at commit. */
    public void assertBalanced() {
        if (postings.size() < 2) {
            throw new IllegalStateException("A journal entry needs at least two postings");
        }
        long sum = postings.stream().mapToLong(Posting::getAmount).sum();
        if (sum != 0) {
            throw new IllegalStateException("Postings must sum to zero, got " + sum);
        }
    }

    public Set<Long> accountIds() {
        Set<Long> ids = new LinkedHashSet<>();
        postings.forEach(p -> ids.add(p.getAccountId()));
        return ids;
    }

    public Long getId() { return id; }
    public String getReference() { return reference; }
    public EntryKind getKind() { return kind; }
    public String getFeeArm() { return feeArm; }
    public List<Posting> getPostings() { return postings; }
}
