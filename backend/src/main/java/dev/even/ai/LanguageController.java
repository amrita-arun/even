package dev.even.ai;

import dev.even.auth.CurrentUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class LanguageController {

  private final LanguageSplitService language;

  public LanguageController(LanguageSplitService language) {
    this.language = language;
  }

  @PostMapping("/api/splits/parse")
  public LanguageSplitService.ParseOutcome parse(
      JwtAuthenticationToken authentication, @RequestBody ParseRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    if (body.transactionId() == null) {
      throw new IllegalArgumentException("transactionId is required");
    }
    return language.parse(user.id(), body.transactionId(), body.text());
  }

  @PostMapping("/api/receipts")
  public LanguageSplitService.ParseOutcome receipt(
      JwtAuthenticationToken authentication, @RequestBody ParseRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    if (body.transactionId() == null) {
      throw new IllegalArgumentException("transactionId is required");
    }
    return language.receipt(user.id(), body.transactionId(), body.text());
  }

  @PostMapping("/api/reminders")
  public LanguageSplitService.ReminderOutcome remind(
      JwtAuthenticationToken authentication, @RequestBody ReminderRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    if (body.personId() == null) {
      throw new IllegalArgumentException("personId is required");
    }
    return language.remind(user.id(), body.personId(), body.draft());
  }

  public record ParseRequest(UUID transactionId, String text) {}

  public record ReminderRequest(UUID personId, String draft) {}
}
