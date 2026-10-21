package dev.even.reports;

import dev.even.auth.CurrentUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

  private final ReportService reports;

  public ReportController(ReportService reports) {
    this.reports = reports;
  }

  @GetMapping("/true-spend")
  public TrueSpendResponse trueSpend(JwtAuthenticationToken authentication) {
    CurrentUser user = CurrentUser.from(authentication);
    return new TrueSpendResponse(reports.trueSpendCents(user.id()));
  }

  @GetMapping("/month")
  public ReportService.MonthReport month(
      JwtAuthenticationToken authentication,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    CurrentUser user = CurrentUser.from(authentication);
    return reports.month(user.id(), from, to);
  }

  @GetMapping("/balances")
  public ReportService.BalancesReport balances(JwtAuthenticationToken authentication) {
    CurrentUser user = CurrentUser.from(authentication);
    return reports.balances(user.id());
  }

  @GetMapping("/receivables")
  public List<ReceivableResponse> receivables(JwtAuthenticationToken authentication) {
    CurrentUser user = CurrentUser.from(authentication);
    return reports.receivables(user.id()).stream()
        .map(r -> new ReceivableResponse(r.personId(), r.displayName(), r.kind(), r.amountCents()))
        .toList();
  }

  public record TrueSpendResponse(long amountCents) {}

  public record ReceivableResponse(
      UUID personId, String displayName, String kind, long amountCents) {}
}
