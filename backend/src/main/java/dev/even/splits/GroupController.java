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
@RequestMapping("/api/groups")
public class GroupController {

  private final SplitRuleService rules;

  public GroupController(SplitRuleService rules) {
    this.rules = rules;
  }

  @GetMapping
  public List<SplitRuleService.GroupRecord> list(JwtAuthenticationToken authentication) {
    return rules.listGroups(CurrentUser.from(authentication).id());
  }

  @PostMapping
  public SplitRuleService.GroupRecord create(
      JwtAuthenticationToken authentication, @RequestBody CreateGroupRequest body) {
    return rules.createGroup(
        CurrentUser.from(authentication).id(), body.name(), body.personIds());
  }

  public record CreateGroupRequest(String name, List<UUID> personIds) {}
}
