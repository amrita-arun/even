package dev.even.people;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PeopleService {

  private final JdbcTemplate jdbc;

  public PeopleService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<PersonRecord> list(UUID userId) {
    return jdbc.query(
        """
        select id, user_id, display_name, first_name, last_name, venmo_handle, zelle_handle, kind
        from people
        where user_id = ?
        order by display_name
        """,
        this::mapPerson,
        userId);
  }

  @Transactional
  public PersonRecord create(
      UUID userId,
      String firstName,
      String lastName,
      String displayName,
      String venmoHandle,
      String zelleHandle,
      String kind) {
    String role = normalizeKind(kind);
    String first = blankToNull(firstName);
    String last = blankToNull(lastName);
    if ((first == null) != (last == null)) {
      throw new IllegalArgumentException("first and last name are both required");
    }
    String name = first != null
        ? first + " " + last
        : blankToNull(displayName);
    if (name == null) {
      throw new IllegalArgumentException(
          "reimbursement".equals(role) ? "name is required" : "first and last name are required");
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into people
          (id, user_id, display_name, first_name, last_name, venmo_handle, zelle_handle, kind)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        userId,
        name,
        first,
        last,
        blankToNull(venmoHandle),
        blankToNull(zelleHandle),
        role);
    return requireOwned(userId, id);
  }

  @Transactional
  public PersonRecord create(
      UUID userId, String displayName, String venmoHandle, String zelleHandle) {
    return create(userId, null, null, displayName, venmoHandle, zelleHandle, "friend");
  }

  /** Back-compat for call sites that only pass a display name. */
  @Transactional
  public PersonRecord create(UUID userId, String displayName) {
    return create(userId, displayName, null, null);
  }

  @Transactional
  public PersonRecord update(
      UUID userId,
      UUID personId,
      String firstName,
      String lastName,
      String venmoHandle,
      String zelleHandle) {
    requireOwned(userId, personId);
    String first = blankToNull(firstName);
    String last = blankToNull(lastName);
    if ((first == null) != (last == null)) {
      throw new IllegalArgumentException("first and last name are both required");
    }
    String name = first == null ? null : first + " " + last;
    jdbc.update(
        """
        update people
        set first_name = coalesce(?, first_name),
            last_name = coalesce(?, last_name),
            display_name = coalesce(?, display_name),
            venmo_handle = coalesce(?, venmo_handle),
            zelle_handle = coalesce(?, zelle_handle)
        where id = ? and user_id = ?
        """,
        first,
        last,
        name,
        blankToNull(venmoHandle),
        blankToNull(zelleHandle),
        personId,
        userId);
    return requireOwned(userId, personId);
  }

  public PersonRecord requireOwned(UUID userId, UUID personId) {
    List<PersonRecord> rows = jdbc.query(
        """
        select id, user_id, display_name, first_name, last_name, venmo_handle, zelle_handle, kind
        from people where id = ?
        """,
        this::mapPerson,
        personId);
    if (rows.isEmpty() || !rows.getFirst().userId().equals(userId)) {
      throw new IllegalArgumentException("person not found: " + personId);
    }
    return rows.getFirst();
  }

  private PersonRecord mapPerson(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new PersonRecord(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("user_id")),
        rs.getString("display_name"),
        rs.getString("first_name"),
        rs.getString("last_name"),
        rs.getString("venmo_handle"),
        rs.getString("zelle_handle"),
        rs.getString("kind"));
  }

  private static String normalizeKind(String kind) {
    if (kind == null || kind.isBlank() || "friend".equalsIgnoreCase(kind.trim())) {
      return "friend";
    }
    if ("reimbursement".equalsIgnoreCase(kind.trim()) || "club".equalsIgnoreCase(kind.trim())) {
      return "reimbursement";
    }
    throw new IllegalArgumentException("kind must be friend or reimbursement");
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  public record PersonRecord(
      UUID id,
      UUID userId,
      String displayName,
      String firstName,
      String lastName,
      String venmoHandle,
      String zelleHandle,
      String kind) {}
}
