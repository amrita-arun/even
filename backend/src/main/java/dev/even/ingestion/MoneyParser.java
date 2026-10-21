package dev.even.ingestion;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Parses dollar strings into integer cents. Rejects more than two decimal places. */
public final class MoneyParser {

  private MoneyParser() {}

  public static long dollarsToCents(String amount) {
    if (amount == null || amount.isBlank()) {
      throw new IllegalArgumentException("amount is required");
    }
    String trimmed = amount.trim();
    int dot = trimmed.indexOf('.');
    if (dot >= 0) {
      int decimals = trimmed.length() - dot - 1;
      if (decimals > 2) {
        throw new IllegalArgumentException(
            "amount must have at most two decimal places: " + amount);
      }
    }
    try {
      BigDecimal dollars = new BigDecimal(trimmed);
      return dollars.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      throw new IllegalArgumentException("invalid amount: " + amount, e);
    }
  }
}
