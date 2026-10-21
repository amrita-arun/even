package dev.even.plaid;

import dev.even.accounts.AccountService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PlaidItemService {

  private final JdbcTemplate jdbc;
  private final TokenCipher cipher;
  private final PlaidApiClient plaid;
  private final AccountService accounts;

  public PlaidItemService(
      JdbcTemplate jdbc, TokenCipher cipher, PlaidApiClient plaid, AccountService accounts) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.plaid = plaid;
    this.accounts = accounts;
  }

  public boolean live() {
    return plaid.isLive();
  }

  public PlaidApiClient.LinkTokenResult createLinkToken(UUID userId, String webhookUrl) {
    return plaid.createLinkToken(userId.toString(), webhookUrl);
  }

  @Transactional
  public LinkedItem exchangeAndStore(UUID userId, String publicToken, UUID accountId) {
    return exchangeAndStore(userId, null, publicToken, accountId, null, List.of());
  }

  @Transactional
  public LinkedItem exchangeAndStore(
      UUID userId,
      String email,
      String publicToken,
      UUID accountId,
      String institutionName,
      List<PlaidAccountLink> selectedAccounts) {
    PlaidApiClient.ExchangeResult exchanged = plaid.exchangePublicToken(publicToken);
    String storedName = institutionName == null || institutionName.isBlank()
        ? exchanged.institutionName()
        : institutionName.trim();
    UUID itemRowId = UUID.randomUUID();
    jdbc.update(
        """
        insert into plaid_items
          (id, user_id, item_id, access_token_encrypted, institution_name)
        values (?, ?, ?, ?, ?)
        on conflict (item_id) do update
          set access_token_encrypted = excluded.access_token_encrypted,
              institution_name = excluded.institution_name,
              updated_at = now()
        """,
        itemRowId,
        userId,
        exchanged.itemId(),
        cipher.encrypt(exchanged.accessToken()),
        storedName);
    UUID storedId = jdbc.queryForObject(
        "select id from plaid_items where item_id = ?",
        UUID.class,
        exchanged.itemId());
    List<UUID> linked = new ArrayList<>();
    if (accountId != null) {
      linkAccount(userId, storedId, accountId);
      linked.add(accountId);
    }
    for (PlaidAccountLink selected : selectedAccounts) {
      if (email == null || email.isBlank()) {
        throw new IllegalArgumentException("email is required to store a Plaid account");
      }
      AccountService.AccountRecord created = accounts.upsertExternal(
          userId,
          email,
          storedName,
          selected.externalAccountId(),
          selected.name(),
          selected.mask(),
          selected.accountType());
      linkAccount(userId, storedId, created.id());
      linked.add(created.id());
    }
    return new LinkedItem(storedId, exchanged.itemId(), storedName, plaid.isLive(), linked);
  }

  private void linkAccount(UUID userId, UUID plaidItemId, UUID accountId) {
    Integer owned = jdbc.queryForObject(
        "select count(*) from accounts where id = ? and user_id = ?",
        Integer.class,
        accountId,
        userId);
    if (owned == null || owned == 0) {
      throw new IllegalArgumentException("account not found");
    }
    jdbc.update(
        """
        update accounts
        set plaid_item_id = ?, provider = 'plaid'
        where id = ? and user_id = ?
        """,
        plaidItemId,
        accountId,
        userId);
  }

  public Optional<ItemSecrets> findByAccount(UUID userId, UUID accountId) {
    List<ItemSecrets> rows = jdbc.query(
        """
        select i.id, i.item_id, i.access_token_encrypted, i.cursor, a.account_type
        from accounts a
        join plaid_items i on i.id = a.plaid_item_id
        where a.id = ? and a.user_id = ?
        """,
        (rs, n) -> new ItemSecrets(
            UUID.fromString(rs.getString("id")),
            rs.getString("item_id"),
            cipher.decrypt(rs.getString("access_token_encrypted")),
            rs.getString("cursor"),
            rs.getString("account_type")),
        accountId,
        userId);
    return rows.stream().findFirst();
  }

  public Optional<ItemSecrets> findByItemId(String itemId) {
    List<ItemSecrets> rows = jdbc.query(
        """
        select i.id, i.item_id, i.access_token_encrypted, i.cursor, 'credit' as account_type
        from plaid_items i
        where i.item_id = ?
        """,
        (rs, n) -> new ItemSecrets(
            UUID.fromString(rs.getString("id")),
            rs.getString("item_id"),
            cipher.decrypt(rs.getString("access_token_encrypted")),
            rs.getString("cursor"),
            rs.getString("account_type")),
        itemId);
    return rows.stream().findFirst();
  }

  @Transactional
  public void saveCursor(UUID plaidItemRowId, String cursor) {
    jdbc.update(
        "update plaid_items set cursor = ?, updated_at = now() where id = ?",
        cursor,
        plaidItemRowId);
  }

  public List<LinkedAccountDetail> findAccountDetails(UUID userId, UUID plaidItemId) {
    return jdbc.query(
        """
        select id, user_id, account_type, external_account_id
        from accounts
        where user_id = ? and plaid_item_id = ?
        """,
        (rs, n) -> new LinkedAccountDetail(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("account_type"),
            rs.getString("external_account_id")),
        userId,
        plaidItemId);
  }

  public List<LinkedAccount> findAccountsByItemId(String itemId) {
    return jdbc.query(
        """
        select a.id as account_id, a.user_id, a.account_type
        from accounts a
        join plaid_items i on i.id = a.plaid_item_id
        where i.item_id = ?
        """,
        (rs, n) -> new LinkedAccount(
            UUID.fromString(rs.getString("account_id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("account_type")),
        itemId);
  }

  public List<ItemStatus> listForUser(UUID userId) {
    return jdbc.query(
        """
        select i.id, i.institution_name, i.updated_at,
               coalesce(array_agg(a.id) filter (where a.id is not null), array[]::uuid[]) as account_ids
        from plaid_items i
        left join accounts a on a.plaid_item_id = i.id and a.user_id = i.user_id
        where i.user_id = ?
        group by i.id, i.institution_name, i.updated_at
        order by i.updated_at desc
        """,
        (rs, n) -> {
          java.sql.Array raw = rs.getArray("account_ids");
          List<UUID> accountIds = new ArrayList<>();
          if (raw != null) {
            Object[] values = (Object[]) raw.getArray();
            for (Object value : values) {
              accountIds.add(value instanceof UUID id ? id : UUID.fromString(value.toString()));
            }
          }
          return new ItemStatus(
              UUID.fromString(rs.getString("id")),
              rs.getString("institution_name"),
              rs.getTimestamp("updated_at").toInstant(),
              accountIds);
        },
        userId);
  }

  @Transactional
  public void disconnect(UUID userId, UUID plaidItemId) {
    Integer owned = jdbc.queryForObject(
        "select count(*) from plaid_items where id = ? and user_id = ?",
        Integer.class,
        plaidItemId,
        userId);
    if (owned == null || owned == 0) {
      throw new dev.even.ingestion.NotFoundException("plaid item not found");
    }
    jdbc.update(
        "update accounts set plaid_item_id = null where plaid_item_id = ? and user_id = ?",
        plaidItemId,
        userId);
    jdbc.update("delete from plaid_items where id = ? and user_id = ?", plaidItemId, userId);
  }

  public record ItemStatus(
      UUID id, String institutionName, java.time.Instant updatedAt, List<UUID> accountIds) {}

  public record PlaidAccountLink(
      String externalAccountId, String name, String mask, String accountType) {}

  public record LinkedItem(
      UUID id, String itemId, String institutionName, boolean live, List<UUID> accountIds) {

    public LinkedItem(UUID id, String itemId, String institutionName, boolean live) {
      this(id, itemId, institutionName, live, List.of());
    }
  }

  public record LinkedAccountDetail(
      UUID accountId, UUID userId, String accountType, String externalAccountId) {}

  public record LinkedAccount(UUID accountId, UUID userId, String accountType) {}

  public record ItemSecrets(
      UUID id, String itemId, String accessToken, String cursor, String accountType) {}
}
