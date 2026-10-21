package dev.even.flags;

import dev.even.ingestion.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class FlagService {

  private final JdbcTemplate jdbc;

  public FlagService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<FlagRecord> list(UUID userId, String status) {
    if (status == null || status.isBlank()) {
      return jdbc.query(
          """
          select f.id, f.transaction_id, f.rule_id, f.status, r.name as rule_name,
                 t.description, t.amount_cents
          from flags f
          join rules r on r.id = f.rule_id
          join transactions t on t.id = f.transaction_id
          where f.user_id = ?
          order by f.created_at desc
          """,
          this::mapFlag,
          userId);
    }
    return jdbc.query(
        """
        select f.id, f.transaction_id, f.rule_id, f.status, r.name as rule_name,
               t.description, t.amount_cents
        from flags f
        join rules r on r.id = f.rule_id
        join transactions t on t.id = f.transaction_id
        where f.user_id = ? and f.status = ?
        order by f.created_at desc
        """,
        this::mapFlag,
        userId,
        status);
  }

  @Transactional
  public FlagRecord updateStatus(UUID userId, UUID flagId, String status) {
    String normalized = status.trim().toLowerCase(Locale.ROOT);
    if (!List.of("open", "acknowledged", "dismissed").contains(normalized)) {
      throw new IllegalArgumentException("status must be open, acknowledged, or dismissed");
    }
    int updated = jdbc.update(
        """
        update flags
        set status = ?, updated_at = now()
        where id = ? and user_id = ?
        """,
        normalized,
        flagId,
        userId);
    if (updated == 0) {
      throw new NotFoundException("flag not found");
    }
    return list(userId, null).stream()
        .filter(f -> f.id().equals(flagId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("flag not found"));
  }

  private FlagRecord mapFlag(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new FlagRecord(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("transaction_id")),
        UUID.fromString(rs.getString("rule_id")),
        rs.getString("status"),
        rs.getString("rule_name"),
        rs.getString("description"),
        rs.getLong("amount_cents"));
  }

  public record FlagRecord(
      UUID id,
      UUID transactionId,
      UUID ruleId,
      String status,
      String ruleName,
      String description,
      long amountCents) {}
}
