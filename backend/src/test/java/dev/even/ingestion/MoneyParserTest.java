package dev.even.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyParserTest {

  @Test
  void parsesTwoDecimalDollars() {
    assertThat(MoneyParser.dollarsToCents("4.50")).isEqualTo(450L);
    assertThat(MoneyParser.dollarsToCents("-12.00")).isEqualTo(-1200L);
    assertThat(MoneyParser.dollarsToCents("0.01")).isEqualTo(1L);
  }

  @Test
  void rejectsMoreThanTwoDecimals() {
    assertThatThrownBy(() -> MoneyParser.dollarsToCents("4.555"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("two decimal places");
  }
}
