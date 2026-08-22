package ledgerline.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountRepository accounts;
    private final BalanceReader balances;

    public AccountController(AccountRepository accounts, BalanceReader balances) {
        this.accounts = accounts;
        this.balances = balances;
    }

    /** type is optional and defaults to USER. k6 creates a SYSTEM "faucet" to fund test users. */
    public record CreateAccount(@NotBlank String name, @NotBlank @Size(min = 3, max = 3) String currency, AccountType type) {}

    /** balance is the cached value the strategies maintain; journalBalance is recomputed from postings. */
    public record AccountView(Long id, String name, String currency, AccountType type, long balance, long journalBalance) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView create(@Valid @RequestBody CreateAccount req) {
        AccountType type = req.type() == null ? AccountType.USER : req.type();
        return view(accounts.save(new Account(req.name(), req.currency(), type)));
    }

    @GetMapping("/{id}")
    public AccountView get(@PathVariable Long id) {
        return view(accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id)));
    }

    private AccountView view(Account a) {
        return new AccountView(a.getId(), a.getName(), a.getCurrency(), a.getType(),
                balances.cachedBalance(a.getId()), balances.journalBalance(a.getId()));
    }
}
