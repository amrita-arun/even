package dev.even.splits;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Computes integer-cent shares. Owner is represented by {@code personId == null}.
 * Tax and tip are allocated in proportion to each party's pre-tax basis, then
 * rounded with largest remainder so the cents sum exactly to the charge.
 */
@Component
public class SplitEngine {

  public record ShareInput(UUID personId, Long shareCents, Integer basisPoints, Long itemizedCents) {}

  public record ComputedShare(UUID personId, long shareCents) {}

  public List<ComputedShare> compute(
      long totalCents,
      SplitMethod method,
      List<ShareInput> inputs,
      long taxCents,
      long tipCents) {
    if (totalCents <= 0) {
      throw new IllegalArgumentException("split total must be positive");
    }
    if (taxCents < 0 || tipCents < 0) {
      throw new IllegalArgumentException("tax and tip must be >= 0");
    }
    if (taxCents + tipCents > totalCents) {
      throw new IllegalArgumentException("tax + tip cannot exceed total");
    }
    if (inputs == null || inputs.isEmpty()) {
      throw new IllegalArgumentException("at least one share is required");
    }
    boolean hasOwner = inputs.stream().anyMatch(i -> i.personId() == null);
    if (!hasOwner) {
      throw new IllegalArgumentException("owner share (personId null) is required");
    }
    long seenNull = inputs.stream().filter(i -> i.personId() == null).count();
    if (seenNull != 1) {
      throw new IllegalArgumentException("exactly one owner share is required");
    }

    long pretaxTotal = totalCents - taxCents - tipCents;
    Map<UUID, Long> pretaxByPerson = pretaxShares(method, pretaxTotal, inputs);
    long pretaxSum = pretaxByPerson.values().stream().mapToLong(Long::longValue).sum();
    if (pretaxSum != pretaxTotal) {
      throw new IllegalStateException("pre-tax shares sum to " + pretaxSum + " not " + pretaxTotal);
    }

    Map<UUID, Long> taxByPerson = allocateAddon(taxCents, pretaxByPerson);
    Map<UUID, Long> tipByPerson = allocateAddon(tipCents, pretaxByPerson);

    List<ComputedShare> out = new ArrayList<>();
    long grand = 0L;
    for (ShareInput input : inputs) {
      UUID id = input.personId();
      long share = pretaxByPerson.getOrDefault(id, 0L)
          + taxByPerson.getOrDefault(id, 0L)
          + tipByPerson.getOrDefault(id, 0L);
      out.add(new ComputedShare(id, share));
      grand += share;
    }
    if (grand != totalCents) {
      throw new IllegalStateException("shares sum to " + grand + " not " + totalCents);
    }
    return out;
  }

  private Map<UUID, Long> pretaxShares(SplitMethod method, long pretaxTotal, List<ShareInput> inputs) {
    return switch (method) {
      case equal -> {
        List<LargestRemainder.Party> parties = inputs.stream()
            .map(i -> new LargestRemainder.Party(i.personId(), 1.0))
            .toList();
        yield toMap(LargestRemainder.allocate(pretaxTotal, parties));
      }
      case percentage -> {
        List<LargestRemainder.Party> parties = new ArrayList<>();
        int points = 0;
        for (ShareInput input : inputs) {
          if (input.basisPoints() == null || input.basisPoints() < 0) {
            throw new IllegalArgumentException("percentage shares need non-negative basisPoints");
          }
          points += input.basisPoints();
          parties.add(new LargestRemainder.Party(input.personId(), input.basisPoints()));
        }
        if (points != 10_000) {
          throw new IllegalArgumentException("basisPoints must sum to 10000, got " + points);
        }
        yield toMap(LargestRemainder.allocate(pretaxTotal, parties));
      }
      case fixed -> {
        Map<UUID, Long> map = new LinkedHashMap<>();
        long sum = 0L;
        for (ShareInput input : inputs) {
          if (input.shareCents() == null || input.shareCents() < 0) {
            throw new IllegalArgumentException("fixed shares need non-negative shareCents");
          }
          map.put(input.personId(), input.shareCents());
          sum += input.shareCents();
        }
        if (sum != pretaxTotal) {
          throw new IllegalArgumentException(
              "fixed pre-tax shares must sum to " + pretaxTotal + ", got " + sum);
        }
        yield map;
      }
      case itemized -> {
        Map<UUID, Long> map = new LinkedHashMap<>();
        long sum = 0L;
        for (ShareInput input : inputs) {
          if (input.itemizedCents() == null || input.itemizedCents() < 0) {
            throw new IllegalArgumentException("itemized shares need non-negative itemizedCents");
          }
          map.put(input.personId(), input.itemizedCents());
          sum += input.itemizedCents();
        }
        if (sum != pretaxTotal) {
          throw new IllegalArgumentException(
              "itemized pre-tax shares must sum to " + pretaxTotal + ", got " + sum);
        }
        yield map;
      }
    };
  }

  private Map<UUID, Long> allocateAddon(long addonCents, Map<UUID, Long> pretaxByPerson) {
    if (addonCents == 0) {
      Map<UUID, Long> zeros = new HashMap<>();
      pretaxByPerson.keySet().forEach(k -> zeros.put(k, 0L));
      return zeros;
    }
    long pretaxSum = pretaxByPerson.values().stream().mapToLong(Long::longValue).sum();
    List<LargestRemainder.Party> parties = new ArrayList<>();
    if (pretaxSum == 0) {
      // Degenerate: split addon equally.
      pretaxByPerson.keySet().forEach(id -> parties.add(new LargestRemainder.Party(id, 1.0)));
    } else {
      pretaxByPerson.forEach((id, cents) ->
          parties.add(new LargestRemainder.Party(id, cents.doubleValue())));
    }
    return toMap(LargestRemainder.allocate(addonCents, parties));
  }

  private static Map<UUID, Long> toMap(List<LargestRemainder.Allocation> allocations) {
    Map<UUID, Long> map = new LinkedHashMap<>();
    for (LargestRemainder.Allocation a : allocations) {
      map.put(a.tieKey(), a.cents());
    }
    return map;
  }
}
