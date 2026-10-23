package dev.even.proposals;

import dev.even.auth.CurrentUser;
import dev.even.conversations.ConversationService;
import dev.even.people.PeopleService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/proposals")
public class ProposalController {

  private final ProposalService proposals;
  private final PeopleService people;
  private final ConversationService conversations;

  public ProposalController(
      ProposalService proposals, PeopleService people, ConversationService conversations) {
    this.proposals = proposals;
    this.people = people;
    this.conversations = conversations;
  }

  @GetMapping
  public List<ProposalResponse> list(
      JwtAuthenticationToken authentication,
      @RequestParam(required = false) String status) {
    CurrentUser user = CurrentUser.from(authentication);
    return proposals.list(user.id(), status).stream()
        .map(row -> ProposalResponse.from(row, people, user.id()))
        .toList();
  }

  @PostMapping("/{id}/approve")
  public ProposalResponse approve(
      JwtAuthenticationToken authentication, @PathVariable UUID id) {
    CurrentUser user = CurrentUser.from(authentication);
    ProposalService.ProposalRecord row = proposals.approve(user.id(), id);
    conversations.onProposalDecided(user.id(), id, true);
    return ProposalResponse.from(row, people, user.id());
  }

  @PostMapping("/{id}/reject")
  public ProposalResponse reject(
      JwtAuthenticationToken authentication, @PathVariable UUID id) {
    CurrentUser user = CurrentUser.from(authentication);
    ProposalService.ProposalRecord row = proposals.reject(user.id(), id);
    conversations.onProposalDecided(user.id(), id, false);
    return ProposalResponse.from(row, people, user.id());
  }

  @PostMapping("/{id}/edit")
  public ProposalResponse edit(
      JwtAuthenticationToken authentication,
      @PathVariable UUID id,
      @RequestBody EditRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    ProposalService.ProposalRecord row = proposals.edit(user.id(), id, body.personIds());
    return ProposalResponse.from(row, people, user.id());
  }

  public record EditRequest(List<UUID> personIds) {}

  public record ProposalResponse(
      UUID id,
      UUID transactionId,
      String description,
      long amountCents,
      String status,
      int score,
      String reason,
      List<UUID> personIds,
      List<String> personNames) {
    static ProposalResponse from(
        ProposalService.ProposalRecord row, PeopleService people, UUID userId) {
      List<String> names = row.personIds().stream()
          .map(id -> people.requireOwned(userId, id).displayName())
          .toList();
      return new ProposalResponse(
          row.id(),
          row.transactionId(),
          row.description(),
          row.amountCents(),
          row.status(),
          row.score(),
          row.reason(),
          row.personIds(),
          names);
    }
  }
}
