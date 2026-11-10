package ledgerline.transfer;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ledgerline.balance.strategy=sharded")
class ShardedTransferTest extends TransferContract {
  @Override
  String expectedStrategy() {
    return "sharded";
  }
}
