package dev.even.people;

import dev.even.auth.CurrentUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/people")
public class PeopleController {

  private final PeopleService people;

  public PeopleController(PeopleService people) {
    this.people = people;
  }

  @GetMapping
  public List<PersonResponse> list(JwtAuthenticationToken authentication) {
    CurrentUser user = CurrentUser.from(authentication);
    return people.list(user.id()).stream().map(PersonResponse::from).toList();
  }

  @PostMapping
  public PersonResponse create(
      JwtAuthenticationToken authentication,
      @RequestBody CreatePersonRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    PeopleService.PersonRecord created = people.create(
        user.id(),
        body.firstName(),
        body.lastName(),
        body.displayName(),
        body.venmoHandle(),
        body.zelleHandle(),
        body.kind());
    return PersonResponse.from(created);
  }

  @PatchMapping("/{id}")
  public PersonResponse update(
      JwtAuthenticationToken authentication,
      @PathVariable UUID id,
      @RequestBody UpdatePersonRequest body) {
    CurrentUser user = CurrentUser.from(authentication);
    return PersonResponse.from(people.update(
        user.id(),
        id,
        body.firstName(),
        body.lastName(),
        body.venmoHandle(),
        body.zelleHandle()));
  }

  public record CreatePersonRequest(
      String displayName,
      String firstName,
      String lastName,
      String venmoHandle,
      String zelleHandle,
      String kind) {}

  public record UpdatePersonRequest(
      String firstName, String lastName, String venmoHandle, String zelleHandle) {}

  public record PersonResponse(
      UUID id,
      String displayName,
      String firstName,
      String lastName,
      String venmoHandle,
      String zelleHandle,
      String kind) {
    static PersonResponse from(PeopleService.PersonRecord row) {
      return new PersonResponse(
          row.id(),
          row.displayName(),
          row.firstName(),
          row.lastName(),
          row.venmoHandle(),
          row.zelleHandle(),
          row.kind());
    }
  }
}
