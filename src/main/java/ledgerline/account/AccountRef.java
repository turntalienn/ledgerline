package ledgerline.account;

/**
 * The immutable facts about an account (id, currency, type), read without a lock and without
 * putting an Account entity into the persistence context. Balance strategies do their own loading
 * and locking afterwards; a managed Account loaded here first would be returned stale by a later
 * SELECT ... FOR UPDATE.
 */
public record AccountRef(Long id, String currency, AccountType type) {}
