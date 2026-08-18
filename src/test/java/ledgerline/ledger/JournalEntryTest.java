package ledgerline.ledger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    @Test
    void balancedEntryPasses() {
        JournalEntry e = new JournalEntry("t1", EntryKind.TRANSFER);
        e.addPosting(1L, -500);
        e.addPosting(2L, 500);
        assertThatCode(e::assertBalanced).doesNotThrowAnyException();
    }

    @Test
    void threeLeggedEntryWithFeePasses() {
        JournalEntry e = new JournalEntry("t5", EntryKind.TRANSFER, "flat-5");
        e.addPosting(1L, -1_500);
        e.addPosting(2L, 1_000);
        e.addPosting(3L, 500);
        assertThatCode(e::assertBalanced).doesNotThrowAnyException();
    }

    @Test
    void unbalancedEntryIsRejected() {
        JournalEntry e = new JournalEntry("t2", EntryKind.TRANSFER);
        e.addPosting(1L, -500);
        e.addPosting(2L, 400);
        assertThatThrownBy(e::assertBalanced).hasMessageContaining("sum to zero");
    }

    @Test
    void singlePostingIsRejected() {
        JournalEntry e = new JournalEntry("t3", EntryKind.TRANSFER);
        e.addPosting(1L, -500);
        assertThatThrownBy(e::assertBalanced).hasMessageContaining("at least two");
    }

    @Test
    void zeroAmountPostingIsRejected() {
        JournalEntry e = new JournalEntry("t4", EntryKind.TRANSFER);
        assertThatThrownBy(() -> e.addPosting(1L, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
