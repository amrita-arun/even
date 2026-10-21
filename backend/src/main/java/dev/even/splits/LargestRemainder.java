package dev.even.splits;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Allocates {@code totalCents} across parties using floor + largest remainder.
 * Ties break by stable {@code tieKey} (person id, or nulls-last for the owner).
 */
public final class LargestRemainder {

  private LargestRemainder() {}

  public record Party(UUID tieKey, double weight) {}

  public record Allocation(UUID tieKey, long cents) {}

  public static List<Allocation> allocate(long totalCents, List<Party> parties) {
    if (parties.isEmpty()) {
      throw new IllegalArgumentException("need at least one party");
    }
    if (totalCents < 0) {
      throw new IllegalArgumentException("totalCents must be >= 0");
    }
    double weightSum = parties.stream().mapToDouble(Party::weight).sum();
    if (weightSum <= 0) {
      throw new IllegalArgumentException("weights must sum to a positive number");
    }

    record Scratch(UUID tieKey, long floor, double fraction) {}
    List<Scratch> scratched = new ArrayList<>(parties.size());
    long assigned = 0L;
    for (Party party : parties) {
      double exact = totalCents * (party.weight() / weightSum);
      long floor = (long) Math.floor(exact);
      scratched.add(new Scratch(party.tieKey(), floor, exact - floor));
      assigned += floor;
    }

    long remainder = totalCents - assigned;
    scratched.sort(Comparator
        .comparingDouble(Scratch::fraction).reversed()
        .thenComparing(s -> s.tieKey() == null ? "\uFFFF" : s.tieKey().toString()));

    List<Allocation> out = new ArrayList<>(scratched.size());
    for (int i = 0; i < scratched.size(); i++) {
      Scratch s = scratched.get(i);
      long extra = i < remainder ? 1L : 0L;
      out.add(new Allocation(s.tieKey(), s.floor() + extra));
    }
    return out;
  }
}
