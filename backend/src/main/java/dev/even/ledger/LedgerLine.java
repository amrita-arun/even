package dev.even.ledger;

/**
 * One journal line. {@code amountCents} is signed: debit positive, credit negative.
 */
public record LedgerLine(String path, long amountCents) {
  public LedgerLine {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("ledger path is required");
    }
    if (amountCents == 0L) {
      throw new IllegalArgumentException("ledger line amount must be non-zero");
    }
  }
}
