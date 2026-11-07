package dev.hotbarsorter.core;

import java.util.*;

/** Immutable nine-slot rule set; indices exposed by this class are zero-based. */
public final class RuleSet {
    private final List<List<Rule>> slots;
    private final String unmatched;

    public RuleSet(List<List<Rule>> slots, String unmatched) {
        if (slots.size() != 9) throw new IllegalArgumentException("exactly nine hotbar slots required");
        if (!Set.of("keep", "inventory").contains(unmatched)) throw new IllegalArgumentException("unsupported unmatched mode");
        this.slots = slots.stream().map(List::copyOf).toList();
        this.unmatched = unmatched;
    }

    public List<Rule> rules(int slot) { return slots.get(slot); }
    public boolean locked(int slot) { return slots.get(slot).stream().anyMatch(Rule::lock); }
    public String unmatched() { return unmatched; }
    public List<List<Rule>> slots() { return slots; }

    public static RuleSet empty() { return new RuleSet(Collections.nCopies(9, List.of()), "keep"); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RuleSet other)) return false;
        return Objects.equals(slots, other.slots) && Objects.equals(unmatched, other.unmatched);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slots, unmatched);
    }
}
