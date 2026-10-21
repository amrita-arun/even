package dev.even.ai;

import dev.even.ingestion.MoneyParser;
import dev.even.splits.SplitMethod;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for a schema-constrained model. Text is data: instruction-shaped
 * lines are dropped and never followed. Merchant memos are not read here.
 */
@Component
public class HeuristicSplitModel {

  public static final String MODEL = "language-mock";

  private static final Pattern INSTRUCTION = Pattern.compile(
      "(?i)(ignore\\s+(all\\s+|any\\s+|previous\\s+|prior\\s+)?instructions"
          + "|system\\s+prompt|you\\s+are\\s+now|do\\s+not\\s+follow"
          + "|post\\s+(this\\s+|the\\s+)?(journal|split)|drop\\s+table|<\\s*/?\\s*[a-z])");

  private static final Pattern TAX_LINE = Pattern.compile(
      "(?i)^tax\\s+\\$?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*$");
  private static final Pattern TIP_LINE = Pattern.compile(
      "(?i)^tip\\s+\\$?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*$");
  private static final Pattern TAX = Pattern.compile(
      "(?i)\\btax\\s+\\$?\\s*(\\d+(?:\\.\\d{1,2})?)");
  private static final Pattern TIP = Pattern.compile(
      "(?i)\\btip\\s+\\$?\\s*(\\d+(?:\\.\\d{1,2})?)");
  private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z'.-]*");

  private static final Set<String> STOPWORDS = Set.of(
      "split", "equally", "equal", "with", "the", "bill", "and", "for", "dinner", "lunch",
      "breakfast", "please", "had", "has", "have", "me", "i", "tax", "tip", "owed", "owe",
      "owes", "paid", "got", "to", "a", "an", "it", "my", "our", "on", "of", "charge",
      "total", "between", "us", "we", "both", "plus", "including", "include", "this",
      "that", "from", "receipt");

  public ModelSplit parseUtterance(String text, List<KnownPerson> people, long totalCents) {
    Prepared prepared = prepare(text);
    long tax = firstMoney(TAX, prepared.cleaned());
    long tip = firstMoney(TIP, prepared.cleaned());
    String body = TIP.matcher(TAX.matcher(prepared.cleaned()).replaceAll(" ")).replaceAll(" ");
    return fromAssignments(body, people, totalCents, tax, tip, prepared.ignoredLines(), false);
  }

  public ModelSplit parseReceipt(String text, List<KnownPerson> people, long totalCents) {
    Prepared prepared = prepare(text);
    Map<UUID, Long> items = new LinkedHashMap<>();
    Long ownerCents = null;
    long tax = 0L;
    long tip = 0L;
    List<Alias> aliases = aliases(people);
    for (String rawLine : prepared.cleaned().split("\\R")) {
      String line = rawLine.trim();
      if (line.isEmpty()) {
        continue;
      }
      Matcher taxLine = TAX_LINE.matcher(line);
      if (taxLine.matches()) {
        tax += cents(taxLine.group(1));
        continue;
      }
      Matcher tipLine = TIP_LINE.matcher(line);
      if (tipLine.matches()) {
        tip += cents(tipLine.group(1));
        continue;
      }
      if (!line.matches(".*\\d.*")) {
        continue;
      }
      Assignment assignment = assignmentAtStart(line, aliases);
      if (assignment == null) {
        return ModelSplit.rejected(
            "could not match a friend on receipt line: " + line, prepared.ignoredLines());
      }
      if (assignment.reimbursement()) {
        return ModelSplit.rejected(
            assignment.name() + " is a reimbursement, not a friend", prepared.ignoredLines());
      }
      if (assignment.owner()) {
        ownerCents = (ownerCents == null ? 0L : ownerCents) + assignment.cents();
      } else {
        items.merge(assignment.personId(), assignment.cents(), Long::sum);
      }
    }
    if (items.isEmpty() && ownerCents == null) {
      return ModelSplit.rejected(
          "receipt needs a line per person with an amount", prepared.ignoredLines());
    }
    return sharesFrom(items, ownerCents, totalCents, tax, tip, prepared.ignoredLines(), true);
  }

  private ModelSplit fromAssignments(
      String body,
      List<KnownPerson> people,
      long totalCents,
      long tax,
      long tip,
      int ignoredLines,
      boolean itemized) {
    List<Alias> aliases = aliases(people);
    List<Hit> hits = amountHits(body, aliases);
    String remaining = blank(body, hits);
    String unknown = unknownToken(remaining, aliases);
    if (unknown != null) {
      return ModelSplit.rejected("unknown person: " + unknown, ignoredLines);
    }
    for (Hit hit : hits) {
      if (hit.reimbursement()) {
        return ModelSplit.rejected(
            hit.name() + " is a reimbursement, not a friend", ignoredLines);
      }
    }
    if (hits.isEmpty()) {
      List<Hit> mentions = mentionHits(body, aliases);
      for (Hit hit : mentions) {
        if (hit.reimbursement()) {
          return ModelSplit.rejected(
              hit.name() + " is a reimbursement, not a friend", ignoredLines);
        }
      }
      String mentionUnknown = unknownToken(blank(body, mentions), aliases);
      if (mentionUnknown != null) {
        return ModelSplit.rejected("unknown person: " + mentionUnknown, ignoredLines);
      }
      List<UUID> friends = mentions.stream()
          .filter(hit -> !hit.owner())
          .map(Hit::personId)
          .distinct()
          .toList();
      if (friends.isEmpty()) {
        return ModelSplit.rejected(
            "name a friend to split with, or give each amount", ignoredLines);
      }
      List<ModelShare> shares = new ArrayList<>();
      shares.add(new ModelShare(null, null));
      friends.forEach(id -> shares.add(new ModelShare(id, null)));
      return new ModelSplit(true, null, SplitMethod.equal, tax, tip, shares, ignoredLines);
    }

    Map<UUID, Long> items = new LinkedHashMap<>();
    Long ownerCents = null;
    for (Hit hit : hits) {
      if (hit.owner()) {
        ownerCents = (ownerCents == null ? 0L : ownerCents) + hit.cents();
      } else if (hit.personId() != null) {
        items.merge(hit.personId(), hit.cents(), Long::sum);
      }
    }
    return sharesFrom(items, ownerCents, totalCents, tax, tip, ignoredLines, itemized);
  }

  private ModelSplit sharesFrom(
      Map<UUID, Long> items,
      Long ownerCents,
      long totalCents,
      long tax,
      long tip,
      int ignoredLines,
      boolean itemized) {
    long pretax = totalCents - tax - tip;
    if (pretax < 0) {
      return ModelSplit.rejected("tax and tip are more than the charge", ignoredLines);
    }
    long others = items.values().stream().mapToLong(Long::longValue).sum();
    if (ownerCents == null) {
      long remainder = pretax - others;
      if (remainder < 0) {
        return ModelSplit.rejected("those amounts are more than the charge", ignoredLines);
      }
      ownerCents = remainder;
    }
    List<ModelShare> shares = new ArrayList<>();
    shares.add(new ModelShare(null, ownerCents));
    items.forEach((id, cents) -> shares.add(new ModelShare(id, cents)));
    if (items.isEmpty()) {
      return ModelSplit.rejected(
          "name a friend to split with, or give each amount", ignoredLines);
    }
    SplitMethod method = itemized ? SplitMethod.itemized : SplitMethod.fixed;
    return new ModelSplit(true, null, method, tax, tip, shares, ignoredLines);
  }

  private static Assignment assignmentAtStart(String line, List<Alias> aliases) {
    if (startsWithAlias(line, "me") || startsWithAlias(line, "i")) {
      String alias = startsWithAlias(line, "me") ? "me" : "i";
      Long amount = leadingAmount(line.substring(alias.length()).trim());
      if (amount == null) {
        return null;
      }
      return new Assignment(null, null, amount, true, false);
    }
    for (Alias alias : aliases) {
      if (alias.owner()) {
        continue;
      }
      if (!startsWithAlias(line, alias.text())) {
        continue;
      }
      String rest = line.substring(alias.text().length()).trim();
      Long amount = leadingAmount(rest);
      if (amount == null) {
        continue;
      }
      return new Assignment(alias.personId(), alias.text(), amount, false, alias.reimbursement());
    }
    return null;
  }

  private static boolean startsWithAlias(String line, String alias) {
    if (line.length() < alias.length()) {
      return false;
    }
    if (!line.regionMatches(true, 0, alias, 0, alias.length())) {
      return false;
    }
    if (line.length() == alias.length()) {
      return true;
    }
    char next = line.charAt(alias.length());
    return !Character.isLetterOrDigit(next);
  }

  private static Long leadingAmount(String rest) {
    Matcher matcher = Pattern.compile("^\\$?\\s*(\\d+(?:\\.\\d{1,2})?)\\b").matcher(rest);
    if (!matcher.find()) {
      return null;
    }
    return cents(matcher.group(1));
  }

  private static List<Hit> amountHits(String body, List<Alias> aliases) {
    List<Hit> hits = new ArrayList<>();
    for (Alias alias : aliases) {
      Matcher after = Pattern.compile(
              "(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(alias.text()) + "(?![\\p{L}\\p{N}])"
                  + "(?:\\s+(?:had|has|have|owes|owe|owed|paid|got))?"
                  + "\\s*\\$?\\s*(\\d+(?:\\.\\d{1,2})?)")
          .matcher(body);
      while (after.find()) {
        if (overlaps(hits, after.start(), after.end())) {
          continue;
        }
        hits.add(new Hit(
            after.start(),
            after.end(),
            alias.personId(),
            alias.text(),
            cents(after.group(1)),
            alias.owner(),
            alias.reimbursement()));
      }
    }
    return hits;
  }

  private static List<Hit> mentionHits(String body, List<Alias> aliases) {
    List<Hit> hits = new ArrayList<>();
    for (Alias alias : aliases) {
      if (alias.owner()) {
        continue;
      }
      Matcher matcher = Pattern.compile(
              "(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(alias.text()) + "(?![\\p{L}\\p{N}])")
          .matcher(body);
      while (matcher.find()) {
        if (overlaps(hits, matcher.start(), matcher.end())) {
          continue;
        }
        hits.add(new Hit(
            matcher.start(),
            matcher.end(),
            alias.personId(),
            alias.text(),
            0L,
            false,
            alias.reimbursement()));
      }
    }
    return hits;
  }

  private static boolean overlaps(List<Hit> hits, int start, int end) {
    for (Hit hit : hits) {
      if (start < hit.end() && end > hit.start()) {
        return true;
      }
    }
    return false;
  }

  private static String blank(String body, List<Hit> hits) {
    StringBuilder out = new StringBuilder(body);
    hits.stream()
        .sorted(Comparator.comparingInt(Hit::start).reversed())
        .forEach(hit -> {
          for (int i = hit.start(); i < hit.end() && i < out.length(); i++) {
            out.setCharAt(i, ' ');
          }
        });
    return out.toString();
  }

  private static String unknownToken(String text, List<Alias> aliases) {
    Matcher matcher = WORD.matcher(text);
    while (matcher.find()) {
      String word = matcher.group();
      if (STOPWORDS.contains(word.toLowerCase(Locale.ROOT))) {
        continue;
      }
      if (!Character.isUpperCase(word.charAt(0))) {
        continue;
      }
      boolean known = aliases.stream().anyMatch(alias ->
          alias.text().equalsIgnoreCase(word)
              || alias.text().toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT)));
      if (!known) {
        return word;
      }
    }
    return null;
  }

  private static List<Alias> aliases(List<KnownPerson> people) {
    List<Alias> aliases = new ArrayList<>();
    aliases.add(new Alias(null, "me", true, false));
    aliases.add(new Alias(null, "i", true, false));
    Map<String, Integer> firstNameCounts = new LinkedHashMap<>();
    for (KnownPerson person : people) {
      if (person.firstName() != null && !person.firstName().isBlank()) {
        String key = person.firstName().toLowerCase(Locale.ROOT);
        firstNameCounts.merge(key, 1, Integer::sum);
      }
    }
    for (KnownPerson person : people) {
      boolean reimbursement = !"friend".equals(person.kind());
      if (person.displayName() != null && !person.displayName().isBlank()) {
        aliases.add(new Alias(person.id(), person.displayName().trim(), false, reimbursement));
      }
      if (person.firstName() != null && !person.firstName().isBlank()) {
        String first = person.firstName().trim();
        boolean unique = firstNameCounts.getOrDefault(first.toLowerCase(Locale.ROOT), 0) == 1;
        boolean sameAsDisplay = person.displayName() != null
            && person.displayName().trim().equalsIgnoreCase(first);
        if (unique && !sameAsDisplay) {
          aliases.add(new Alias(person.id(), first, false, reimbursement));
        }
      }
    }
    aliases.sort(Comparator.comparingInt((Alias alias) -> alias.text().length()).reversed());
    return aliases;
  }

  private static long firstMoney(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    if (!matcher.find()) {
      return 0L;
    }
    return cents(matcher.group(1));
  }

  private static long cents(String amount) {
    try {
      return MoneyParser.dollarsToCents(amount);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(e.getMessage());
    }
  }

  private static Prepared prepare(String text) {
    if (text == null || text.isBlank()) {
      return new Prepared("", 0);
    }
    List<String> kept = new ArrayList<>();
    int ignored = 0;
    for (String line : text.split("\\R")) {
      if (INSTRUCTION.matcher(line).find()) {
        ignored++;
        continue;
      }
      kept.add(line);
    }
    return new Prepared(String.join("\n", kept), ignored);
  }

  public record KnownPerson(
      UUID id, String displayName, String firstName, String lastName, String kind) {}

  public record ModelShare(UUID personId, Long cents) {}

  public record ModelSplit(
      boolean parsed,
      String rejection,
      SplitMethod method,
      long taxCents,
      long tipCents,
      List<ModelShare> shares,
      int ignoredInstructionLines) {

    static ModelSplit rejected(String reason, int ignoredLines) {
      return new ModelSplit(false, reason, null, 0L, 0L, List.of(), ignoredLines);
    }
  }

  private record Prepared(String cleaned, int ignoredLines) {}

  private record Alias(UUID personId, String text, boolean owner, boolean reimbursement) {}

  private record Hit(
      int start,
      int end,
      UUID personId,
      String name,
      long cents,
      boolean owner,
      boolean reimbursement) {}

  private record Assignment(
      UUID personId, String name, long cents, boolean owner, boolean reimbursement) {}
}
