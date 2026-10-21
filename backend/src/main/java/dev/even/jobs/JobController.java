package dev.even.jobs;

import dev.even.auth.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

  private final JobService jobs;
  private final TransactionProcessor processor;
  private final JdbcTemplate jdbc;

  public JobController(JobService jobs, TransactionProcessor processor, JdbcTemplate jdbc) {
    this.jobs = jobs;
    this.processor = processor;
    this.jdbc = jdbc;
  }

  /**
   * Re-enrich and re-evaluate rules for every transaction owned by the caller.
   * Safe to repeat: flags stay unique on (transaction_id, rule_id).
   */
  @PostMapping("/reprocess")
  public ReprocessResponse reprocess(JwtAuthenticationToken authentication) {
    CurrentUser user = CurrentUser.from(authentication);
    List<UUID> txnIds = jdbc.query(
        "select id from transactions where user_id = ?",
        (rs, n) -> UUID.fromString(rs.getString("id")),
        user.id());
    int processed = 0;
    for (UUID txnId : txnIds) {
      processor.process(user.id(), txnId);
      processed++;
    }
    // Also drain any pending jobs for this user so the queue stays clean.
    return new ReprocessResponse(processed);
  }

  public record ReprocessResponse(int processed) {}
}
