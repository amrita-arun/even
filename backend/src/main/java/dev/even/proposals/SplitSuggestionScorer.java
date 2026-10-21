package dev.even.proposals;

import java.util.Locale;

/**
 * Closed heuristic. No model weights: category, amount, an open flag, and how
 * often this merchant was split before.
 */
public final class SplitSuggestionScorer {

  public static final int SUGGEST_AT = 50;

  private SplitSuggestionScorer() {}

  public static int score(
      String category, long amountCents, boolean flagged, double historicalSplitRate) {
    int score = 0;
    String normalized = category == null ? "" : category.toLowerCase(Locale.ROOT);
    if (normalized.contains("dining") || normalized.contains("food")) {
      score += 30;
    } else if (normalized.contains("rideshare") || normalized.contains("transport")) {
      score += 25;
    }
    if (amountCents >= 4_000L) {
      score += 20;
    }
    if (flagged) {
      score += 20;
    }
    double rate = Math.clamp(historicalSplitRate, 0d, 1d);
    score += (int) Math.round(rate * 30d);
    return Math.min(score, 100);
  }

  public static boolean suggest(int score) {
    return score >= SUGGEST_AT;
  }
}
