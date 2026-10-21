package dev.even.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

/** Seeds the minimum rows needed to post ledger groups in tests. */
public final class LedgerTestFixture {

  private final JdbcTemplate jdbc;

  public LedgerTestFixture(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public UUID insertUser(String email) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into users (id, email) values (?, ?)", id, email);
    return id;
  }

  public UUID insertCreditAccount(UUID userId, String name) {
    UUID institutionId = UUID.randomUUID();
    jdbc.update(
        """
        insert into institutions (id, user_id, name, provider)
        values (?, ?, ?, 'manual')
        """,
        institutionId,
        userId,
        name + " Bank");
    UUID accountId = UUID.randomUUID();
    jdbc.update(
        """
        insert into accounts
          (id, user_id, institution_id, name, account_type, currency, provider)
        values (?, ?, ?, ?, 'credit', 'USD', 'manual')
        """,
        accountId,
        userId,
        institutionId,
        name);
    return accountId;
  }

  public UUID insertTransaction(
      UUID userId,
      UUID accountId,
      long amountCents,
      String status,
      String kind,
      String description) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into transactions
          (id, account_id, user_id, amount_cents, currency, posted_on, status, description, kind)
        values (?, ?, ?, ?, 'USD', ?, ?, ?, ?)
        """,
        id,
        accountId,
        userId,
        amountCents,
        LocalDate.of(2026, 10, 1),
        status,
        description,
        kind);
    return id;
  }

  public void markRemoved(UUID transactionId) {
    jdbc.update(
        "update transactions set status = 'removed', updated_at = now() where id = ?",
        transactionId);
  }
}
