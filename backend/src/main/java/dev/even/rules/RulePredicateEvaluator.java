package dev.even.rules;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Map;

/**
 * Closed operator set: {@code all}, {@code any}, {@code eq}, {@code gt}, {@code lt},
 * {@code gt_trailing_avg_multiple}, {@code local_time_between}.
 */
@Component
public class RulePredicateEvaluator {

  public void validate(JsonNode predicate) {
    evaluate(predicate, Map.of(
        "amount_cents", 0L,
        "category", "",
        "kind", "purchase",
        "status", "posted",
        "description", "",
        "trailing_avg_amount_cents", 0L), true);
  }

  public boolean matches(JsonNode predicate, Map<String, Object> fields) {
    return evaluate(predicate, fields, false);
  }

  private boolean evaluate(JsonNode node, Map<String, Object> fields, boolean validateOnly) {
    if (node == null || node.isNull() || !node.isObject() || node.isEmpty()) {
      throw new IllegalArgumentException("predicate must be a non-empty object");
    }
    if (node.size() != 1) {
      throw new IllegalArgumentException("predicate object must have exactly one operator key");
    }
    Map.Entry<String, JsonNode> entry = node.fields().next();
    String op = entry.getKey();
    JsonNode body = entry.getValue();
    return switch (op) {
      case "all" -> evalAll(body, fields, validateOnly);
      case "any" -> evalAny(body, fields, validateOnly);
      case "eq" -> evalEq(body, fields, validateOnly);
      case "gt" -> evalCompare(body, fields, validateOnly, true);
      case "lt" -> evalCompare(body, fields, validateOnly, false);
      case "gt_trailing_avg_multiple" -> evalTrailingAvg(body, fields, validateOnly);
      case "local_time_between" -> evalLocalTime(body, fields, validateOnly);
      default -> throw new IllegalArgumentException("unknown predicate operator: " + op);
    };
  }

  private boolean evalAll(JsonNode body, Map<String, Object> fields, boolean validateOnly) {
    if (!body.isArray() || body.isEmpty()) {
      throw new IllegalArgumentException("all requires a non-empty array");
    }
    boolean ok = true;
    for (JsonNode child : body) {
      ok = evaluate(child, fields, validateOnly) && ok;
    }
    return ok;
  }

  private boolean evalAny(JsonNode body, Map<String, Object> fields, boolean validateOnly) {
    if (!body.isArray() || body.isEmpty()) {
      throw new IllegalArgumentException("any requires a non-empty array");
    }
    boolean ok = false;
    for (JsonNode child : body) {
      ok = evaluate(child, fields, validateOnly) || ok;
    }
    return ok;
  }

  private boolean evalEq(JsonNode body, Map<String, Object> fields, boolean validateOnly) {
    String field = requiredText(body, "field");
    if (!body.has("value")) {
      throw new IllegalArgumentException("eq requires value");
    }
    if (validateOnly) {
      return true;
    }
    Object actual = fields.get(field);
    JsonNode expected = body.get("value");
    if (actual == null) {
      return expected.isNull();
    }
    if (expected.isNumber() && actual instanceof Number n) {
      return n.doubleValue() == expected.asDouble();
    }
    return String.valueOf(actual).equalsIgnoreCase(expected.asText());
  }

  private boolean evalCompare(
      JsonNode body, Map<String, Object> fields, boolean validateOnly, boolean greater) {
    String field = requiredText(body, "field");
    if (!body.has("value") || !body.get("value").isNumber()) {
      throw new IllegalArgumentException((greater ? "gt" : "lt") + " requires numeric value");
    }
    if (validateOnly) {
      return true;
    }
    Object actual = fields.get(field);
    if (!(actual instanceof Number n)) {
      return false;
    }
    double left = n.doubleValue();
    double right = body.get("value").asDouble();
    return greater ? left > right : left < right;
  }

  private boolean evalTrailingAvg(
      JsonNode body, Map<String, Object> fields, boolean validateOnly) {
    requiredText(body, "field");
    if (!body.has("multiple") || !body.get("multiple").isNumber()) {
      throw new IllegalArgumentException("gt_trailing_avg_multiple requires numeric multiple");
    }
    if (validateOnly) {
      return true;
    }
    Object amount = fields.get("amount_cents");
    Object avg = fields.get("trailing_avg_amount_cents");
    if (!(amount instanceof Number a) || !(avg instanceof Number b) || b.doubleValue() <= 0) {
      return false;
    }
    return a.doubleValue() > body.get("multiple").asDouble() * b.doubleValue();
  }

  private boolean evalLocalTime(
      JsonNode body, Map<String, Object> fields, boolean validateOnly) {
    LocalTime start = LocalTime.parse(requiredText(body, "start"));
    LocalTime end = LocalTime.parse(requiredText(body, "end"));
    if (validateOnly) {
      return true;
    }
    Object raw = fields.get("local_time");
    if (!(raw instanceof String s) || s.isBlank()) {
      return false;
    }
    LocalTime time = LocalTime.parse(s);
    if (!start.isAfter(end)) {
      return !time.isBefore(start) && !time.isAfter(end);
    }
    return !time.isBefore(start) || !time.isAfter(end);
  }

  private static String requiredText(JsonNode body, String field) {
    if (body == null || !body.hasNonNull(field)) {
      throw new IllegalArgumentException("missing " + field);
    }
    return body.get(field).asText();
  }
}
