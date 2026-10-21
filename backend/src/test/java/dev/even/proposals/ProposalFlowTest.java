package dev.even.proposals;

import dev.even.accounts.AccountService;
import dev.even.ingestion.IngestService;
import dev.even.ingestion.ProviderTransaction;
import dev.even.ingestion.SyncPage;
import dev.even.jobs.TransactionProcessor;
import dev.even.ledger.LedgerPurpose;
import dev.even.ledger.LedgerService;
import dev.even.people.PeopleService;
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

class ProposalFlowTest extends PostgresIntegrationTest {

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
  }

  @Test
  void approvePostsOnceAndASecondApproveDoesNot() {
    UUID dinner = importPurchase("demo-dinner", 8_000L, "Dinner restaurant");
    processor.process(userId, dinner);

    List<ProposalService.ProposalRecord> pending = proposals.list(userId, "pending");
    assertThat(pending).hasSize(1);
    assertThat(pending.getFirst().personIds()).containsExactly(alex);
    assertThat(jdbc.queryForObject(
            "select model from ai_audit_log where user_id = ?", String.class, userId))
        .isEqualTo("heuristic-mock");

    proposals.approve(userId, pending.getFirst().id());
    assertThat(ledger.postingExists(dinner, LedgerPurpose.split)).isTrue();

    UUID proposalId = pending.getFirst().id();
    assertThatThrownBy(() -> proposals.approve(userId, proposalId))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.queryForObject(
            "select count(*) from ledger_postings where purpose = 'split'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void rejectWritesFeedbackAndDoesNotPost() {
    UUID coffee = importPurchase("demo-coffee", 7_000L, "Starbucks");
    processor.process(userId, coffee);
    ProposalService.ProposalRecord proposal = proposals.list(userId, "pending").getFirst();

    proposals.reject(userId, proposal.id());

    assertThat(ledger.postingExists(coffee, LedgerPurpose.split)).isFalse();
    assertThat(jdbc.queryForObject(
            "select action from split_feedback where proposal_id = ?", String.class, proposal.id()))
        .isEqualTo("reject");
    assertThat(proposals.list(userId, "pending")).isEmpty();
  }

  private UUID importPurchase(String externalId, long amountCents, String description) {
    ingest.apply(accountId, userId, SyncPage.ofAdded(List.of(new ProviderTransaction(
        externalId,
        Optional.empty(),
        amountCents,
        "USD",
        LocalDate.of(2026, 10, 14),
        "posted",
        description,
        "purchase",
        "{}"))));
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
