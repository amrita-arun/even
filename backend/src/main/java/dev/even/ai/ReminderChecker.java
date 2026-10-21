package dev.even.ai;

import dev.even.ingestion.MoneyParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every dollar amount in a reminder draft must equal the ledger balance.
 * Anything else is replaced with the template.
 */
public final class ReminderChecker {

  private static final Pattern MONEY = Pattern.compile(
      "\\$\\s*(\\d{1,7}(?:\\.\\d{1,2})?)|(?<![\\d.])(\\d{1,7}\\.\\d{2})(?![\\d])");

  private ReminderChecker() {}

  public static Result check(long ledgerCents, String draft, String template) {
    List<Long> amounts = amounts(draft);
    boolean matches = !amounts.isEmpty() && amounts.stream().allMatch(cents -> cents == ledgerCents);
    if (matches) {
      return new Result(draft.trim(), false);
    }
    return new Result(template, true);
  }

  static List<Long> amounts(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<Long> found = new ArrayList<>();
    Matcher matcher = MONEY.matcher(text);
    while (matcher.find()) {
      String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
      found.add(MoneyParser.dollarsToCents(raw));
    }
    return found;
  }

  public record Result(String text, boolean usedTemplate) {}
}
