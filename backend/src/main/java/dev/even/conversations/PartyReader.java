package dev.even.conversations;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a text into people, names, and Venmo usernames. It does not decide
 * whether a split is posted.
 */
final class PartyReader {

  private static final Pattern INSTRUCTION = Pattern.compile(
      "(?i)(ignore\\s+(all\\s+|any\\s+|previous\\s+|prior\\s+)?instructions"
          + "|system\\s+prompt|you\\s+are\\s+now|do\\s+not\\s+follow"
          + "|post\\s+(this\\s+|the\\s+)?(journal|split)|drop\\s+table|<\\s*/?\\s*[a-z])");

  private static final Pattern VENMO = Pattern.compile(
      "(?i)\\bvenmo(?:\\s+is)?\\s+@?([A-Za-z0-9_-]{2,30})\\b");

  private static final Pattern AT = Pattern.compile("@([A-Za-z0-9_-]{2,30})\\b");

  private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z'-]*");

  private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_-]{2,30}");

  private static final Set<String> STOP = Set.of(
      "a", "an", "the", "and", "with", "plus", "me", "i", "im", "myself", "we", "us", "our",
      "my", "was", "were", "had", "has", "have", "got", "on", "for", "of", "to", "too", "also",
      "just", "there", "drinks", "drink", "coffee", "beer", "beers", "wine", "dessert",
      "appetizer", "appetizers", "tip", "tax", "food", "meal", "meals", "stuff", "things",
      "portion", "tab", "check", "bill", "ride", "rides", "dinner", "lunch", "breakfast",
      "uber", "restaurant", "split", "equally", "equal", "please", "who", "her", "his",
      "their", "she", "he", "they", "it", "that", "this", "from", "username", "user",
      "hi", "hey", "hello", "yes", "yeah", "yep", "no", "nope", "nah");

  private PartyReader() {}

  static List<Mention> read(String text) {
    if (text == null || text.isBlank() || INSTRUCTION.matcher(text).find()) {
      return List.of();
    }
    List<Mention> mentions = new ArrayList<>();
    for (String clause : text.split("(?i)\\s*(,|;|&|\\band\\b)\\s*")) {
      String venmo = firstVenmo(clause);
      String body = VENMO.matcher(clause).replaceAll(" ");
      body = AT.matcher(body).replaceAll(" ");
      List<String> names = names(body);
      String handle = names.isEmpty() ? loneHandle(body) : null;
      if (venmo == null) {
        venmo = handle;
      }
      if (names.isEmpty() && venmo == null) {
        continue;
      }
      String first = names.isEmpty() ? null : title(names.get(0));
      String last = names.size() < 2 ? null : titleJoin(names.subList(1, names.size()));
      mentions.add(new Mention(first, last, venmo));
    }
    return mentions.stream().filter(mention -> mention.first() != null || mention.venmo() != null).toList();
  }

  /**
   * Fills whatever is still missing on {@code current} from a follow-up text.
   * A later name becomes the last name. A handle, or a leftover word once the
   * last name is filled, becomes the Venmo username.
   */
  static Mention fill(Mention current, String text) {
    String first = current.first();
    String last = current.last();
    String venmo = current.venmo();
    String explicit = firstVenmo(text);
    if (explicit != null && venmo == null) {
      venmo = explicit;
    }
    String body = text == null ? "" : VENMO.matcher(text).replaceAll(" ");
    body = AT.matcher(body).replaceAll(" ");
    List<String> names = new ArrayList<>();
    for (String clause : body.split("(?i)\\s*(,|;|&|\\band\\b)\\s*")) {
      String handle = loneHandle(clause);
      if (handle != null && names(clause).isEmpty()) {
        if (venmo == null) {
          venmo = handle;
        }
        continue;
      }
      for (String name : names(clause)) {
        names.add(name);
      }
    }
    List<String> leftover = new ArrayList<>();
    for (String name : names) {
      String titled = title(name);
      if (first == null) {
        first = titled;
      } else if (first.equalsIgnoreCase(titled)) {
        continue;
      } else if (last == null) {
        last = titled;
      } else {
        leftover.add(name);
      }
    }
    if (venmo == null && !leftover.isEmpty() && isHandle(leftover.get(0))) {
      venmo = leftover.get(0);
    }
    if (venmo == null && last == null && names.size() == 1 && current.first() != null && isHandle(names.get(0))
        && current.last() != null) {
      venmo = names.get(0);
    }
    return new Mention(first, last, venmo, current.personId());
  }

  static String encode(List<Mention> mentions) {
    if (mentions == null || mentions.isEmpty()) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    for (Mention mention : mentions) {
      if (!out.isEmpty()) {
        out.append('\n');
      }
      out.append(mention.first() == null ? "" : mention.first())
          .append('\t')
          .append(mention.last() == null ? "" : mention.last())
          .append('\t')
          .append(mention.venmo() == null ? "" : mention.venmo())
          .append('\t')
          .append(mention.personId() == null ? "" : mention.personId());
    }
    return out.toString();
  }

  static List<Mention> decode(String raw) {
    if (raw == null || !raw.contains("\t")) {
      return List.of();
    }
    List<Mention> mentions = new ArrayList<>();
    for (String line : raw.split("\n")) {
      String[] parts = line.split("\t", -1);
      if (parts.length < 3) {
        continue;
      }
      UUID personId = parts.length > 3 && !parts[3].isBlank() ? UUID.fromString(parts[3]) : null;
      mentions.add(new Mention(blank(parts[0]), blank(parts[1]), blank(parts[2]), personId));
    }
    return mentions;
  }

  private static String firstVenmo(String text) {
    if (text == null) {
      return null;
    }
    Matcher matcher = VENMO.matcher(text);
    if (matcher.find() && isHandle(matcher.group(1))) {
      return matcher.group(1);
    }
    Matcher at = AT.matcher(text);
    if (at.find() && isHandle(at.group(1))) {
      return at.group(1);
    }
    return null;
  }

  private static List<String> names(String clause) {
    List<String> names = new ArrayList<>();
    Matcher matcher = WORD.matcher(clause == null ? "" : clause);
    while (matcher.find()) {
      String word = matcher.group();
      if (word.length() < 2 || STOP.contains(word.toLowerCase(Locale.US))) {
        continue;
      }
      names.add(word);
    }
    return names;
  }

  private static String loneHandle(String clause) {
    if (clause == null) {
      return null;
    }
    String trimmed = clause.trim().replaceAll("[.!?]+$", "");
    if (!HANDLE.matcher(trimmed).matches() || !isHandle(trimmed)) {
      return null;
    }
    if (trimmed.chars().allMatch(Character::isLetter)) {
      return null;
    }
    return trimmed;
  }

  private static boolean isHandle(String value) {
    if (value == null || !HANDLE.matcher(value).matches()) {
      return false;
    }
    String norm = value.toLowerCase(Locale.US);
    return !STOP.contains(norm) && !norm.equals("y") && !norm.equals("n");
  }

  private static String titleJoin(List<String> words) {
    StringBuilder out = new StringBuilder();
    for (String word : words) {
      if (!out.isEmpty()) {
        out.append(' ');
      }
      out.append(title(word));
    }
    return out.toString();
  }

  private static String title(String word) {
    if (word == null || word.isBlank()) {
      return word;
    }
    return word.substring(0, 1).toUpperCase(Locale.US) + word.substring(1).toLowerCase(Locale.US);
  }

  private static String blank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  record Mention(String first, String last, String venmo, UUID personId) {

    Mention(String first, String last, String venmo) {
      this(first, last, venmo, null);
    }

    boolean named() {
      return first != null && !first.isBlank();
    }

    boolean complete() {
      return named() && last != null && !last.isBlank() && venmo != null && !venmo.isBlank();
    }
  }
}
