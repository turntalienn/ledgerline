package ledgerline.transfer;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ledgerline.balance.strategy=row-lock")
class RowLockTransferTest extends TransferContract {
    @Override
    String expectedStrategy() { return "row-lock"; }
}
