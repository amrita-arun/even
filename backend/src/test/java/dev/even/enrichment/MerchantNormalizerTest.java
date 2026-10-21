package dev.even.enrichment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantNormalizerTest {

  private final MerchantNormalizer normalizer = new MerchantNormalizer();

  @Test
  void stripsProcessorPrefixAndInfersCategory() {
    var sq = normalizer.normalize("SQ *YBSY SUPERETTE USC");
    assertThat(sq.displayName()).isEqualTo("YBSY SUPERETTE USC");

    var uber = normalizer.normalize("Uber Technologies");
    assertThat(uber.category()).isEqualTo("Rideshare");

    var toast = normalizer.normalize("Toast");
    assertThat(toast.category()).isEqualTo("Dining");
  }
}
