package dev.even.splits;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SplitEngineTest {

  private final SplitEngine engine = new SplitEngine();

  @Test
  void equalFourWaysWithTaxAndTip() {
    // $100 pretax + $10 tax + $10 tip = $120; four equal parties → $30 each.
    UUID alex = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    UUID sam = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    UUID jo = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    List<SplitEngine.ComputedShare> shares = engine.compute(
        12_000L,
        SplitMethod.equal,
        List.of(
            new SplitEngine.ShareInput(null, null, null, null),
            new SplitEngine.ShareInput(alex, null, null, null),
            new SplitEngine.ShareInput(sam, null, null, null),
            new SplitEngine.ShareInput(jo, null, null, null)),
        1_000L,
        1_000L);

    assertThat(shares).hasSize(4);
    assertThat(shares.stream().mapToLong(SplitEngine.ComputedShare::shareCents).sum())
        .isEqualTo(12_000L);
    assertThat(shares).allMatch(s -> s.shareCents() == 3_000L);
  }

  @Test
  void fixedSharesMustSumToPretax() {
    assertThatThrownBy(() -> engine.compute(
            10_000L,
            SplitMethod.fixed,
            List.of(
                new SplitEngine.ShareInput(null, 6_000L, null, null),
                new SplitEngine.ShareInput(UUID.randomUUID(), 3_000L, null, null)),
            0L,
            0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fixed pre-tax");
  }

  @Property(tries = 40)
  void equalSharesAlwaysSumToTotal(
      @ForAll @LongRange(min = 1, max = 100_000) long total,
      @ForAll @IntRange(min = 2, max = 8) int partyCount) {
    List<SplitEngine.ShareInput> inputs = new ArrayList<>();
    inputs.add(new SplitEngine.ShareInput(null, null, null, null));
    for (int i = 1; i < partyCount; i++) {
      inputs.add(new SplitEngine.ShareInput(UUID.randomUUID(), null, null, null));
    }
    List<SplitEngine.ComputedShare> shares =
        engine.compute(total, SplitMethod.equal, inputs, 0L, 0L);
    assertThat(shares.stream().mapToLong(SplitEngine.ComputedShare::shareCents).sum())
        .isEqualTo(total);
  }
}
