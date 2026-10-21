package dev.even.accounts;

import dev.even.auth.CurrentUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

  private final AccountService accounts;

  public AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping
  public List<AccountResponse> list(JwtAuthenticationToken authentication) {
    CurrentUser user = CurrentUser.from(authentication);
    return accounts.list(user.id()).stream()
        .map(AccountResponse::from)
        .toList();
  }

  @PostMapping
  public AccountResponse create(
      JwtAuthenticationToken authentication,
      @RequestBody CreateAccountRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    AccountService.AccountRecord created = accounts.create(
        user.id(),
        user.email(),
        body.name(),
        body.accountType(),
        body.currency());
    return AccountResponse.from(created);
  }

  public record CreateAccountRequest(String name, String accountType, String currency) {}

  public record AccountResponse(
      java.util.UUID id, String name, String accountType, String currency) {
    static AccountResponse from(AccountService.AccountRecord row) {
      return new AccountResponse(row.id(), row.name(), row.accountType(), row.currency());
    }
  }
}
