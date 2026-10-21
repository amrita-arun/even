package dev.even.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CapitalOnePdfConnectorTest {

  @Test
  void parsesPrintTransactionsPdf() throws Exception {
    CapitalOnePdfConnector connector = new CapitalOnePdfConnector();
    UUID accountId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    SyncPage page;
    try (InputStream in = new ClassPathResource(
        "fixtures/SeptemberStatementCapitalOne.pdf").getInputStream()) {
      page = connector.fetch(accountId, in);
    }

    List<ProviderTransaction> rows = page.added();
    assertThat(rows).hasSize(48);
    assertThat(rows.stream().filter(r -> "payment".equals(r.kind())).count()).isEqualTo(3);
    assertThat(rows.stream().filter(r -> "purchase".equals(r.kind())).count()).isEqualTo(45);

    ProviderTransaction autopay = rows.getFirst();
    assertThat(autopay.postedOn()).hasToString("2026-10-20");
    assertThat(autopay.amountCents()).isEqualTo(-53_633L);
    assertThat(autopay.kind()).isEqualTo("payment");
    assertThat(autopay.description()).containsIgnoringCase("AutoPay");

    ProviderTransaction pizza = rows.stream()
        .filter(r -> r.description().contains("Domino"))
        .findFirst()
        .orElseThrow();
    assertThat(pizza.amountCents()).isEqualTo(277L);
    assertThat(pizza.kind()).isEqualTo("purchase");

    // Re-parse is stable: same content hash external ids.
    try (InputStream in = new ClassPathResource(
        "fixtures/SeptemberStatementCapitalOne.pdf").getInputStream()) {
      SyncPage again = connector.fetch(accountId, in);
      assertThat(again.added())
          .extracting(ProviderTransaction::externalId)
          .containsExactlyElementsOf(
              rows.stream().map(ProviderTransaction::externalId).toList());
    }
  }
}
