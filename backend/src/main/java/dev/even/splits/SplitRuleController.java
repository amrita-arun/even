package dev.even.splits;

import dev.even.auth.CurrentUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/split-rules")
public class SplitRuleController {

  private final SplitRuleService rules;

  public SplitRuleController(SplitRuleService rules) {
    this.rules = rules;
  }

  @GetMapping
  public List<SplitRuleService.RuleRecord> list(JwtAuthenticationToken authentication) {
    return rules.listRules(CurrentUser.from(authentication).id());
  }

  @PostMapping
  public SplitRuleService.RuleRecord create(
      JwtAuthenticationToken authentication, @RequestBody CreateRuleRequest body) {
    return rules.createRule(
        CurrentUser.from(authentication).id(),
        body.name(),
        body.merchantMatch(),
        body.method(),
        body.personId(),
        body.groupId(),
        body.basisPoints());
  }

  public record CreateRuleRequest(
      String name,
      String merchantMatch,
      String method,
      UUID personId,
      UUID groupId,
      Integer basisPoints) {}
}
