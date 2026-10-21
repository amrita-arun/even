package dev.even.proposals;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SplitSuggestionScorerTest {

  @Test
  void diningFlagAndHistoryClearTheBar() {
    int score = SplitSuggestionScorer.score("Dining", 8_000L, true, 1.0);
    assertThat(score).isEqualTo(100);
    assertThat(SplitSuggestionScorer.suggest(score)).isTrue();
  }

  @Test
  void smallUncategorizedChargeIsNotSuggested() {
    int score = SplitSuggestionScorer.score("Grocery", 1_200L, false, 0);
    assertThat(SplitSuggestionScorer.suggest(score)).isFalse();
  }
}
