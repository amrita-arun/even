package dev.even.ingestion;

public class CsvRowException extends RuntimeException {
  private final int lineNumber;

  public CsvRowException(int lineNumber, String message) {
    super("line " + lineNumber + ": " + message);
    this.lineNumber = lineNumber;
  }

  public int lineNumber() {
    return lineNumber;
  }
}
