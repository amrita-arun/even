package dev.even.accounts;

import dev.even.auth.UserRepository;
import dev.even.ingestion.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AccountService {

  private final JdbcTemplate jdbc;
  private final UserRepository users;

  public AccountService(JdbcTemplate jdbc, UserRepository users) {
    this.jdbc = jdbc;
    this.users = users;
  }

  @Transactional
  public AccountRecord create(UUID userId, String email, String name, String accountType, String currency) {
    users.upsert(userId, email);
    String type = normalizeType(accountType);
    String cur = currency == null || currency.isBlank()
        ? "USD"
        : currency.trim().toUpperCase(Locale.ROOT);
    if (!"USD".equals(cur)) {
      throw new IllegalArgumentException("only USD is supported");
    }

    UUID institutionId = findOrCreateManualInstitution(userId);
    UUID accountId = UUID.randomUUID();
    jdbc.update(
        """
        insert into accounts
          (id, user_id, institution_id, name, account_type, currency, provider)
        values (?, ?, ?, ?, ?, ?, 'manual')
        """,
        accountId,
        userId,
        institutionId,
        name.trim(),
        type,
        cur);
    return new AccountRecord(accountId, userId, name.trim(), type, cur);
  }

  public List<AccountRecord> list(UUID userId) {
    return jdbc.query(
        """
        select id, user_id, name, account_type, currency
        from accounts
        where user_id = ?
        order by created_at
        """,
        (rs, rowNum) -> new AccountRecord(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("name"),
            rs.getString("account_type"),
            rs.getString("currency")),
        userId);
  }

  public AccountRecord requireOwned(UUID accountId, UUID userId) {
    List<AccountRecord> rows = jdbc.query(
        """
        select id, user_id, name, account_type, currency
        from accounts
        where id = ?
        """,
        (rs, rowNum) -> new AccountRecord(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("name"),
            rs.getString("account_type"),
            rs.getString("currency")),
        accountId);
    if (rows.isEmpty() || !rows.getFirst().userId().equals(userId)) {
      throw new NotFoundException("account not found");
    }
    return rows.getFirst();
  }

  @Transactional
  public AccountRecord upsertExternal(
      UUID userId,
      String email,
      String institutionName,
      String externalAccountId,
      String name,
      String mask,
      String accountType) {
    if (externalAccountId == null || externalAccountId.isBlank()) {
      throw new IllegalArgumentException("externalAccountId is required");
    }
    String type = normalizeType(accountType);
    String display = displayName(name, mask);
    UUID institutionId = findOrCreateInstitution(
        userId, institutionName == null || institutionName.isBlank() ? "Plaid" : institutionName.trim(), "plaid");
    List<UUID> existing = jdbc.query(
        """
        select id from accounts
        where user_id = ? and provider = 'plaid' and external_account_id = ?
        """,
        (rs, rowNum) -> UUID.fromString(rs.getString("id")),
        userId,
        externalAccountId.trim());
    if (!existing.isEmpty()) {
      UUID accountId = existing.getFirst();
      jdbc.update(
          """
          update accounts
          set name = ?, account_type = ?, institution_id = ?
          where id = ? and user_id = ?
          """,
          display,
          type,
          institutionId,
          accountId,
          userId);
      return new AccountRecord(accountId, userId, display, type, "USD");
    }
    users.upsert(userId, email);
    UUID accountId = UUID.randomUUID();
    jdbc.update(
        """
        insert into accounts
          (id, user_id, institution_id, name, account_type, currency, provider, external_account_id)
        values (?, ?, ?, ?, ?, 'USD', 'plaid', ?)
        """,
        accountId,
        userId,
        institutionId,
        display,
        type,
        externalAccountId.trim());
    return new AccountRecord(accountId, userId, display, type, "USD");
  }

  private static String normalizeType(String accountType) {
    String type = accountType == null ? "" : accountType.trim().toLowerCase(Locale.ROOT);
    if (!type.equals("credit") && !type.equals("depository")) {
      throw new IllegalArgumentException("accountType must be credit or depository");
    }
    return type;
  }

  private static String displayName(String name, String mask) {
    String display = name == null || name.isBlank() ? "Account" : name.trim();
    if (mask != null && !mask.isBlank() && !display.contains(mask.trim())) {
      display = display + " ··" + mask.trim();
    }
    return display;
  }

  private UUID findOrCreateManualInstitution(UUID userId) {
    return findOrCreateInstitution(userId, "Manual", "manual");
  }

  private UUID findOrCreateInstitution(UUID userId, String name, String provider) {
    List<UUID> existing = jdbc.query(
        """
        select id from institutions
        where user_id = ? and provider = ? and name = ?
        limit 1
        """,
        (rs, rowNum) -> UUID.fromString(rs.getString("id")),
        userId,
        provider,
        name);
    if (!existing.isEmpty()) {
      return existing.getFirst();
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into institutions (id, user_id, name, provider)
        values (?, ?, ?, ?)
        """,
        id,
        userId,
        name,
        provider);
    return id;
  }

  public record AccountRecord(
      UUID id, UUID userId, String name, String accountType, String currency) {}
}
