package dev.even.ingestion;

import dev.even.accounts.AccountService;
import dev.even.auth.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

  private final CsvConnector csvConnector;
  private final CapitalOnePdfConnector capitalOnePdfConnector;
  private final IngestService ingest;
  private final AccountService accounts;

  public ImportController(
      CsvConnector csvConnector,
      CapitalOnePdfConnector capitalOnePdfConnector,
      IngestService ingest,
      AccountService accounts) {
    this.csvConnector = csvConnector;
    this.capitalOnePdfConnector = capitalOnePdfConnector;
    this.ingest = ingest;
    this.accounts = accounts;
  }

  @PostMapping(path = "/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ImportResponse importCsv(
      JwtAuthenticationToken authentication,
      @RequestParam("account_id") UUID accountId,
      @RequestParam("file") MultipartFile file) throws IOException {
    return importStatement(authentication, accountId, file);
  }

  @PostMapping(path = "/statement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ImportResponse importStatement(
      JwtAuthenticationToken authentication,
      @RequestParam("account_id") UUID accountId,
      @RequestParam("file") MultipartFile file) throws IOException {
    CurrentUser user = CurrentUser.from(authentication);
    accounts.requireOwned(accountId, user.id());
    SyncPage page = parse(accountId, file);
    String cursor = ingest.apply(accountId, user.id(), page);
    return new ImportResponse(page.added().size(), cursor);
  }

  private SyncPage parse(UUID accountId, MultipartFile file) throws IOException {
    String name = file.getOriginalFilename() == null
        ? ""
        : file.getOriginalFilename().toLowerCase(Locale.ROOT);
    String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
    if (name.endsWith(".pdf") || contentType.contains("pdf")) {
      return capitalOnePdfConnector.fetch(accountId, file.getInputStream());
    }
    if (name.endsWith(".csv") || contentType.contains("csv") || contentType.contains("text")) {
      return csvConnector.fetch(accountId, file.getInputStream());
    }
    throw new IllegalArgumentException("unsupported statement type; upload a CSV or Capital One PDF");
  }

  public record ImportResponse(int imported, String nextCursor) {}
}
