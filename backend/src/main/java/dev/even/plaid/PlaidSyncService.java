package dev.even.plaid;

import com.fasterxml.jackson.databind.JsonNode;
import dev.even.ingestion.IngestService;
import dev.even.ingestion.PlaidSyncPageMapper;
import dev.even.ingestion.SyncPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PlaidSyncService {

  private final PlaidItemService items;
  private final PlaidApiClient plaid;
  private final PlaidSyncPageMapper mapper;
  private final IngestService ingest;

  public PlaidSyncService(
      PlaidItemService items,
      PlaidApiClient plaid,
      PlaidSyncPageMapper mapper,
      IngestService ingest) {
    this.items = items;
    this.plaid = plaid;
    this.mapper = mapper;
    this.ingest = ingest;
  }

  @Transactional
  public SyncSummary syncAccount(UUID userId, UUID accountId) {
    PlaidItemService.ItemSecrets item = items.findByAccount(userId, accountId)
        .orElseThrow(() -> new IllegalArgumentException("account is not linked to Plaid"));
    int pages = 0;
    int imported = 0;
    List<PlaidItemService.LinkedAccountDetail> linked =
        items.findAccountDetails(userId, item.id());
    boolean routeByPlaidAccount = linked.stream()
        .anyMatch(row -> row.externalAccountId() != null && !row.externalAccountId().isBlank());
    Optional<String> cursor = Optional.ofNullable(item.cursor()).filter(c -> !c.isBlank());
    boolean hasMore = true;
    String nextCursor = cursor.orElse("");
    while (hasMore) {
      PlaidApiClient.SyncResult page = plaid.syncTransactions(item.accessToken(), cursor);
      if (!routeByPlaidAccount) {
        imported += ingestPage(accountId, userId, page.pageJson(), item.accountType());
      } else {
        for (PlaidItemService.LinkedAccountDetail row : linked) {
          if (row.externalAccountId() == null || row.externalAccountId().isBlank()) {
            continue;
          }
          JsonNode filtered = PlaidAccountPageFilter.forAccount(page.pageJson(), row.externalAccountId());
          imported += ingestPage(row.accountId(), userId, filtered, row.accountType());
        }
      }
      nextCursor = page.nextCursor();
      cursor = Optional.ofNullable(nextCursor).filter(c -> !c.isBlank());
      hasMore = page.hasMore();
      pages++;
      if (pages > 50) {
        throw new IllegalStateException("Plaid sync exceeded page safety limit");
      }
    }
    items.saveCursor(item.id(), nextCursor);
    return new SyncSummary(pages, imported, nextCursor);
  }

  private int ingestPage(UUID accountId, UUID userId, JsonNode page, String accountType) {
    SyncPage syncPage = mapper.fromJson(
        new ByteArrayInputStream(page.toString().getBytes(StandardCharsets.UTF_8)),
        accountType);
    ingest.apply(accountId, userId, syncPage);
    return syncPage.added().size() + syncPage.modified().size();
  }

  public record SyncSummary(int pages, int imported, String cursor) {}
}
