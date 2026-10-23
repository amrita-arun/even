package dev.even.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Shared Postgres for all Spring integration tests. Started once as a JVM singleton
 * (not via {@code @Container}) so the mapped port stays stable across test classes
 * and Spring's cached ApplicationContext keeps a valid JDBC URL.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("db/step1_core.sql"),
            "/docker-entrypoint-initdb.d/01_step1_core.sql")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("db/step2_splits.sql"),
            "/docker-entrypoint-initdb.d/02_step2_splits.sql")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("db/step3_rules_flags.sql"),
            "/docker-entrypoint-initdb.d/03_step3_rules_flags.sql")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("db/step4_plaid.sql"),
            "/docker-entrypoint-initdb.d/04_step4_plaid.sql")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("db/step5_settlement.sql"),
            "/docker-entrypoint-initdb.d/05_step5_settlement.sql")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("db/step5b_person_names.sql"),
            "/docker-entrypoint-initdb.d/06_person_names.sql")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("db/step5c_person_kind.sql"),
            "/docker-entrypoint-initdb.d/07_person_kind.sql")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("db/step6_proposals.sql"),
            "/docker-entrypoint-initdb.d/08_proposals.sql")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("db/step6b_proposal_split_request.sql"),
            "/docker-entrypoint-initdb.d/09_proposal_split_request.sql")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("db/step7_split_rules.sql"),
            "/docker-entrypoint-initdb.d/10_split_rules.sql")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("db/step8_conversations.sql"),
            "/docker-entrypoint-initdb.d/11_conversations.sql");
    POSTGRES.start();
    // Init scripts only run on a fresh data dir; apply later steps if a reused
    // container predates those migrations.
    ensureSchema("accounts", "plaid_item_id", "db/step4_plaid.sql");
    ensureSchema("settlements", "id", "db/step5_settlement.sql");
    ensureSchema("people", "first_name", "db/step5b_person_names.sql");
    ensureSchema("people", "kind", "db/step5c_person_kind.sql");
    ensureSchema("proposals", "id", "db/step6_proposals.sql");
    ensureSchema("proposals", "split_request", "db/step6b_proposal_split_request.sql");
    ensureSchema("split_rules", "id", "db/step7_split_rules.sql");
    ensureSchema("users", "imessage_handle", "db/step8_conversations.sql");
  }

  private static void ensureSchema(String table, String column, String classpathSql) {
    try (Connection conn = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             """
             select 1 from information_schema.columns
             where table_schema = 'public'
               and table_name = '%s'
               and column_name = '%s'
             """.formatted(table, column))) {
      if (rs.next()) {
        return;
      }
    } catch (Exception e) {
      throw new IllegalStateException("failed checking schema for " + classpathSql, e);
    }
    try (Connection conn = DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ScriptUtils.executeSqlScript(conn, new ClassPathResource(classpathSql));
    } catch (Exception e) {
      throw new IllegalStateException("failed applying " + classpathSql, e);
    }
  }

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void truncateAllTables() {
    jdbcTemplate.execute(
        """
        truncate table
          channel_messages,
          conversations,
          split_rules,
          person_group_members,
          person_groups,
          split_feedback,
          proposals,
          ai_audit_log,
          settlements,
          flags,
          rules,
          jobs,
          merchant_aliases,
          merchants,
          split_shares,
          splits,
          people,
          ledger_entries,
          ledger_postings,
          ledger_accounts,
          transaction_external_ids,
          transactions,
          accounts,
          plaid_items,
          institutions,
          users
        cascade
        """);
  }

  @DynamicPropertySource
  static void datasourceProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("SUPABASE_URL", () -> "https://test.supabase.co");
    registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    registry.add("SUPABASE_JWKS_URL", () -> "");
    registry.add("even.supabase.jwt-secret",
        () -> "test-secret-key-at-least-thirty-two-chars");
    registry.add("even.jobs.poll-delay-ms", () -> "60000");
  }
}
