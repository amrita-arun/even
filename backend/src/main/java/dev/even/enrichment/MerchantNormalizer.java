package dev.even.enrichment;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class MerchantNormalizer {

  private static final Pattern PROCESSOR_PREFIX = Pattern.compile(
      "(?i)^(SQ\\s*\\*|TST\\s*\\*|UEP\\s*\\*|SP\\s*\\*|PP\\*\\s*)");
  private static final Pattern TRAILING_STORE_NUM = Pattern.compile("\\s+#?\\d{3,}$");

  public record NormalizedMerchant(String displayName, String category) {}

  public NormalizedMerchant normalize(String rawDescription) {
    if (rawDescription == null || rawDescription.isBlank()) {
      return new NormalizedMerchant("Unknown", "Uncategorized");
    }
    String cleaned = PROCESSOR_PREFIX.matcher(rawDescription.trim()).replaceFirst("");
    cleaned = TRAILING_STORE_NUM.matcher(cleaned).replaceFirst("");
    cleaned = cleaned.replaceAll("\\s{2,}", " ").strip();
    if (cleaned.isEmpty()) {
      cleaned = rawDescription.trim();
    }
    return new NormalizedMerchant(cleaned, inferCategory(cleaned));
  }

  static String inferCategory(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    if (containsAny(n, "uber", "lyft", "rideshare")) {
      return "Rideshare";
    }
    if (containsAny(n, "doordash", "uber eats", "grubhub")) {
      return "Dining";
    }
    if (containsAny(n, "pizza", "taco", "cafe", "coffee", "starbucks", "toast",
        "dining", "restaurant", "dominos", "domino")) {
      return "Dining";
    }
    if (containsAny(n, "trader joe", "ralphs", "grocery", "smart & final", "7-eleven")) {
      return "Grocery";
    }
    if (containsAny(n, "spotify", "phone", "cable")) {
      return "Phone/Cable";
    }
    if (containsAny(n, "shell", "chevron", "gas", "automotive")) {
      return "Gas/Automotive";
    }
    if (containsAny(n, "amazon", "target", "tiktok", "brandy", "cursor")) {
      return "Merchandise";
    }
    if (containsAny(n, "payment", "autopay", "alliant")) {
      return "Payment";
    }
    return "Uncategorized";
  }

  private static boolean containsAny(String haystack, String... needles) {
    for (String needle : needles) {
      if (haystack.contains(needle)) {
        return true;
      }
    }
    return false;
  }
}
