package dev.even.splits;

import dev.even.people.PeopleService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Merchant rules that prepare a pending proposal. They never post a journal group.
 */
@Service
public class SplitRuleService {

  private final JdbcTemplate jdbc;
  private final PeopleService people;
  private final SplitEngine engine;

  public SplitRuleService(JdbcTemplate jdbc, PeopleService people, SplitEngine engine) {
    this.jdbc = jdbc;
    this.people = people;
    this.engine = engine;
  }

  public List<GroupRecord> listGroups(UUID userId) {
    return jdbc.query(
        """
        select id, name from person_groups
        where user_id = ?
        order by name
        """,
        (rs, n) -> {
          UUID id = UUID.fromString(rs.getString("id"));
          return new GroupRecord(id, rs.getString("name"), memberIds(id));
        },
        userId);
  }

  @Transactional
  public GroupRecord createGroup(UUID userId, String name, List<UUID> personIds) {
    String label = requiredName(name, "group name is required");
    List<UUID> members = friendsOnly(userId, personIds);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into person_groups (id, user_id, name) values (?, ?, ?)",
        id,
        userId,
        label);
    for (UUID personId : members) {
      jdbc.update(
          "insert into person_group_members (group_id, person_id) values (?, ?)",
          id,
          personId);
    }
    return new GroupRecord(id, label, members);
  }

  public List<RuleRecord> listRules(UUID userId) {
    return jdbc.query(
        """
        select id, name, merchant_match, method, person_id, group_id, basis_points
        from split_rules
        where user_id = ?
        order by created_at desc
        """,
        (rs, n) -> new RuleRecord(
            UUID.fromString(rs.getString("id")),
            rs.getString("name"),
            rs.getString("merchant_match"),
            rs.getString("method"),
            rs.getString("person_id") == null ? null : UUID.fromString(rs.getString("person_id")),
            rs.getString("group_id") == null ? null : UUID.fromString(rs.getString("group_id")),
            rs.getObject("basis_points") == null ? null : rs.getInt("basis_points")),
        userId);
  }

  @Transactional
  public RuleRecord createRule(
      UUID userId,
      String name,
      String merchantMatch,
      String method,
      UUID personId,
      UUID groupId,
      Integer basisPoints) {
    String label = requiredName(name, "rule name is required");
    String merchant = requiredName(merchantMatch, "merchant is required");
    if ((personId == null) == (groupId == null)) {
      throw new IllegalArgumentException("choose a person or a saved group");
    }
    String normalized = method == null ? "" : method.trim().toLowerCase(Locale.ROOT);
    if (!"equal".equals(normalized) && !"percentage".equals(normalized)) {
      throw new IllegalArgumentException("method must be equal or percentage");
    }
    if ("percentage".equals(normalized) && (basisPoints == null || basisPoints <= 0 || basisPoints >= 10_000)) {
      throw new IllegalArgumentException("percentage rules need basisPoints between 1 and 9999");
    }
    if ("equal".equals(normalized)) {
      basisPoints = null;
    }
    if (personId != null) {
      requireFriend(userId, personId);
    } else {
      requireOwnedGroup(userId, groupId);
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into split_rules
          (id, user_id, name, merchant_match, method, person_id, group_id, basis_points)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        userId,
        label,
        merchant,
        normalized,
        personId,
        groupId,
        basisPoints);
    return new RuleRecord(id, label, merchant, normalized, personId, groupId, basisPoints);
  }

  /**
   * Builds a validated split for the first rule whose merchant text appears on
   * the charge. Empty when no rule applies.
   */
  public Optional<MatchedRule> match(
      UUID userId, String description, String merchantName, long amountCents) {
    String haystack = ((merchantName == null ? "" : merchantName) + " "
        + (description == null ? "" : description)).toLowerCase(Locale.ROOT);
    List<RuleRecord> rules = jdbc.query(
        """
        select id, name, merchant_match, method, person_id, group_id, basis_points
        from split_rules
        where user_id = ?
        order by length(merchant_match) desc, created_at desc
        """,
        (rs, n) -> new RuleRecord(
            UUID.fromString(rs.getString("id")),
            rs.getString("name"),
            rs.getString("merchant_match"),
            rs.getString("method"),
            rs.getString("person_id") == null ? null : UUID.fromString(rs.getString("person_id")),
            rs.getString("group_id") == null ? null : UUID.fromString(rs.getString("group_id")),
            rs.getObject("basis_points") == null ? null : rs.getInt("basis_points")),
        userId);
    for (RuleRecord rule : rules) {
      if (rule.merchantMatch() == null
          || !haystack.contains(rule.merchantMatch().toLowerCase(Locale.ROOT))) {
        continue;
      }
      List<UUID> friends = rule.personId() != null
          ? List.of(rule.personId())
          : memberIds(rule.groupId());
      friends = friends.stream().filter(id -> isFriend(userId, id)).toList();
      if (friends.isEmpty()) {
        continue;
      }
      try {
        SplitService.SplitRequest request = requestFor(rule, friends, amountCents);
        return Optional.of(new MatchedRule(rule.name(), friends, request));
      } catch (RuntimeException ignored) {
        // A rule that cannot be checked is skipped. The heuristic can still run.
      }
    }
    return Optional.empty();
  }

  private SplitService.SplitRequest requestFor(RuleRecord rule, List<UUID> friends, long amountCents) {
    List<SplitService.ShareRequest> shares = new ArrayList<>();
    if ("equal".equals(rule.method())) {
      shares.add(new SplitService.ShareRequest(null, null, null, null));
      for (UUID friend : friends) {
        shares.add(new SplitService.ShareRequest(friend, null, null, null));
      }
    } else {
      int friendPoints = rule.basisPoints();
      shares.add(new SplitService.ShareRequest(null, null, 10_000 - friendPoints, null));
      List<LargestRemainder.Allocation> parts = LargestRemainder.allocate(
          friendPoints,
          friends.stream().map(id -> new LargestRemainder.Party(id, 1.0)).toList());
      for (LargestRemainder.Allocation part : parts) {
        shares.add(new SplitService.ShareRequest(part.tieKey(), null, (int) part.cents(), null));
      }
    }
    SplitMethod method = "equal".equals(rule.method()) ? SplitMethod.equal : SplitMethod.percentage;
    SplitService.SplitRequest request = new SplitService.SplitRequest(method, 0L, 0L, shares);
    engine.compute(
        amountCents,
        request.method(),
        shares.stream()
            .map(share -> new SplitEngine.ShareInput(
                share.personId(), share.shareCents(), share.basisPoints(), share.itemizedCents()))
            .toList(),
        0L,
        0L);
    return request;
  }

  private List<UUID> memberIds(UUID groupId) {
    return jdbc.query(
        "select person_id from person_group_members where group_id = ? order by person_id",
        (rs, n) -> UUID.fromString(rs.getString("person_id")),
        groupId);
  }

  private List<UUID> friendsOnly(UUID userId, List<UUID> personIds) {
    if (personIds == null || personIds.isEmpty()) {
      throw new IllegalArgumentException("at least one friend is required");
    }
    List<UUID> unique = personIds.stream().distinct().toList();
    for (UUID personId : unique) {
      requireFriend(userId, personId);
    }
    return unique;
  }

  private void requireFriend(UUID userId, UUID personId) {
    PeopleService.PersonRecord person = people.requireOwned(userId, personId);
    if (!"friend".equals(person.kind())) {
      throw new IllegalArgumentException("split rules use friends");
    }
  }

  private boolean isFriend(UUID userId, UUID personId) {
    try {
      return "friend".equals(people.requireOwned(userId, personId).kind());
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private void requireOwnedGroup(UUID userId, UUID groupId) {
    Integer count = jdbc.queryForObject(
        "select count(*) from person_groups where id = ? and user_id = ?",
        Integer.class,
        groupId,
        userId);
    if (count == null || count == 0) {
      throw new IllegalArgumentException("group not found");
    }
  }

  private static String requiredName(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }

  public record GroupRecord(UUID id, String name, List<UUID> personIds) {}

  public record RuleRecord(
      UUID id,
      String name,
      String merchantMatch,
      String method,
      UUID personId,
      UUID groupId,
      Integer basisPoints) {}

  public record MatchedRule(String name, List<UUID> personIds, SplitService.SplitRequest request) {}
}
