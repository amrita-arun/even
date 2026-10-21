package dev.even.transactions;

import dev.even.auth.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

  private final TransactionService transactions;

  public TransactionController(TransactionService transactions) {
    this.transactions = transactions;
  }

  @GetMapping
  public List<TransactionService.TransactionRow> list(
      JwtAuthenticationToken authentication,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String state) {
    CurrentUser user = CurrentUser.from(authentication);
    return transactions.list(user.id(), from, to, accountId, q, state);
  }

  @GetMapping("/{id}/entries")
  public List<TransactionService.LedgerEntryRow> entries(
      JwtAuthenticationToken authentication,
      @PathVariable UUID id) {
    CurrentUser user = CurrentUser.from(authentication);
    return transactions.entries(user.id(), id);
  }
}
