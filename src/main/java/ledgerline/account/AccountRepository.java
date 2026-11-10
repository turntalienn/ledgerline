package ledgerline.account;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

  /**
   * Hibernate 6 renders this as SELECT ... ORDER BY id FOR NO KEY UPDATE on Postgres. Postgres
   * sorts first and locks rows as they come out of the sort, so every transaction takes its locks
   * lowest id first and two transfers A->B and B->A can never wait on each other in a cycle. NO KEY
   * UPDATE (not plain FOR UPDATE) still blocks other writers of the row, but not the FOR KEY SHARE
   * lock that inserting a posting takes on its account foreign key.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from Account a where a.id in :ids order by a.id")
  List<Account> lockAllInIdOrder(@Param("ids") Collection<Long> ids);

  @Query(
      "select new ledgerline.account.AccountRef(a.id, a.currency, a.type) from Account a where a.id in :ids")
  List<AccountRef> findRefs(@Param("ids") Collection<Long> ids);

  Optional<Account> findByTypeAndName(AccountType type, String name);
}
