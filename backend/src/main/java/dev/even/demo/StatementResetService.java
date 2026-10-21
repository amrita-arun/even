package dev.even.demo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Returns the signed-in user to their imported statement. People, splits,
 * reimbursements, settlements, and the demo card are removed. Capture
 * postings on every other account stay.
 */
@Service
public class StatementResetService {

  private final JdbcTemplate jdbc;

  public StatementResetService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void reset(UUID userId) {
    jdbc.execute("alter table ledger_entries disable trigger ledger_entries_no_update");
    try {
      jdbc.update("delete from split_rules where user_id = ?", userId);
      jdbc.update(
          """
          delete from person_group_members
          where group_id in (select id from person_groups where user_id = ?)
          """,
          userId);
      jdbc.update("delete from person_groups where user_id = ?", userId);
      jdbc.update("delete from split_feedback where user_id = ?", userId);
      jdbc.update("delete from proposals where user_id = ?", userId);
      jdbc.update("delete from ai_audit_log where user_id = ?", userId);
      jdbc.update("delete from settlements where user_id = ?", userId);
      jdbc.update(
          """
          delete from split_shares
          where split_id in (select id from splits where user_id = ?)
          """,
          userId);
      jdbc.update("delete from splits where user_id = ?", userId);
      jdbc.update(
          """
          delete from ledger_entries
          where txn_group_id in (
            select txn_group_id from ledger_postings
            where user_id = ? and purpose in ('split', 'settlement')
          )
          """,
          userId);
      jdbc.update(
          """
          delete from ledger_postings
          where user_id = ? and purpose in ('split', 'settlement')
          """,
          userId);
      jdbc.update("delete from people where user_id = ?", userId);
      deleteMarkedTransactions(userId, "Settlement from %");
      deleteDemoAccounts(userId);
      jdbc.update("delete from jobs where user_id = ?", userId);
    } finally {
      jdbc.execute("alter table ledger_entries enable trigger ledger_entries_no_update");
    }
  }

  private void deleteDemoAccounts(UUID userId) {
    jdbc.update(
        """
        delete from flags
        where transaction_id in (
          select t.id from transactions t
          join accounts a on a.id = t.account_id
          where a.user_id = ? and a.name in ('Demo Card', 'Demo Checking')
        )
        """,
        userId);
    jdbc.update(
        """
        delete from ledger_entries
        where source_transaction_id in (
          select t.id from transactions t
          join accounts a on a.id = t.account_id
          where a.user_id = ? and a.name in ('Demo Card', 'Demo Checking')
        )
        """,
        userId);
    jdbc.update(
        """
        delete from ledger_postings
        where source_transaction_id in (
          select t.id from transactions t
          join accounts a on a.id = t.account_id
          where a.user_id = ? and a.name in ('Demo Card', 'Demo Checking')
        )
        """,
        userId);
    jdbc.update(
        """
        delete from transaction_external_ids
        where account_id in (
          select id from accounts
          where user_id = ? and name in ('Demo Card', 'Demo Checking')
        )
        """,
        userId);
    jdbc.update(
        """
        delete from transactions
        where account_id in (
          select id from accounts
          where user_id = ? and name in ('Demo Card', 'Demo Checking')
        )
        """,
        userId);
    jdbc.update(
        """
        delete from accounts
        where user_id = ? and name in ('Demo Card', 'Demo Checking')
        """,
        userId);
  }

  private void deleteMarkedTransactions(UUID userId, String descriptionLike) {
    jdbc.update(
        """
        delete from flags
        where transaction_id in (
          select id from transactions where user_id = ? and description like ?
        )
        """,
        userId,
        descriptionLike);
    jdbc.update(
        """
        delete from ledger_entries
        where source_transaction_id in (
          select id from transactions where user_id = ? and description like ?
        )
        """,
        userId,
        descriptionLike);
    jdbc.update(
        """
        delete from ledger_postings
        where source_transaction_id in (
          select id from transactions where user_id = ? and description like ?
        )
        """,
        userId,
        descriptionLike);
    jdbc.update(
        """
        delete from transaction_external_ids
        where transaction_id in (
          select id from transactions where user_id = ? and description like ?
        )
        """,
        userId,
        descriptionLike);
    jdbc.update(
        """
        delete from transactions
        where user_id = ? and description like ?
        """,
        userId,
        descriptionLike);
  }
}
