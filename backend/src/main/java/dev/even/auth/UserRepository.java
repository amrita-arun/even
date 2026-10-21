package dev.even.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

  private final JdbcTemplate jdbc;

  public UserRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<UserRecord> findById(UUID id) {
    var rows = jdbc.query(
        "select id, email from users where id = ?",
        (rs, rowNum) -> new UserRecord(
            UUID.fromString(rs.getString("id")),
            rs.getString("email")),
        id);
    return rows.stream().findFirst();
  }

  public UserRecord upsert(UUID id, String email) {
    jdbc.update(
        """
        insert into users (id, email)
        values (?, ?)
        on conflict (id) do update set email = excluded.email
        """,
        id,
        email);
    return new UserRecord(id, email);
  }

  public record UserRecord(UUID id, String email) {}
}
