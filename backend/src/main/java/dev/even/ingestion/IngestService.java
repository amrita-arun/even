package dev.even.ingestion;

import dev.even.jobs.JobService;
import dev.even.ledger.LedgerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IngestService {

  /** Max tip-sized delta ($25) when matching posted→pending without pending_transaction_id. */
  public static final long TIP_TOLERANCE_CENTS = 2_500L;

  private final JdbcTemplate jdbc;
  private final LedgerService ledger;
  private final JobService jobs;

  public IngestService(JdbcTemplate jdbc, LedgerService ledger, JobService jobs) {
    this.jdbc = jdbc;
    this.ledger = ledger;
    this.jobs = jobs;
  }

  @Transactional
  public String apply(UUID accountId, UUID userId, SyncPage page) {
    AccountRow account = loadOwnedAccount(accountId, userId);

    for (ProviderTransaction row : page.added()) {
      upsert(account, userId, row);
    }
    for (ProviderTransaction row : page.modified()) {
      upsert(account, userId, row);
    }
    for (String removedId : page.removedExternalIds()) {
      handleRemoved(accountId, userId, removedId);
    }
    return page.nextCursor();
  }

  private void upsert(AccountRow account, UUID userId, ProviderTransaction row) {
    Optional<UUID> existing = findByExternalId(account.id(), row.externalId());
    if (existing.isEmpty() && row.pendingExternalId().isPresent()) {
      existing = findByExternalId(account.id(), row.pendingExternalId().get());
    }
    if (existing.isEmpty()) {
      existing = findTipTolerantPending(account.id(), row);
    }

    UUID transactionId;
    boolean hasPosting;
    if (existing.isEmpty()) {
      transactionId = UUID.randomUUID();
      jdbc.update(
          """
          insert into transactions
            (id, account_id, user_id, amount_cents, currency, posted_on, status, description, kind, raw_payload)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
          """,
          transactionId,
          account.id(),
          userId,
          row.amountCents(),
          row.currency(),
          row.postedOn(),
          row.status(),
          row.description(),
          row.kind(),
          row.rawJson());
      hasPosting = false;
    } else {
      transactionId = existing.get();
      hasPosting = hasAnyPosting(transactionId);
      if (!hasPosting) {
        jdbc.update(
            """
            update transactions
            set amount_cents = ?,
                status = ?,
                description = ?,
                kind = ?,
                posted_on = ?,
                currency = ?,
                raw_payload = ?::jsonb,
                updated_at = now()
            where id = ?
            """,
            row.amountCents(),
            row.status(),
            row.description(),
            row.kind(),
            row.postedOn(),
            row.currency(),
            row.rawJson(),
            transactionId);
      } else {
        jdbc.update(
            """
            update transactions
            set description = ?,
                raw_payload = ?::jsonb,
                updated_at = now()
            where id = ?
            """,
            row.description(),
            row.rawJson(),
            transactionId);
      }
    }

    attachExternalId(account.id(), row.externalId(), transactionId);
    row.pendingExternalId()
        .ifPresent(pendingId -> attachExternalId(account.id(), pendingId, transactionId));

    ledger.postForTransaction(userId, transactionId);
    jobs.enqueueProcessTransaction(userId, transactionId);
  }

  private void handleRemoved(UUID accountId, UUID userId, String externalId) {
    Optional<UUID> txnId = findByExternalId(accountId, externalId);
    if (txnId.isEmpty()) {
      return;
    }
    UUID id = txnId.get();
    String status = jdbc.queryForObject(
        "select status from transactions where id = ?", String.class, id);
    if ("pending".equals(status) && !hasAnyPosting(id)) {
      jdbc.update(
          "update transactions set status = 'removed', updated_at = now() where id = ?",
          id);
      return;
    }
    // Posted replacement already linked this id — keep the row.
    attachExternalId(accountId, externalId, id);
  }

  private void attachExternalId(UUID accountId, String externalId, UUID transactionId) {
    jdbc.update(
        """
        insert into transaction_external_ids (account_id, external_id, transaction_id)
        values (?, ?, ?)
        on conflict (account_id, external_id) do update
          set transaction_id = excluded.transaction_id
        """,
        accountId,
        externalId,
        transactionId);
  }

  private Optional<UUID> findByExternalId(UUID accountId, String externalId) {
    List<UUID> ids = jdbc.query(
        """
        select transaction_id
        from transaction_external_ids
        where account_id = ? and external_id = ?
        """,
        (rs, rowNum) -> UUID.fromString(rs.getString("transaction_id")),
        accountId,
        externalId);
    return ids.stream().findFirst();
  }

  /**
   * When Plaid omits {@code pending_transaction_id}, still collapse a posted charge
   * onto a same-day pending row whose amount differs by at most {@link #TIP_TOLERANCE_CENTS}.
   */
  private Optional<UUID> findTipTolerantPending(UUID accountId, ProviderTransaction row) {
    if (!"posted".equals(row.status())) {
      return Optional.empty();
    }
    List<Candidate> candidates = jdbc.query(
        """
        select id, description, amount_cents
        from transactions
        where account_id = ?
          and status = 'pending'
          and posted_on = ?
          and abs(amount_cents - ?) <= ?
        order by abs(amount_cents - ?) asc, created_at asc
        """,
        (rs, n) -> new Candidate(
            UUID.fromString(rs.getString("id")),
            rs.getString("description"),
            rs.getLong("amount_cents")),
        accountId,
        row.postedOn(),
        row.amountCents(),
        TIP_TOLERANCE_CENTS,
        row.amountCents());
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    if (candidates.size() == 1) {
      return Optional.of(candidates.getFirst().id());
    }
    String needle = normalizeDesc(row.description());
    List<Candidate> named = candidates.stream()
        .filter(c -> {
          String hay = normalizeDesc(c.description());
          return !needle.isEmpty() && !hay.isEmpty()
              && (hay.contains(needle) || needle.contains(hay));
        })
        .toList();
    if (named.size() == 1) {
      return Optional.of(named.getFirst().id());
    }
    return Optional.empty();
  }

  private static String normalizeDesc(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
  }

  private record Candidate(UUID id, String description, long amountCents) {}

  private boolean hasAnyPosting(UUID transactionId) {
    Integer count = jdbc.queryForObject(
        "select count(*) from ledger_postings where source_transaction_id = ?",
        Integer.class,
        transactionId);
    return count != null && count > 0;
  }

  private AccountRow loadOwnedAccount(UUID accountId, UUID userId) {
    List<AccountRow> rows = jdbc.query(
        "select id, user_id, account_type from accounts where id = ?",
        (rs, rowNum) -> new AccountRow(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("account_type")),
        accountId);
    if (rows.isEmpty()) {
      throw new NotFoundException("account not found");
    }
    AccountRow account = rows.getFirst();
    if (!account.userId().equals(userId)) {
      throw new NotFoundException("account not found");
    }
    return account;
  }

  private record AccountRow(UUID id, UUID userId, String accountType) {}
}
