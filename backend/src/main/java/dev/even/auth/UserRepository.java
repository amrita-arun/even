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
        "select id, email, imessage_handle from users where id = ?",
        (rs, rowNum) -> mapUser(rs),
        id);
    return rows.stream().findFirst();
  }

  public Optional<UserRecord> onlyUser() {
    var rows = jdbc.query(
        "select id, email, imessage_handle from users",
        (rs, rowNum) -> mapUser(rs));
    if (rows.size() != 1) {
      return Optional.empty();
    }
    return Optional.of(rows.getFirst());
  }

  public Optional<UserRecord> findByHandle(String handle) {
    var rows = jdbc.query(
        "select id, email, imessage_handle from users where imessage_handle = ?",
        (rs, rowNum) -> mapUser(rs),
        handle);
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
    return findById(id).orElseThrow();
  }

  public void setHandle(UUID id, String handle) {
    try {
      jdbc.update("update users set imessage_handle = ? where id = ?", handle, id);
    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
      throw new IllegalArgumentException("that number is already registered");
    }
  }

  private static UserRecord mapUser(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new UserRecord(
        UUID.fromString(rs.getString("id")),
        rs.getString("email"),
        rs.getString("imessage_handle"));
  }

  public record UserRecord(UUID id, String email, String imessageHandle) {}
}
