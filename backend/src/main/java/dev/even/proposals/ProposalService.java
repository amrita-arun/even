package dev.even.proposals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.even.ingestion.NotFoundException;
import dev.even.people.PeopleService;
import dev.even.splits.SplitEngine;
import dev.even.splits.SplitMethod;
import dev.even.splits.SplitRuleService;
import dev.even.splits.SplitService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ProposalService {

  static final String MODEL = "heuristic-mock";

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final PeopleService people;
  private final SplitService splits;
  private final SplitRuleService splitRules;
  private final SplitEngine splitEngine;

  public ProposalService(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      PeopleService people,
      SplitService splits,
      SplitRuleService splitRules,
      SplitEngine splitEngine) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.people = people;
    this.splits = splits;
    this.splitRules = splitRules;
    this.splitEngine = splitEngine;
  }

  /**
   * Writes a pending proposal when the heuristic clears the bar. Does not post
   * a journal group. One proposal per transaction.
   */
  @Transactional
  public void consider(UUID userId, UUID transactionId) {
    Txn txn = loadTxn(userId, transactionId);
    if (txn == null || !"posted".equals(txn.status()) || !"purchase".equals(txn.kind()) || txn.amountCents() <= 0) {
      return;
    }
    if (alreadySplit(transactionId) || proposalExists(transactionId)) {
      return;
    }

    var matched = splitRules.match(
        userId, txn.description(), txn.merchantName(), txn.amountCents());
    if (matched.isPresent()) {
      SplitRuleService.MatchedRule rule = matched.get();
      savePending(
          userId,
          transactionId,
          "Rule " + rule.name(),
          rule.personIds(),
          rule.request());
      audit(userId, transactionId, txn, hasOpenFlag(transactionId), 0d, 100, true, rule.personIds());
      return;
    }

    List<UUID> friends = friendIds(userId);
    if (friends.isEmpty()) {
      return;
    }
    List<UUID> pastPartners = pastPartners(userId, txn.merchantId());
    boolean flagged = hasOpenFlag(transactionId);
    double rate = historicalSplitRate(userId, txn.merchantId());
    int score = SplitSuggestionScorer.score(txn.category(), txn.amountCents(), flagged, rate);

    List<UUID> suggested = new ArrayList<>();
    if (!pastPartners.isEmpty()) {
      suggested.addAll(pastPartners);
    } else if (friends.size() <= 3) {
      suggested.addAll(friends);
    }
    suggested.retainAll(friends);

    boolean propose = SplitSuggestionScorer.suggest(score) && !suggested.isEmpty();
    String reason = reason(score, txn.category(), flagged, rate, suggested.size());
    audit(userId, transactionId, txn, flagged, rate, score, propose, suggested);
    if (!propose) {
      return;
    }
    jdbc.update(
        """
        insert into proposals (id, user_id, transaction_id, status, score, reason, person_ids)
        values (?, ?, ?, 'pending', ?, ?, ?::jsonb)
        """,
        UUID.randomUUID(),
        userId,
        transactionId,
        score,
        reason,
        toJson(suggested));
  }

  public List<ProposalRecord> list(UUID userId, String status) {
    if (status == null || status.isBlank()) {
      return jdbc.query(
          """
          select p.id, p.transaction_id, t.description, t.amount_cents, p.status, p.score, p.reason, p.person_ids::text, p.split_request::text
          from proposals p
          join transactions t on t.id = p.transaction_id
          where p.user_id = ?
          order by p.created_at desc
          """,
          this::mapProposal,
          userId);
    }
    return jdbc.query(
        """
        select p.id, p.transaction_id, t.description, t.amount_cents, p.status, p.score, p.reason, p.person_ids::text, p.split_request::text
        from proposals p
        join transactions t on t.id = p.transaction_id
        where p.user_id = ? and p.status = ?
        order by p.created_at desc
        """,
        this::mapProposal,
        userId,
        status);
  }

  @Transactional
  public ProposalRecord approve(UUID userId, UUID proposalId) {
    ProposalRecord proposal = requireOwned(userId, proposalId);
    if (!"pending".equals(proposal.status())) {
      throw new IllegalArgumentException("proposal is already " + proposal.status());
    }
    if (proposal.splitRequestJson() != null && !proposal.splitRequestJson().isBlank()) {
      splits.split(userId, proposal.transactionId(), readSplit(proposal.splitRequestJson()));
    } else {
      List<SplitService.ShareRequest> shares = new ArrayList<>();
      shares.add(new SplitService.ShareRequest(null, null, null, null));
      for (UUID personId : proposal.personIds()) {
        people.requireOwned(userId, personId);
        shares.add(new SplitService.ShareRequest(personId, null, null, null));
      }
      splits.split(
          userId,
          proposal.transactionId(),
          new SplitService.SplitRequest(SplitMethod.equal, 0L, 0L, shares));
    }
    jdbc.update(
        """
        update proposals set status = 'approved', decided_at = now() where id = ?
        """,
        proposalId);
    return requireOwned(userId, proposalId);
  }

  @Transactional
  public ProposalRecord reject(UUID userId, UUID proposalId) {
    ProposalRecord proposal = requireOwned(userId, proposalId);
    if (!"pending".equals(proposal.status())) {
      throw new IllegalArgumentException("proposal is already " + proposal.status());
    }
    feedback(userId, proposal, "reject", proposal.personIds());
    jdbc.update(
        "update proposals set status = 'rejected', decided_at = now() where id = ?",
        proposalId);
    return requireOwned(userId, proposalId);
  }

  @Transactional
  public ProposalRecord edit(UUID userId, UUID proposalId, List<UUID> personIds) {
    ProposalRecord proposal = requireOwned(userId, proposalId);
    if (!"pending".equals(proposal.status())) {
      throw new IllegalArgumentException("proposal is already " + proposal.status());
    }
    if (proposal.splitRequestJson() != null && !proposal.splitRequestJson().isBlank()) {
      throw new IllegalArgumentException(
          "this proposal has set shares; reject it and describe the split again");
    }
    if (personIds == null || personIds.isEmpty()) {
      throw new IllegalArgumentException("at least one person is required");
    }
    Set<UUID> unique = new LinkedHashSet<>(personIds);
    for (UUID personId : unique) {
      PeopleService.PersonRecord person = people.requireOwned(userId, personId);
      if (!"friend".equals(person.kind())) {
        throw new IllegalArgumentException("proposals split with friends only");
      }
    }
    List<UUID> edited = List.copyOf(unique);
    feedback(userId, proposal, "edit", edited);
    jdbc.update(
        "update proposals set person_ids = ?::jsonb where id = ?",
        toJson(edited),
        proposalId);
    return requireOwned(userId, proposalId);
  }

  private void feedback(UUID userId, ProposalRecord proposal, String action, List<UUID> personIds) {
    jdbc.update(
        """
        insert into split_feedback (id, user_id, proposal_id, transaction_id, action, person_ids)
        values (?, ?, ?, ?, ?, ?::jsonb)
        """,
        UUID.randomUUID(),
        userId,
        proposal.id(),
        proposal.transactionId(),
        action,
        toJson(personIds));
  }

  private void audit(
      UUID userId,
      UUID transactionId,
      Txn txn,
      boolean flagged,
      double rate,
      int score,
      boolean propose,
      List<UUID> suggested) {
    ObjectNode request = mapper.createObjectNode();
    request.put("category", txn.category() == null ? "" : txn.category());
    request.put("amountCents", txn.amountCents());
    request.put("flagged", flagged);
    request.put("historicalSplitRate", rate);
    request.put("merchant", txn.merchantName() == null ? "" : txn.merchantName());
    ObjectNode response = mapper.createObjectNode();
    response.put("score", score);
    response.put("propose", propose);
    ArrayNode ids = response.putArray("personIds");
    suggested.forEach(id -> ids.add(id.toString()));
    jdbc.update(
        """
        insert into ai_audit_log (id, user_id, transaction_id, model, request, response)
        values (?, ?, ?, ?, ?::jsonb, ?::jsonb)
        """,
        UUID.randomUUID(),
        userId,
        transactionId,
        MODEL,
        request.toString(),
        response.toString());
  }

  private String reason(int score, String category, boolean flagged, double rate, int people) {
    return "score " + score
        + " from category " + (category == null ? "none" : category)
        + (flagged ? ", open flag" : "")
        + String.format(", split rate %.0f%%", rate * 100)
        + ", " + people + " friend(s)";
  }

  private Txn loadTxn(UUID userId, UUID transactionId) {
    List<Txn> rows = jdbc.query(
        """
        select t.id, t.user_id, t.amount_cents, t.status, t.kind, t.category,
               t.description, t.merchant_id, m.display_name as merchant_name
        from transactions t
        left join merchants m on m.id = t.merchant_id
        where t.id = ?
        """,
        (rs, n) -> new Txn(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getLong("amount_cents"),
            rs.getString("status"),
            rs.getString("kind"),
            rs.getString("category"),
            rs.getString("description"),
            rs.getString("merchant_id") == null ? null : UUID.fromString(rs.getString("merchant_id")),
            rs.getString("merchant_name")),
        transactionId);
    if (rows.isEmpty() || !rows.getFirst().userId().equals(userId)) {
      return null;
    }
    return rows.getFirst();
  }

  private boolean alreadySplit(UUID transactionId) {
    Integer count = jdbc.queryForObject(
        "select count(*) from splits where transaction_id = ?",
        Integer.class,
        transactionId);
    return count != null && count > 0;
  }

  private boolean proposalExists(UUID transactionId) {
    Integer count = jdbc.queryForObject(
        "select count(*) from proposals where transaction_id = ?",
        Integer.class,
        transactionId);
    return count != null && count > 0;
  }

  private boolean hasOpenFlag(UUID transactionId) {
    Integer count = jdbc.queryForObject(
        "select count(*) from flags where transaction_id = ? and status = 'open'",
        Integer.class,
        transactionId);
    return count != null && count > 0;
  }

  private double historicalSplitRate(UUID userId, UUID merchantId) {
    if (merchantId == null) {
      return 0d;
    }
    Integer total = jdbc.queryForObject(
        """
        select count(*) from transactions
        where user_id = ? and merchant_id = ? and kind = 'purchase' and status = 'posted'
        """,
        Integer.class,
        userId,
        merchantId);
    if (total == null || total == 0) {
      return 0d;
    }
    Integer split = jdbc.queryForObject(
        """
        select count(*) from transactions t
        join splits s on s.transaction_id = t.id
        where t.user_id = ? and t.merchant_id = ?
        """,
        Integer.class,
        userId,
        merchantId);
    return (split == null ? 0d : split) / total.doubleValue();
  }

  private List<UUID> pastPartners(UUID userId, UUID merchantId) {
    if (merchantId == null) {
      return List.of();
    }
    return jdbc.query(
        """
        select distinct ss.person_id
        from split_shares ss
        join splits s on s.id = ss.split_id
        join transactions t on t.id = s.transaction_id
        join people p on p.id = ss.person_id
        where s.user_id = ? and t.merchant_id = ? and p.kind = 'friend'
        """,
        (rs, n) -> UUID.fromString(rs.getString("person_id")),
        userId,
        merchantId);
  }

  private List<UUID> friendIds(UUID userId) {
    return people.list(userId).stream()
        .filter(p -> "friend".equals(p.kind()))
        .map(PeopleService.PersonRecord::id)
        .toList();
  }

  private ProposalRecord requireOwned(UUID userId, UUID proposalId) {
    List<ProposalRecord> rows = jdbc.query(
        """
        select p.id, p.transaction_id, t.description, t.amount_cents, p.status, p.score, p.reason, p.person_ids::text, p.split_request::text
        from proposals p
        join transactions t on t.id = p.transaction_id
        where p.id = ? and p.user_id = ?
        """,
        this::mapProposal,
        proposalId,
        userId);
    if (rows.isEmpty()) {
      throw new NotFoundException("proposal not found");
    }
    return rows.getFirst();
  }

  private ProposalRecord mapProposal(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new ProposalRecord(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("transaction_id")),
        rs.getString("description"),
        rs.getLong("amount_cents"),
        rs.getString("status"),
        rs.getInt("score"),
        rs.getString("reason"),
        parseIds(rs.getString("person_ids")),
        rs.getString("split_request"));
  }

  /**
   * Stores a pending proposal whose approval posts {@code request}. The request
   * has already passed the split engine.
   */
  public ProposalRecord savePending(
      UUID userId,
      UUID transactionId,
      String reason,
      List<UUID> personIds,
      SplitService.SplitRequest request) {
    if (alreadySplit(transactionId) || proposalExists(transactionId)) {
      throw new IllegalArgumentException("transaction already has a split or a proposal");
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into proposals (id, user_id, transaction_id, status, score, reason, person_ids, split_request)
        values (?, ?, ?, 'pending', 100, ?, ?::jsonb, ?::jsonb)
        """,
        id,
        userId,
        transactionId,
        reason,
        toJson(personIds),
        writeSplit(request));
    return requireOwned(userId, id);
  }

  private String writeSplit(SplitService.SplitRequest request) {
    ObjectNode node = mapper.createObjectNode();
    node.put("method", request.method().name());
    node.put("taxCents", request.taxCents());
    node.put("tipCents", request.tipCents());
    ArrayNode shares = node.putArray("shares");
    for (SplitService.ShareRequest share : request.shares()) {
      ObjectNode row = shares.addObject();
      if (share.personId() == null) {
        row.putNull("personId");
      } else {
        row.put("personId", share.personId().toString());
      }
      putNullableLong(row, "shareCents", share.shareCents());
      if (share.basisPoints() == null) {
        row.putNull("basisPoints");
      } else {
        row.put("basisPoints", share.basisPoints());
      }
      putNullableLong(row, "itemizedCents", share.itemizedCents());
    }
    return node.toString();
  }

  private SplitService.SplitRequest readSplit(String json) {
    try {
      var node = mapper.readTree(json);
      List<SplitService.ShareRequest> shares = new ArrayList<>();
      for (var share : node.get("shares")) {
        UUID personId = share.get("personId").isNull()
            ? null
            : UUID.fromString(share.get("personId").asText());
        shares.add(new SplitService.ShareRequest(
            personId,
            nullableLong(share.get("shareCents")),
            share.get("basisPoints").isNull() ? null : share.get("basisPoints").asInt(),
            nullableLong(share.get("itemizedCents"))));
      }
      return new SplitService.SplitRequest(
          SplitMethod.valueOf(node.get("method").asText()),
          node.get("taxCents").asLong(),
          node.get("tipCents").asLong(),
          shares);
    } catch (Exception e) {
      throw new IllegalStateException("invalid stored split", e);
    }
  }

  private static void putNullableLong(ObjectNode node, String field, Long value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private static Long nullableLong(com.fasterxml.jackson.databind.JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return node.asLong();
  }

  private List<UUID> parseIds(String json) {
    try {
      List<UUID> ids = new ArrayList<>();
      for (var node : mapper.readTree(json)) {
        ids.add(UUID.fromString(node.asText()));
      }
      return ids;
    } catch (Exception e) {
      throw new IllegalStateException("invalid proposal person_ids", e);
    }
  }

  /**
   * Shares approval would post. Does not write a journal group.
   */
  public List<SplitEngine.ComputedShare> previewShares(UUID userId, UUID proposalId) {
    ProposalRecord proposal = requireOwned(userId, proposalId);
    if (proposal.splitRequestJson() != null && !proposal.splitRequestJson().isBlank()) {
      SplitService.SplitRequest request = readSplit(proposal.splitRequestJson());
      List<SplitEngine.ShareInput> inputs = request.shares().stream()
          .map(share -> new SplitEngine.ShareInput(
              share.personId(), share.shareCents(), share.basisPoints(), share.itemizedCents()))
          .toList();
      return splitEngine.compute(
          proposal.amountCents(), request.method(), inputs, request.taxCents(), request.tipCents());
    }
    List<SplitEngine.ShareInput> inputs = new ArrayList<>();
    inputs.add(new SplitEngine.ShareInput(null, null, null, null));
    for (UUID personId : proposal.personIds()) {
      inputs.add(new SplitEngine.ShareInput(personId, null, null, null));
    }
    return splitEngine.compute(proposal.amountCents(), SplitMethod.equal, inputs, 0L, 0L);
  }

  private String toJson(List<UUID> ids) {
    ArrayNode array = mapper.createArrayNode();
    ids.forEach(id -> array.add(id.toString()));
    return array.toString();
  }

  private record Txn(
      UUID id,
      UUID userId,
      long amountCents,
      String status,
      String kind,
      String category,
      String description,
      UUID merchantId,
      String merchantName) {}

  public record ProposalRecord(
      UUID id,
      UUID transactionId,
      String description,
      long amountCents,
      String status,
      int score,
      String reason,
      List<UUID> personIds,
      String splitRequestJson) {}
}
