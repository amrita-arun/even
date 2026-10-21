package dev.even.ingestion;

import java.time.LocalDate;
import java.util.Optional;

public record ProviderTransaction(
    String externalId,
    Optional<String> pendingExternalId,
    long amountCents,
    String currency,
    LocalDate postedOn,
    String status,
    String description,
    String kind,
    String rawJson) {

  public ProviderTransaction {
    if (externalId == null || externalId.isBlank()) {
      throw new IllegalArgumentException("externalId is required");
    }
    if (pendingExternalId == null) {
      pendingExternalId = Optional.empty();
    }
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("currency is required");
    }
    if (postedOn == null) {
      throw new IllegalArgumentException("postedOn is required");
    }
    if (status == null || status.isBlank()) {
      throw new IllegalArgumentException("status is required");
    }
    if (description == null) {
      throw new IllegalArgumentException("description is required");
    }
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException("kind is required");
    }
    if (rawJson == null || rawJson.isBlank()) {
      rawJson = "{}";
    }
  }
}
