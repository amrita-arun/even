package dev.even.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@RequestMapping("/api/rules")
public class RuleController {

  private final RuleService rules;
  private final ObjectMapper mapper;

  public RuleController(RuleService rules, ObjectMapper mapper) {
    this.rules = rules;
    this.mapper = mapper;
  }

  @GetMapping
  public List<RuleResponse> list(JwtAuthenticationToken authentication) throws Exception {
    CurrentUser user = CurrentUser.from(authentication);
    return rules.list(user.id()).stream()
        .map(this::toResponse)
        .toList();
  }

  @PostMapping
  public RuleResponse create(
      JwtAuthenticationToken authentication,
      @RequestBody CreateRuleRequest body) throws Exception {
    CurrentUser user = CurrentUser.from(authentication);
    JsonNode predicate = body.predicate();
    RuleService.RuleRecord created = rules.create(user.id(), body.name(), predicate);
    return toResponse(created);
  }

  private RuleResponse toResponse(RuleService.RuleRecord rule) {
    try {
      return new RuleResponse(
          rule.id(),
          rule.name(),
          mapper.readTree(rule.predicateJson()),
          rule.enabled());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public record CreateRuleRequest(String name, JsonNode predicate) {}

  public record RuleResponse(UUID id, String name, JsonNode predicate, boolean enabled) {}
}
