package dev.even.ledger;

import dev.even.support.LedgerTestFixture;
import dev.even.support.PostgresIntegrationTest;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JqwikSpringSupport
class LedgerPropertyTest extends PostgresIntegrationTest {

  @Autowired
  LedgerService ledger;

  @Autowired
  JdbcTemplate jdbc;

  @Property(tries = 15)
  void balancedLineListsCommit(
      @ForAll @LongRange(min = 1, max = 1_000_000) long amountCents) {
    LedgerTestFixture fixture = new LedgerTestFixture(jdbc);
    UUID userId = fixture.insertUser("prop-" + UUID.randomUUID() + "@example.com");
    UUID accountId = fixture.insertCreditAccount(userId, "Card");
    UUID txnId = fixture.insertTransaction(
        userId, accountId, amountCents, "posted", "purchase", "Prop purchase");

    UUID groupId = ledger.post(
        userId,
        txnId,
        LedgerPurpose.capture,
        List.of(
            new LedgerLine(LedgerService.EXPENSES_UNCATEGORIZED, amountCents),
            new LedgerLine(LedgerService.liabilityPath(accountId), -amountCents)));

    Long sum = jdbc.queryForObject(
        "select coalesce(sum(amount_cents), 0) from ledger_entries where txn_group_id = ?",
        Long.class,
        groupId);
    assertThat(sum).isZero();
  }

  @Property(tries = 10)
  void unbalancedLineListsRollBack(
      @ForAll @LongRange(min = 1, max = 1_000_000) long debit,
      @ForAll @IntRange(min = 1, max = 500) int skew) {
    LedgerTestFixture fixture = new LedgerTestFixture(jdbc);
    UUID userId = fixture.insertUser("unbal-" + UUID.randomUUID() + "@example.com");
    UUID accountId = fixture.insertCreditAccount(userId, "Card");
    UUID txnId = fixture.insertTransaction(
        userId, accountId, debit, "posted", "purchase", "Unbalanced");

    int beforePostings = count("ledger_postings");
    int beforeEntries = count("ledger_entries");

    assertThatThrownBy(() -> ledger.post(
            userId,
            txnId,
            LedgerPurpose.capture,
            List.of(
                new LedgerLine(LedgerService.EXPENSES_UNCATEGORIZED, debit),
                new LedgerLine(LedgerService.liabilityPath(accountId), -(debit + skew)))))
        .isInstanceOf(LedgerException.class);

    assertThat(count("ledger_postings")).isEqualTo(beforePostings);
    assertThat(count("ledger_entries")).isEqualTo(beforeEntries);
  }

  private int count(String table) {
    Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
    return n == null ? 0 : n;
  }
}
