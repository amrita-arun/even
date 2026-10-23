package dev.even.conversations;

import dev.even.accounts.AccountService;
import dev.even.ingestion.IngestService;
import dev.even.ingestion.ProviderTransaction;
import dev.even.ingestion.SyncPage;
import dev.even.jobs.TransactionProcessor;
import dev.even.ledger.LedgerPurpose;
import dev.even.ledger.LedgerService;
import dev.even.people.PeopleService;
import dev.even.proposals.ProposalService;
import dev.even.rules.RuleService;
import dev.even.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationFlowTest extends PostgresIntegrationTest {

  @Autowired
  AccountService accounts;

  @Autowired
  PeopleService people;

  @Autowired
  IngestService ingest;

  @Autowired
  TransactionProcessor processor;

  @Autowired
  ProposalService proposals;

  @Autowired
  RuleService rules;

  @Autowired
  LedgerService ledger;

  @Autowired
  ConversationService conversations;

  @Autowired
  SimulatedMessageChannel channel;

  @Autowired
  JdbcTemplate jdbc;

  UUID userId;
  UUID accountId;
  UUID alex;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    accountId = accounts.create(userId, "owner@example.com", "Capital One", "credit", "USD").id();
    alex = people.create(userId, "Alex", "Kim", null, null, null, "friend").id();
    rules.ensureDefaults(userId);
    conversations.setHandle(userId, "+15551212000");
  }

  @Test
  void unknownSenderIsIgnored() {
    importDinner("ignored-dinner");
    ConversationService.Turn turn = conversations.inbound("+15559990000", "Y", "ext-unknown");

    assertThat(turn.ignored()).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from channel_messages", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from conversations", Integer.class)).isZero();
    assertThat(people.list(userId)).hasSize(1);
    assertThat(ledger.postingExists(dinnerId("ignored-dinner"), LedgerPurpose.split)).isFalse();
  }

  @Test
  void yesOutsideConfirmingDoesNotPost() {
    UUID dinner = openDinner("asking-dinner");
    channel.deliver(userId, "Hey", null);
    ConversationService.Turn yes = channel.deliver(userId, "Y", null);

    assertThat(yes.state()).isEqualTo("asking_who");
    assertThat(yes.reply()).contains("Who was with you");
    assertThat(ledger.postingExists(dinner, LedgerPurpose.split)).isFalse();
  }

  @Test
  void unknownNameIsNotCreatedUntilYesAndTheNamesAreCollected() {
    UUID dinner = openDinner("sam-dinner");
    channel.deliver(userId, "Hey", null);
    ConversationService.Turn named = channel.deliver(userId, "Sam", null);

    assertThat(named.state()).isEqualTo("collecting_last_name");
    assertThat(named.reply()).contains("Last name and Venmo");
    assertThat(people.list(userId)).extracting(PeopleService.PersonRecord::displayName)
        .doesNotContain("Sam Lee");
    assertThat(ledger.postingExists(dinner, LedgerPurpose.split)).isFalse();

    ConversationService.Turn venmo = channel.deliver(userId, "Lee, sam-lee", null);

    assertThat(venmo.state()).isEqualTo("confirming");
    assertThat(people.list(userId)).extracting(PeopleService.PersonRecord::displayName)
        .contains("Sam Lee");
    assertThat(people.list(userId)).extracting(PeopleService.PersonRecord::venmoHandle)
        .contains("sam-lee");
    assertThat(ledger.postingExists(dinner, LedgerPurpose.split)).isFalse();
  }

  @Test
  void textAndWebApprovePostOnce() {
    people.update(userId, alex, null, null, "alex-kim", null);
    UUID dinner = openDinner("confirm-dinner");
    channel.deliver(userId, "Hey", null);
    ConversationService.Turn named = channel.deliver(userId, "Alex", null);
    assertThat(named.state()).isEqualTo("confirming");
    assertThat(named.reply()).contains("Reply Y to split it");
    assertThat(ledger.postingExists(dinner, LedgerPurpose.split)).isFalse();

    channel.deliver(userId, "Y", null);
    assertThat(ledger.postingExists(dinner, LedgerPurpose.split)).isTrue();
    assertThat(splitPostings()).isEqualTo(1);

    UUID proposalId = proposals.list(userId, "approved").getFirst().id();
    assertThatThrownBy(() -> proposals.approve(userId, proposalId))
        .isInstanceOf(IllegalArgumentException.class);
    channel.deliver(userId, "Y", null);
    assertThat(splitPostings()).isEqualTo(1);

    UUID second = openDinner("web-dinner");
    channel.deliver(userId, "Hey", null);
    channel.deliver(userId, "Alex", null);
    proposals.approve(userId, proposals.list(userId, "pending").getFirst().id());
    channel.deliver(userId, "Y", null);
    assertThat(ledger.postingExists(second, LedgerPurpose.split)).isTrue();
    assertThat(splitPostings()).isEqualTo(2);
  }

  @Test
  void oneSentenceNamesBothPeopleAndStillWaitsForYes() {
    UUID dinner = openDinner("sentence-dinner");
    ConversationService.Turn named = channel.deliver(
        userId, "reeya sawhney venmo reeyas, alex had the drinks", null);

    assertThat(named.state()).isEqualTo("collecting_venmo");
    assertThat(named.reply()).contains("Alex Kim").contains("Venmo");
    assertThat(people.list(userId)).extracting(PeopleService.PersonRecord::displayName)
        .contains("Reeya Sawhney");
    assertThat(people.list(userId)).extracting(PeopleService.PersonRecord::venmoHandle)
        .contains("reeyas");
    assertThat(ledger.postingExists(dinner, LedgerPurpose.split)).isFalse();

    ConversationService.Turn confirm = channel.deliver(userId, "alex-kim", null);
    assertThat(confirm.state()).isEqualTo("confirming");
    assertThat(confirm.reply()).contains("Reeya Sawhney").contains("Alex Kim").contains("Reply Y to split it");
    assertThat(ledger.postingExists(dinner, LedgerPurpose.split)).isFalse();

    channel.deliver(userId, "Y", null);
    assertThat(ledger.postingExists(dinner, LedgerPurpose.split)).isTrue();
    assertThat(splitPostings()).isEqualTo(1);
  }

  private int splitPostings() {
    return jdbc.queryForObject(
        "select count(*) from ledger_postings where purpose = 'split'", Integer.class);
  }

  private UUID openDinner(String externalId) {
    UUID id = importDinner(externalId);
    processor.process(userId, id);
    return id;
  }

  private UUID importDinner(String externalId) {
    ingest.apply(accountId, userId, SyncPage.ofAdded(List.of(new ProviderTransaction(
        externalId,
        Optional.empty(),
        8_000L,
        "USD",
        LocalDate.of(2026, 10, 14),
        "posted",
        "Dinner restaurant",
        "purchase",
        "{}"))));
    return dinnerId(externalId);
  }

  private UUID dinnerId(String externalId) {
    return jdbc.queryForObject(
        """
        select transaction_id from transaction_external_ids
        where account_id = ? and external_id = ?
        """,
        UUID.class,
        accountId,
        externalId);
  }
}
