package dev.even.enrichment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class EnrichmentService {

  private final JdbcTemplate jdbc;
  private final MerchantNormalizer normalizer;

  public EnrichmentService(JdbcTemplate jdbc, MerchantNormalizer normalizer) {
    this.jdbc = jdbc;
    this.normalizer = normalizer;
  }

  @Transactional
  public void enrich(UUID userId, UUID transactionId) {
    List<Txn> rows = jdbc.query(
        """
        select id, user_id, description, category, merchant_id
        from transactions
        where id = ?
        """,
        (rs, n) -> new Txn(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("description"),
            rs.getString("category"),
            rs.getString("merchant_id") == null
                ? null
                : UUID.fromString(rs.getString("merchant_id"))),
        transactionId);
    if (rows.isEmpty() || !rows.getFirst().userId().equals(userId)) {
      return;
    }
    Txn txn = rows.getFirst();

    String aliasMerchant = findAliasMerchantName(userId, txn.description());
    MerchantNormalizer.NormalizedMerchant normalized = aliasMerchant == null
        ? normalizer.normalize(txn.description())
        : new MerchantNormalizer.NormalizedMerchant(
            aliasMerchant, MerchantNormalizer.inferCategory(aliasMerchant));

    UUID merchantId = ensureMerchant(userId, normalized.displayName(), normalized.category());
    jdbc.update(
        """
        update transactions
        set merchant_id = ?,
            category = ?,
            updated_at = now()
        where id = ?
        """,
        merchantId,
        normalized.category(),
        transactionId);
  }

  private String findAliasMerchantName(UUID userId, String description) {
    String lower = description.toLowerCase(Locale.ROOT);
    List<Alias> aliases = jdbc.query(
        """
        select a.pattern, m.display_name
        from merchant_aliases a
        join merchants m on m.id = a.merchant_id
        where a.user_id = ?
        """,
        (rs, n) -> new Alias(rs.getString("pattern"), rs.getString("display_name")),
        userId);
    for (Alias alias : aliases) {
      if (lower.contains(alias.pattern().toLowerCase(Locale.ROOT))) {
        return alias.displayName();
      }
    }
    return null;
  }

  private UUID ensureMerchant(UUID userId, String displayName, String category) {
    jdbc.update(
        """
        insert into merchants (id, user_id, display_name, category)
        values (?, ?, ?, ?)
        on conflict (user_id, display_name) do update
          set category = coalesce(merchants.category, excluded.category)
        """,
        UUID.randomUUID(),
        userId,
        displayName,
        category);
    return jdbc.queryForObject(
        "select id from merchants where user_id = ? and display_name = ?",
        UUID.class,
        userId,
        displayName);
  }

  private record Txn(
      UUID id, UUID userId, String description, String category, UUID merchantId) {}

  private record Alias(String pattern, String displayName) {}
}
