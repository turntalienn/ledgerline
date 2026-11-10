package ledgerline.account;

import jakarta.persistence.*;

@Entity
@Table(name = "account")
public class Account {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private AccountType type;

  /**
   * Cached balance in minor units (paise). The journal is the source of truth; see BalanceReader.
   */
  @Column(nullable = false)
  private long balance;

  // Every UPDATE becomes "... WHERE id = ? AND version = ?". The optimistic strategy relies on it;
  // the row-lock strategy already holds the row, so for it the check never fails.
  @Version
  @Column(nullable = false)
  private long version;

  protected Account() {}

  public Account(String name, String currency) {
    this(name, currency, AccountType.USER);
  }

  public Account(String name, String currency, AccountType type) {
    this.name = name;
    this.currency = currency;
    this.type = type;
  }

  public void apply(long amount) {
    this.balance += amount;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getCurrency() {
    return currency;
  }

  public AccountType getType() {
    return type;
  }

  public long getBalance() {
    return balance;
  }
}
