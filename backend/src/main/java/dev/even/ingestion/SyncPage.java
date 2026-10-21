package dev.even.ingestion;

import java.util.List;

public record SyncPage(
    List<ProviderTransaction> added,
    List<ProviderTransaction> modified,
    List<String> removedExternalIds,
    String nextCursor) {

  public SyncPage {
    added = added == null ? List.of() : List.copyOf(added);
    modified = modified == null ? List.of() : List.copyOf(modified);
    removedExternalIds =
        removedExternalIds == null ? List.of() : List.copyOf(removedExternalIds);
    nextCursor = nextCursor == null ? "" : nextCursor;
  }

  public static SyncPage ofAdded(List<ProviderTransaction> added) {
    return new SyncPage(added, List.of(), List.of(), "");
  }
}
