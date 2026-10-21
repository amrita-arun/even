package dev.even.settlement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementDraftsTest {

  @Test
  void venmoBuildsChargeLink() {
    SettlementDrafts.Draft draft = SettlementDrafts.venmo("alex-pay", "Alex", 3_000L, "Dinner");
    assertThat(draft.url()).contains("txn=charge");
    assertThat(draft.url()).contains("recipients=alex-pay");
    assertThat(draft.url()).contains("amount=30.00");
    assertThat(draft.text()).contains("$30.00");
  }

  @Test
  void venmoWithoutUsernameDoesNotGuessARecipient() {
    SettlementDrafts.Draft draft = SettlementDrafts.venmo(null, "Reeya", 3_000L, "Dinner");
    assertThat(draft.url()).doesNotContain("recipients=");
    assertThat(draft.text()).contains("No username saved for Reeya");
  }

  @Test
  void zelleBuildsReminderTextWithoutUrl() {
    SettlementDrafts.Draft draft = SettlementDrafts.zelle("alex@bank.com", "Alex", 3_000L, "Dinner");
    assertThat(draft.url()).isNull();
    assertThat(draft.text()).contains("Zelle me $30.00");
    assertThat(draft.text()).contains("Alex");
  }
}
