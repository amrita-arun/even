package dev.even.conversations;

import dev.even.auth.UserRepository;
import dev.even.ingestion.MoneyParser;
import dev.even.people.PeopleService;
import dev.even.proposals.ProposalService;
import dev.even.settlement.SettlementService;
import dev.even.splits.SplitEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One open question at a time. A journal write happens only from {@code confirming}.
 */
@Service
public class ConversationService {

  private static final Pattern MONEY = Pattern.compile(
      "\\$\\s*(\\d{1,7}(?:\\.\\d{1,2})?)|(?<![\\d.])(\\d{1,7}\\.\\d{2})(?![\\d])");

  private final JdbcTemplate jdbc;
  private final UserRepository users;
  private final PeopleService people;
  private final ProposalService proposals;
  private final SettlementService settlements;
  private final String ownerHandle;
  private final String linePhone;

  public ConversationService(
      JdbcTemplate jdbc,
      UserRepository users,
      PeopleService people,
      ProposalService proposals,
      SettlementService settlements,
      @Value("${even.imessage.owner-handle:}") String ownerHandle,
      @Value("${even.spectrum.line-phone:}") String linePhone) {
    this.jdbc = jdbc;
    this.users = users;
    this.people = people;
    this.proposals = proposals;
    this.settlements = settlements;
    this.ownerHandle = ownerHandle;
    this.linePhone = linePhone == null ? "" : linePhone.trim();
  }

  public Turn inbound(String from, String text, String externalId) {
    String handle = normalizeHandle(from);
    if (handle.isBlank()) {
      return Turn.skipped();
    }
    return users.findByHandle(handle)
        .or(() -> claimOwnerHandle(handle))
        .map(user -> receive(user.id(), text, externalId))
        .orElseGet(Turn::skipped);
  }

  public Turn receive(UUID userId, String text, String externalId) {
    if (externalExists(userId, externalId)) {
      return new Turn(false, null, existingReply(userId, externalId), null);
    }
    if (text == null || text.isBlank()) {
      return new Turn(false, null, "Send a message when you're ready.", null);
    }
    expire(userId);
    Conv open = findOpen(userId);
    boolean created = false;
    if (open == null) {
      open = openNext(userId);
      created = open != null;
    }
    if (open == null) {
      return new Turn(false, null, "Nothing is waiting on you right now.", null);
    }
    if (created && opensWithQuestion(open, text)) {
      String opening = openingFor(open);
      insertInbound(open, text.trim(), externalId, opening);
      insertOutbound(open, opening, false);
      return new Turn(false, open.id(), opening, open.state());
    }
    String reply = step(open, text.trim());
    insertInbound(open, text.trim(), externalId, reply);
    insertOutbound(open, reply, false);
    Conv current = findOpen(userId);
    String state = current == null ? "closed" : current.state();
    return new Turn(false, open.id(), reply, state);
  }

  public ConversationView current(UUID userId) {
    ensureOwnerHandle(userId);
    expire(userId);
    Conv open = findOpen(userId);
    if (open == null) {
      open = openNext(userId);
    }
    if (open != null && messageCount(open.id()) == 0) {
      insertOutbound(open, openingFor(open), false);
    }
    return view(userId, open != null ? open : latest(userId));
  }

  public void setHandle(UUID userId, String handle) {
    String normalized = handle == null ? "" : normalizeHandle(handle);
    users.setHandle(userId, normalized.isBlank() ? null : normalized);
  }

  public void onProposalDecided(UUID userId, UUID proposalId, boolean approved) {
    Conv open = findOpen(userId);
    if (open == null || open.proposalId() == null || !open.proposalId().equals(proposalId)) {
      return;
    }
    close(open.id(), approved ? "confirmed" : "rejected");
    insertOutbound(
        open,
        approved
            ? "Confirmed on the web. The split is posted."
            : "Dismissed on the web. Nothing was posted.",
        true);
  }

  public void onSettlementDecided(UUID userId, UUID settlementId, boolean approved) {
    Conv open = findOpen(userId);
    if (open == null || open.settlementId() == null || !open.settlementId().equals(settlementId)) {
      return;
    }
    close(open.id(), approved ? "confirmed" : "rejected");
    insertOutbound(
        open,
        approved
            ? "Confirmed on the web. The payment is recorded."
            : "Left open on the web. The receivable stays.",
        true);
  }

  public List<Outbound> pendingOutbound() {
    return jdbc.query(
        """
        select m.id, u.imessage_handle, m.body
        from channel_messages m
        join users u on u.id = m.user_id
        where m.deliver = true and m.sent_at is null and m.direction = 'outbound'
          and u.imessage_handle is not null
        order by m.created_at
        limit 20
        """,
        (rs, n) -> new Outbound(
            UUID.fromString(rs.getString("id")),
            rs.getString("imessage_handle"),
            rs.getString("body")));
  }

  public void markSent(UUID messageId) {
    jdbc.update(
        "update channel_messages set sent_at = clock_timestamp() where id = ? and deliver = true",
        messageId);
  }

  private String step(Conv conv, String text) {
    return switch (conv.state()) {
      case "asking_who" -> onAskingWho(conv, text);
      case "confirming_new_person", "collecting_first_name", "collecting_last_name", "collecting_venmo" ->
          onDetails(conv, text);
      case "confirming" -> onConfirming(conv, text);
      default -> "Reply Y only when I ask you to confirm.";
    };
  }

  private String onAskingWho(Conv conv, String text) {
    if (affirmative(text) || negative(text) || greeting(text)) {
      return openingFor(conv);
    }
    return acceptParty(conv, PartyReader.read(text));
  }

  private String onDetails(Conv conv, String text) {
    if (greeting(text)) {
      return askFor(pending(conv));
    }
    PartyReader.Mention current = pending(conv);
    PartyReader.Mention filled = PartyReader.fill(current, text);
    List<PartyReader.Mention> waiting = new ArrayList<>();
    if (!filled.complete() && (filled.personId() == null || blank(filled.venmo()))) {
      if (sameGap(current, filled)) {
        return askFor(current);
      }
      waiting.add(filled);
    } else {
      String saved = saveMention(conv, filled);
      if (saved != null) {
        return saved;
      }
    }
    waiting.addAll(PartyReader.decode(conv.pendingName()));
    if (waiting.isEmpty()) {
      return enterConfirming(reload(conv.id()));
    }
    return continueParty(conv, List.of(), waiting, "");
  }

  private String acceptParty(Conv conv, List<PartyReader.Mention> mentions) {
    List<UUID> ready = new ArrayList<>();
    List<PartyReader.Mention> waiting = new ArrayList<>();
    StringBuilder notes = new StringBuilder();
    for (PartyReader.Mention mention : mentions) {
      if (!mention.named()) {
        continue;
      }
      List<PeopleService.PersonRecord> friends = match(conv.userId(), mention, "friend");
      if (friends.size() > 1) {
        return "Which " + mention.first() + "? Reply with their full name.";
      }
      if (friends.size() == 1) {
        PeopleService.PersonRecord friend = friends.getFirst();
        String venmo = blank(friend.venmoHandle()) ? mention.venmo() : friend.venmoHandle();
        if (blank(venmo)) {
          waiting.add(new PartyReader.Mention(friend.firstName(), friend.lastName(), null, friend.id()));
        } else if (blank(friend.venmoHandle())) {
          people.update(conv.userId(), friend.id(), null, null, venmo, null);
          ready.add(friend.id());
        } else {
          ready.add(friend.id());
        }
        continue;
      }
      List<PeopleService.PersonRecord> reimbursements = match(conv.userId(), mention, "reimbursement");
      if (!reimbursements.isEmpty()) {
        if (!notes.isEmpty()) {
          notes.append(' ');
        }
        notes.append(reimbursements.getFirst().displayName())
            .append(" is a reimbursement, so I left them off.");
        continue;
      }
      if (mention.complete()) {
        PeopleService.PersonRecord created = people.create(
            conv.userId(),
            mention.first(),
            mention.last(),
            null,
            mention.venmo(),
            null,
            "friend");
        ready.add(created.id());
      } else {
        waiting.add(mention);
      }
    }
    return continueParty(conv, ready, waiting, notes.toString());
  }

  private String continueParty(
      Conv conv, List<UUID> ready, List<PartyReader.Mention> waiting, String notes) {
    try {
      rememberPeople(conv, ready);
    } catch (IllegalArgumentException ex) {
      return ex.getMessage();
    }
    String prefix = notes == null || notes.isBlank() ? "" : notes + " ";
    if (!waiting.isEmpty()) {
      PartyReader.Mention current = waiting.getFirst();
      List<PartyReader.Mention> rest = waiting.size() == 1 ? List.of() : waiting.subList(1, waiting.size());
      updateState(
          conv.id(),
          detailState(current),
          current.personId(),
          PartyReader.encode(rest),
          current.first(),
          current.last());
      return prefix + askFor(current);
    }
    if (ready.isEmpty()) {
      return prefix + openingFor(conv);
    }
    return prefix + enterConfirming(reload(conv.id()));
  }

  private String saveMention(Conv conv, PartyReader.Mention mention) {
    try {
      UUID personId = mention.personId();
      if (personId != null) {
        if (!blank(mention.venmo())) {
          people.update(conv.userId(), personId, null, null, mention.venmo(), null);
        }
      } else {
        personId = people.create(
            conv.userId(),
            mention.first(),
            mention.last(),
            null,
            mention.venmo(),
            null,
            "friend").id();
      }
      rememberPeople(conv, List.of(personId));
      return null;
    } catch (IllegalArgumentException ex) {
      return ex.getMessage();
    }
  }

  private boolean opensWithQuestion(Conv conv, String text) {
    if (conv.proposalId() == null || greeting(text) || affirmative(text) || negative(text)) {
      return true;
    }
    return PartyReader.read(text).stream().noneMatch(PartyReader.Mention::named);
  }

  private PartyReader.Mention pending(Conv conv) {
    String first = conv.pendingFirstName();
    if (blank(first) && conv.pendingName() != null && !conv.pendingName().contains("\t")) {
      first = conv.pendingName().trim();
    }
    return new PartyReader.Mention(first, conv.pendingLastName(), null, conv.pendingPersonId());
  }

  private static boolean sameGap(PartyReader.Mention before, PartyReader.Mention after) {
    return eq(before.first(), after.first())
        && eq(before.last(), after.last())
        && eq(before.venmo(), after.venmo());
  }

  private static boolean eq(String left, String right) {
    String a = left == null ? "" : left;
    String b = right == null ? "" : right;
    return a.equalsIgnoreCase(b);
  }

  private static String detailState(PartyReader.Mention mention) {
    if (blank(mention.last())) {
      return "collecting_last_name";
    }
    return "collecting_venmo";
  }

  private static String askFor(PartyReader.Mention mention) {
    String name = blank(mention.last()) ? mention.first() : mention.first() + " " + mention.last();
    if (blank(mention.last()) && blank(mention.venmo())) {
      return "Last name and Venmo for " + name + "? Like Sawhney, reeyas.";
    }
    if (blank(mention.last())) {
      return "What's " + name + "'s last name?";
    }
    return "What's " + name + "'s Venmo?";
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private String onConfirming(Conv conv, String text) {
    if (conv.proposalId() != null) {
      return confirmProposal(conv, text);
    }
    return confirmSettlement(conv, text);
  }

  private String confirmProposal(Conv conv, String text) {
    if (negative(text)) {
      proposals.reject(conv.userId(), conv.proposalId());
      close(conv.id(), "rejected");
      return "Dismissed. Nothing was posted.";
    }
    if (!affirmative(text)) {
      return confirmText(conv.userId(), conv.proposalId());
    }
    try {
      proposals.approve(conv.userId(), conv.proposalId());
      close(conv.id(), "confirmed");
      return "Confirmed. The split is posted.";
    } catch (IllegalArgumentException ex) {
      String status = proposals.list(conv.userId(), null).stream()
          .filter(row -> row.id().equals(conv.proposalId()))
          .map(ProposalService.ProposalRecord::status)
          .findFirst()
          .orElse("");
      if ("approved".equals(status)) {
        close(conv.id(), "confirmed");
        return "That split is already confirmed.";
      }
      throw ex;
    }
  }

  private String confirmSettlement(Conv conv, String text) {
    if (negative(text)) {
      settlements.reject(conv.userId(), conv.settlementId());
      close(conv.id(), "rejected");
      return "Left open. The receivable stays.";
    }
    if (!affirmative(text)) {
      return "Reply Y to record the payment, or no to leave it open.";
    }
    settlements.confirm(conv.userId(), conv.settlementId());
    close(conv.id(), "confirmed");
    return "Confirmed. The payment is recorded.";
  }

  private String enterConfirming(Conv conv) {
    jdbc.update("update conversations set state = 'confirming' where id = ?", conv.id());
    if (conv.proposalId() != null) {
      return confirmText(conv.userId(), conv.proposalId());
    }
    return settlementText(conv.userId(), conv.settlementId());
  }

  private void rememberPeople(Conv conv, List<UUID> personIds) {
    if (conv.proposalId() == null || personIds == null || personIds.isEmpty()) {
      return;
    }
    ProposalService.ProposalRecord proposal = proposals.list(conv.userId(), null).stream()
        .filter(row -> row.id().equals(conv.proposalId()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("proposal not found"));
    LinkedHashSet<UUID> ids = new LinkedHashSet<>(proposal.personIds());
    int before = ids.size();
    ids.addAll(personIds);
    if (ids.size() == before) {
      return;
    }
    proposals.edit(conv.userId(), conv.proposalId(), List.copyOf(ids));
  }

  private String confirmText(UUID userId, UUID proposalId) {
    ProposalService.ProposalRecord proposal = proposals.list(userId, null).stream()
        .filter(row -> row.id().equals(proposalId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("proposal not found"));
    List<SplitEngine.ComputedShare> shares = proposals.previewShares(userId, proposalId);
    Set<Long> allowed = new HashSet<>();
    allowed.add(proposal.amountCents());
    StringBuilder sentence = new StringBuilder();
    for (SplitEngine.ComputedShare share : shares) {
      allowed.add(share.shareCents());
      String label = share.personId() == null
          ? "You"
          : people.requireOwned(userId, share.personId()).displayName();
      if (!sentence.isEmpty()) {
        sentence.append(", ");
      }
      sentence.append(label).append(' ').append(money(share.shareCents()));
    }
    String withMerchant = proposal.description() + " is " + money(proposal.amountCents())
        + ". " + sentence + ". Reply Y to split it, or no to skip.";
    String template = "This charge is " + money(proposal.amountCents())
        + ". " + sentence + ". Reply Y to split it, or no to skip.";
    return guard(withMerchant, allowed, template);
  }

  private String settlementText(UUID userId, UUID settlementId) {
    SettlementService.SettlementRecord row = settlements.list(userId, null).stream()
        .filter(item -> item.id().equals(settlementId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("settlement not found"));
    String text = row.displayName() + " may have paid you " + money(row.amountCents())
        + ". Reply Y to record it, or no to leave it open.";
    return guard(text, Set.of(row.amountCents()), text);
  }

  private String openingFor(Conv conv) {
    if (conv.proposalId() != null) {
      ProposalService.ProposalRecord proposal = proposals.list(conv.userId(), null).stream()
          .filter(row -> row.id().equals(conv.proposalId()))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("proposal not found"));
      return "Who was with you on the " + money(proposal.amountCents())
          + " " + proposal.description() + "?";
    }
    return settlementText(conv.userId(), conv.settlementId());
  }

  private Conv openNext(UUID userId) {
    List<ProposalService.ProposalRecord> pending = proposals.list(userId, "pending");
    if (!pending.isEmpty()) {
      return insertOpen(userId, pending.getLast().id(), null, "asking_who");
    }
    List<SettlementService.SettlementRecord> suggested = settlements.list(userId, "suggested");
    if (!suggested.isEmpty()) {
      return insertOpen(userId, null, suggested.getLast().id(), "confirming");
    }
    return null;
  }

  private Conv insertOpen(UUID userId, UUID proposalId, UUID settlementId, String state) {
    UUID id = UUID.randomUUID();
    try {
      jdbc.update(
          """
          insert into conversations
            (id, user_id, proposal_id, settlement_id, status, state, expires_at)
          values (?, ?, ?, ?, 'open', ?, ?)
          """,
          id,
          userId,
          proposalId,
          settlementId,
          state,
          Timestamp.from(Instant.now().plus(Duration.ofDays(7))));
    } catch (DataIntegrityViolationException ex) {
      return findOpen(userId);
    }
    return reload(id);
  }

  private Optional<UserRepository.UserRecord> claimOwnerHandle(String handle) {
    String expected = ownerHandle == null ? "" : normalizeHandle(ownerHandle);
    if (expected.isBlank() || !expected.equals(handle)) {
      return Optional.empty();
    }
    UserRepository.UserRecord only = users.onlyUser().orElse(null);
    if (only == null || (only.imessageHandle() != null && !only.imessageHandle().isBlank())) {
      return Optional.empty();
    }
    try {
      users.setHandle(only.id(), handle);
    } catch (IllegalArgumentException ex) {
      return users.findByHandle(handle);
    }
    return users.findById(only.id());
  }

  private void ensureOwnerHandle(UUID userId) {
    if (ownerHandle == null || ownerHandle.isBlank()) {
      return;
    }
    String normalized = normalizeHandle(ownerHandle);
    if (normalized.isBlank()) {
      return;
    }
    UserRepository.UserRecord user = users.findById(userId).orElse(null);
    if (user == null || (user.imessageHandle() != null && !user.imessageHandle().isBlank())) {
      return;
    }
    if (users.findByHandle(normalized).isPresent()) {
      return;
    }
    users.setHandle(userId, normalized);
  }

  private void expire(UUID userId) {
    jdbc.update(
        """
        update conversations
        set status = 'expired', closed_at = clock_timestamp()
        where user_id = ? and status = 'open' and expires_at <= clock_timestamp()
        """,
        userId);
  }

  private void close(UUID id, String status) {
    jdbc.update(
        """
        update conversations
        set status = ?, closed_at = clock_timestamp()
        where id = ? and status = 'open'
        """,
        status,
        id);
  }

  private void updateState(
      UUID id, String state, UUID personId, String pendingName, String first, String last) {
    jdbc.update(
        """
        update conversations
        set state = ?, pending_person_id = ?, pending_name = ?,
            pending_first_name = ?, pending_last_name = ?
        where id = ?
        """,
        state,
        personId,
        pendingName,
        first,
        last,
        id);
  }

  private void insertInbound(Conv conv, String body, String externalId, String reply) {
    jdbc.update(
        """
        insert into channel_messages
          (id, conversation_id, user_id, direction, body, external_id, reply_body)
        values (?, ?, ?, 'inbound', ?, ?, ?)
        """,
        UUID.randomUUID(),
        conv.id(),
        conv.userId(),
        body,
        blankToNull(externalId),
        reply);
  }

  private void insertOutbound(Conv conv, String body, boolean deliver) {
    jdbc.update(
        """
        insert into channel_messages
          (id, conversation_id, user_id, direction, body, deliver)
        values (?, ?, ?, 'outbound', ?, ?)
        """,
        UUID.randomUUID(),
        conv.id(),
        conv.userId(),
        body,
        deliver);
  }

  private boolean externalExists(UUID userId, String externalId) {
    if (externalId == null || externalId.isBlank()) {
      return false;
    }
    Integer count = jdbc.queryForObject(
        "select count(*) from channel_messages where user_id = ? and external_id = ?",
        Integer.class,
        userId,
        externalId);
    return count != null && count > 0;
  }

  private String existingReply(UUID userId, String externalId) {
    if (!externalExists(userId, externalId)) {
      return null;
    }
    List<String> rows = jdbc.query(
        "select reply_body from channel_messages where user_id = ? and external_id = ?",
        (rs, n) -> rs.getString("reply_body"),
        userId,
        externalId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private int messageCount(UUID conversationId) {
    Integer count = jdbc.queryForObject(
        "select count(*) from channel_messages where conversation_id = ?",
        Integer.class,
        conversationId);
    return count == null ? 0 : count;
  }

  private Conv findOpen(UUID userId) {
    List<Conv> rows = jdbc.query(
        """
        select id, user_id, proposal_id, settlement_id, status, state,
               pending_name, pending_first_name, pending_last_name, pending_person_id
        from conversations
        where user_id = ? and status = 'open'
        """,
        this::mapConv,
        userId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Conv latest(UUID userId) {
    List<Conv> rows = jdbc.query(
        """
        select id, user_id, proposal_id, settlement_id, status, state,
               pending_name, pending_first_name, pending_last_name, pending_person_id
        from conversations
        where user_id = ?
        order by opened_at desc
        limit 1
        """,
        this::mapConv,
        userId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Conv reload(UUID id) {
    return jdbc.query(
        """
        select id, user_id, proposal_id, settlement_id, status, state,
               pending_name, pending_first_name, pending_last_name, pending_person_id
        from conversations where id = ?
        """,
        this::mapConv,
        id).getFirst();
  }

  private Conv mapConv(ResultSet rs, int n) throws SQLException {
    return new Conv(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("user_id")),
        uuid(rs, "proposal_id"),
        uuid(rs, "settlement_id"),
        rs.getString("status"),
        rs.getString("state"),
        rs.getString("pending_name"),
        rs.getString("pending_first_name"),
        rs.getString("pending_last_name"),
        uuid(rs, "pending_person_id"));
  }

  private ConversationView view(UUID userId, Conv conv) {
    UserRepository.UserRecord user = users.findById(userId).orElse(null);
    String handle = user == null ? null : user.imessageHandle();
    String sms = linePhone.isBlank()
        ? null
        : "sms:" + linePhone + "&body=" + URLEncoder.encode("Hey", StandardCharsets.UTF_8);
    if (conv == null) {
      return new ConversationView(handle, blankToNull(linePhone), sms, null, null, null, null, List.of());
    }
    List<MessageView> messages = jdbc.query(
        """
        select id, direction, body, created_at
        from channel_messages
        where conversation_id = ?
        order by created_at, id
        """,
        (rs, n) -> new MessageView(
            UUID.fromString(rs.getString("id")),
            rs.getString("direction"),
            rs.getString("body"),
            rs.getTimestamp("created_at").toInstant().toString()),
        conv.id());
    return new ConversationView(
        handle,
        blankToNull(linePhone),
        sms,
        conv.id(),
        conv.status(),
        conv.state(),
        conv.proposalId(),
        messages);
  }

  private static String guard(String draft, Set<Long> allowed, String template) {
    List<Long> amounts = amounts(draft);
    boolean matches = !amounts.isEmpty() && amounts.stream().allMatch(allowed::contains);
    return matches ? draft : template;
  }

  private static List<Long> amounts(String text) {
    List<Long> found = new java.util.ArrayList<>();
    Matcher matcher = MONEY.matcher(text);
    while (matcher.find()) {
      String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
      found.add(MoneyParser.dollarsToCents(raw));
    }
    return found;
  }

  static String money(long cents) {
    long abs = Math.abs(cents);
    return (cents < 0 ? "-$" : "$") + (abs / 100) + "." + String.format(Locale.US, "%02d", abs % 100);
  }

  static String normalizeHandle(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim();
    if (trimmed.isBlank()) {
      return "";
    }
    if (trimmed.regionMatches(true, 0, "tel:", 0, 4)) {
      trimmed = trimmed.substring(4).trim();
    }
    if (trimmed.contains("@")) {
      return trimmed.toLowerCase(Locale.US);
    }
    String digits = trimmed.replaceAll("[^0-9]", "");
    if (digits.length() == 10) {
      return "+1" + digits;
    }
    if (digits.length() == 11 && digits.startsWith("1")) {
      return "+" + digits;
    }
    if (trimmed.startsWith("+") && digits.length() >= 8) {
      return "+" + digits;
    }
    return trimmed;
  }

  private static boolean affirmative(String text) {
    return switch (norm(text)) {
      case "y", "yes", "yeah", "yep" -> true;
      default -> false;
    };
  }

  private static boolean negative(String text) {
    return switch (norm(text)) {
      case "n", "no", "nope", "nah" -> true;
      default -> false;
    };
  }

  private static boolean greeting(String text) {
    return switch (norm(text)) {
      case "hey", "hi", "hello", "hey even", "hi even" -> true;
      default -> false;
    };
  }

  private List<PeopleService.PersonRecord> match(UUID userId, PartyReader.Mention mention, String kind) {
    String full = blank(mention.last()) ? mention.first() : mention.first() + " " + mention.last();
    return people.list(userId).stream()
        .filter(person -> kind.equals(person.kind()) && nameMatch(person, full))
        .toList();
  }

  private static boolean nameMatch(PeopleService.PersonRecord person, String raw) {
    String needle = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
    if (needle.isBlank()) {
      return false;
    }
    String first = lower(person.firstName());
    String last = lower(person.lastName());
    if (needle.equals(lower(person.displayName())) || (!first.isBlank() && needle.equals(first))) {
      return true;
    }
    String both = (first + " " + last).trim();
    return !both.isBlank() && needle.equals(both);
  }

  private static String lower(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.US);
  }

  private static String norm(String text) {
    return text == null ? "" : text.trim().toLowerCase(Locale.US).replaceAll("[.!?]", "");
  }

  private static UUID uuid(ResultSet rs, String column) throws SQLException {
    String value = rs.getString(column);
    return value == null ? null : UUID.fromString(value);
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private record Conv(
      UUID id,
      UUID userId,
      UUID proposalId,
      UUID settlementId,
      String status,
      String state,
      String pendingName,
      String pendingFirstName,
      String pendingLastName,
      UUID pendingPersonId) {}

  public record Turn(boolean ignored, UUID conversationId, String reply, String state) {
    public static Turn skipped() {
      return new Turn(true, null, null, null);
    }
  }

  public record Outbound(UUID id, String to, String body) {}

  public record MessageView(UUID id, String direction, String body, String createdAt) {}

  public record ConversationView(
      String handle,
      String linePhone,
      String smsHref,
      UUID conversationId,
      String status,
      String state,
      UUID proposalId,
      List<MessageView> messages) {}
}
