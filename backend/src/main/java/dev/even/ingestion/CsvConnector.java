package dev.even.ingestion;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads a statement CSV into a {@link SyncPage}. Columns: posted_on, description, amount;
 * optional currency, external_id, status, kind, pending_external_id.
 */
@Component
public class CsvConnector {

  public SyncPage fetch(UUID accountId, InputStream csv) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
      String headerLine = reader.readLine();
      if (headerLine == null) {
        throw new CsvRowException(1, "CSV is empty");
      }
      Map<String, Integer> headers = indexHeaders(headerLine);
      require(headers, "posted_on");
      require(headers, "description");
      require(headers, "amount");

      List<ProviderTransaction> added = new ArrayList<>();
      String line;
      int lineNumber = 1;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.isBlank()) {
          continue;
        }
        added.add(parseRow(accountId, headers, splitCsv(line), lineNumber));
      }
      return SyncPage.ofAdded(added);
    } catch (IOException e) {
      throw new IllegalArgumentException("failed to read CSV", e);
    }
  }

  private ProviderTransaction parseRow(
      UUID accountId, Map<String, Integer> headers, List<String> cols, int lineNumber) {
    try {
      LocalDate postedOn = LocalDate.parse(cell(cols, headers, "posted_on").trim());
      String description = cell(cols, headers, "description").trim();
      long amountCents = MoneyParser.dollarsToCents(cell(cols, headers, "amount"));
      String currency = optional(cols, headers, "currency").orElse("USD").trim().toUpperCase(Locale.ROOT);
      if (!"USD".equals(currency)) {
        throw new IllegalArgumentException("only USD is supported, got " + currency);
      }
      String status = optional(cols, headers, "status").orElse("posted").trim().toLowerCase(Locale.ROOT);
      if (!status.equals("pending") && !status.equals("posted")) {
        throw new IllegalArgumentException("status must be pending or posted");
      }
      String kind = optional(cols, headers, "kind").orElse("purchase").trim().toLowerCase(Locale.ROOT);
      if (!List.of("purchase", "payment", "refund").contains(kind)) {
        throw new IllegalArgumentException("kind must be purchase, payment, or refund");
      }
      Optional<String> pendingExternalId = optional(cols, headers, "pending_external_id")
          .map(String::trim)
          .filter(s -> !s.isEmpty());

      String externalId = optional(cols, headers, "external_id")
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .orElseGet(() -> contentHash(accountId, postedOn, amountCents, description));

      String rawJson = """
          {"posted_on":"%s","description":%s,"amount_cents":%d,"status":"%s","kind":"%s"}
          """.formatted(
              postedOn,
              jsonString(description),
              amountCents,
              status,
              kind)
          .strip();

      return new ProviderTransaction(
          externalId,
          pendingExternalId,
          amountCents,
          currency,
          postedOn,
          status,
          description,
          kind,
          rawJson);
    } catch (RuntimeException e) {
      throw new CsvRowException(lineNumber, e.getMessage());
    }
  }

  static String contentHash(UUID accountId, LocalDate postedOn, long amountCents, String description) {
    String normalized = description.trim().toLowerCase(Locale.ROOT);
    String material = accountId + "|" + postedOn + "|" + amountCents + "|" + normalized;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, Integer> indexHeaders(String headerLine) {
    List<String> cols = splitCsv(headerLine);
    Map<String, Integer> index = new LinkedHashMap<>();
    for (int i = 0; i < cols.size(); i++) {
      index.put(cols.get(i).trim().toLowerCase(Locale.ROOT), i);
    }
    return index;
  }

  private static void require(Map<String, Integer> headers, String name) {
    if (!headers.containsKey(name)) {
      throw new CsvRowException(1, "missing column " + name);
    }
  }

  private static String cell(List<String> cols, Map<String, Integer> headers, String name) {
    Integer idx = headers.get(name);
    if (idx == null || idx >= cols.size()) {
      throw new IllegalArgumentException("missing column " + name);
    }
    return cols.get(idx);
  }

  private static Optional<String> optional(
      List<String> cols, Map<String, Integer> headers, String name) {
    Integer idx = headers.get(name);
    if (idx == null || idx >= cols.size()) {
      return Optional.empty();
    }
    String value = cols.get(idx);
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  private static String jsonString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /** Minimal CSV splitter that respects double-quoted fields. */
  static List<String> splitCsv(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            cur.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          cur.append(c);
        }
      } else if (c == '"') {
        inQuotes = true;
      } else if (c == ',') {
        out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    out.add(cur.toString());
    return out;
  }
}
