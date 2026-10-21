package dev.even.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

  public static final String PROCESS_TRANSACTION = "process_transaction";

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JobService(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional
  public UUID enqueueProcessTransaction(UUID userId, UUID transactionId) {
    UUID id = UUID.randomUUID();
    ObjectNode payload = mapper.createObjectNode().put("transactionId", transactionId.toString());
    jdbc.update(
        """
        insert into jobs (id, user_id, job_type, payload, status, run_after)
        values (?, ?, ?, ?::jsonb, 'pending', now())
        """,
        id,
        userId,
        PROCESS_TRANSACTION,
        payload.toString());
    return id;
  }

  @Transactional
  public Optional<JobRecord> claimNext() {
    List<UUID> ids = jdbc.query(
        """
        select id from jobs
        where status = 'pending' and run_after <= now()
        order by run_after, created_at
        limit 1
        for update skip locked
        """,
        (rs, n) -> UUID.fromString(rs.getString("id")));
    if (ids.isEmpty()) {
      return Optional.empty();
    }
    UUID id = ids.getFirst();
    jdbc.update(
        """
        update jobs
        set status = 'running', attempts = attempts + 1, updated_at = now()
        where id = ?
        """,
        id);
    return Optional.ofNullable(jdbc.query(
            """
            select id, user_id, job_type, payload::text, status, attempts
            from jobs where id = ?
            """,
            (rs, n) -> new JobRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("user_id")),
                rs.getString("job_type"),
                rs.getString("payload"),
                rs.getString("status"),
                rs.getInt("attempts")),
            id)
        .stream().findFirst().orElse(null));
  }

  @Transactional
  public void markDone(UUID jobId) {
    jdbc.update(
        "update jobs set status = 'done', updated_at = now(), last_error = null where id = ?",
        jobId);
  }

  @Transactional
  public void markFailed(UUID jobId, String error) {
    jdbc.update(
        """
        update jobs
        set status = 'failed', last_error = ?, updated_at = now()
        where id = ?
        """,
        error == null ? null : error.substring(0, Math.min(error.length(), 2000)),
        jobId);
  }

  public record JobRecord(
      UUID id, UUID userId, String jobType, String payloadJson, String status, int attempts) {}
}
