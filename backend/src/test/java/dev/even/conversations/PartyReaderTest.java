package dev.even.conversations;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PartyReaderTest {

  @Test
  void readsASentenceWithTwoPeople() {
    List<PartyReader.Mention> mentions = PartyReader.read(
        "reeya sawhney venmo reeyas, alex had the drinks.");

    assertThat(mentions).containsExactly(
        new PartyReader.Mention("Reeya", "Sawhney", "reeyas"),
        new PartyReader.Mention("Alex", null, null));
  }

  @Test
  void readsMeAndAFirstName() {
    assertThat(PartyReader.read("Me and reeya")).containsExactly(
        new PartyReader.Mention("Reeya", null, null));
    assertThat(PartyReader.read("with reeya")).containsExactly(
        new PartyReader.Mention("Reeya", null, null));
  }

  @Test
  void ignoresGreetingsAndBareYes() {
    assertThat(PartyReader.read("hi")).isEmpty();
    assertThat(PartyReader.read("Y")).isEmpty();
    assertThat(PartyReader.read("yes")).isEmpty();
  }

  @Test
  void followUpFillsLastNameAndVenmo() {
    PartyReader.Mention filled = PartyReader.fill(
        new PartyReader.Mention("Alex", null, null), "Sawhney, reeyas");

    assertThat(filled).isEqualTo(new PartyReader.Mention("Alex", "Sawhney", "reeyas"));
  }

  @Test
  void followUpAcceptsAVenmoHandleAlone() {
    PartyReader.Mention filled = PartyReader.fill(
        new PartyReader.Mention("Alex", "Kim", null), "alex-kim");

    assertThat(filled.venmo()).isEqualTo("alex-kim");
    assertThat(filled.last()).isEqualTo("Kim");
  }
}
