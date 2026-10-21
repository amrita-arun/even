package dev.even.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.even.notifications.Notifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RuleService {

  private final JdbcTemplate jdbc;
  private final RulePredicateEvaluator evaluator;
  private final ObjectMapper mapper;
  private final Notifier notifier;

  public RuleService(
      JdbcTemplate jdbc,
      RulePredicateEvaluator evaluator,
      ObjectMapper mapper,
      Notifier notifier) {
    this.jdbc = jdbc;
    this.evaluator = evaluator;
    this.mapper = mapper;
    this.notifier = notifier;
  }

  public List<RuleRecord> list(UUID userId) {
    ensureDefaults(userId);
    return jdbc.query(
        """
        select id, user_id, name, predicate::text, enabled
        from rules
        where user_id = ?
        order by name
        """,
        (rs, n) -> new RuleRecord(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("name"),
            rs.getString("predicate"),
            rs.getBoolean("enabled")),
        userId);
  }

  @Transactional
  public RuleRecord create(UUID userId, String name, JsonNode predicate) {
    evaluator.validate(predicate);
    UUID id = UUID.randomUUID();
    try {
      jdbc.update(
          """
          insert into rules (id, user_id, name, predicate, enabled)
          values (?, ?, ?, ?::jsonb, true)
          """,
          id,
          userId,
          name.trim(),
          mapper.writeValueAsString(predicate));
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid rule: " + e.getMessage(), e);
    }
    return new RuleRecord(id, userId, name.trim(), predicate.toString(), true);
  }

  @Transactional
  public void ensureDefaults(UUID userId) {
    Integer count = jdbc.queryForObject(
        "select count(*) from rules where user_id = ?", Integer.class, userId);
    if (count != null && count > 0) {
      return;
    }
    create(userId, "Restaurant over $60", mapper.createObjectNode()
        .set("all", mapper.createArrayNode()
            .add(mapper.createObjectNode().set("gt", mapper.createObjectNode()
                .put("field", "amount_cents")
                .put("value", 6000)))
            .add(mapper.createObjectNode().set("eq", mapper.createObjectNode()
                .put("field", "category")
                .put("value", "Dining")))));
    create(userId, "Rideshare over $40", mapper.createObjectNode()
        .set("all", mapper.createArrayNode()
            .add(mapper.createObjectNode().set("gt", mapper.createObjectNode()
                .put("field", "amount_cents")
                .put("value", 4000)))
            .add(mapper.createObjectNode().set("eq", mapper.createObjectNode()
                .put("field", "category")
                .put("value", "Rideshare")))));
  }

  @Transactional
  public int evaluateTransaction(UUID userId, UUID transactionId) {
    ensureDefaults(userId);
    Map<String, Object> fields = loadFields(userId, transactionId);
    if (fields.isEmpty()) {
      return 0;
    }
    List<RuleRecord> rules = jdbc.query(
        """
        select id, user_id, name, predicate::text, enabled
        from rules
        where user_id = ? and enabled = true
        """,
        (rs, n) -> new RuleRecord(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("name"),
            rs.getString("predicate"),
            rs.getBoolean("enabled")),
        userId);

    int created = 0;
    for (RuleRecord rule : rules) {
      try {
        JsonNode predicate = mapper.readTree(rule.predicateJson());
        if (!evaluator.matches(predicate, fields)) {
          continue;
        }
        UUID flagId = UUID.randomUUID();
        int inserted = jdbc.update(
            """
            insert into flags (id, user_id, transaction_id, rule_id, status)
            values (?, ?, ?, ?, 'open')
            on conflict (transaction_id, rule_id) do nothing
            """,
            flagId,
            userId,
            transactionId,
            rule.id());
        if (inserted > 0) {
          created++;
          notifier.largePurchaseFlagged(userId, transactionId, flagId, rule.name());
        }
      } catch (Exception e) {
        throw new IllegalStateException("failed evaluating rule " + rule.name(), e);
      }
    }
    return created;
  }

  private Map<String, Object> loadFields(UUID userId, UUID transactionId) {
    List<Map<String, Object>> rows = jdbc.query(
        """
        select amount_cents, coalesce(category, '') as category, kind, status, description
        from transactions
        where id = ? and user_id = ?
        """,
        (rs, n) -> {
          Map<String, Object> m = new HashMap<>();
          m.put("amount_cents", rs.getLong("amount_cents"));
          m.put("category", rs.getString("category"));
          m.put("kind", rs.getString("kind"));
          m.put("status", rs.getString("status"));
          m.put("description", rs.getString("description"));
          return m;
        },
        transactionId,
        userId);
    if (rows.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> fields = new HashMap<>(rows.getFirst());
    String category = String.valueOf(fields.get("category"));
    Double avg = jdbc.queryForObject(
        """
        select coalesce(avg(amount_cents), 0)
        from (
          select amount_cents
          from transactions
          where user_id = ?
            and coalesce(category, '') = ?
            and id <> ?
            and status = 'posted'
            and kind = 'purchase'
          order by posted_on desc
          limit 10
        ) t
        """,
        Double.class,
        userId,
        category,
        transactionId);
    fields.put("trailing_avg_amount_cents", avg == null ? 0L : avg.longValue());
    return fields;
  }

  public record RuleRecord(
      UUID id, UUID userId, String name, String predicateJson, boolean enabled) {}
}
