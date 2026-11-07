package dev.hotbarsorter.core;

import java.util.*;

/** Computes swaps using only a supplied inventory snapshot; it never mutates Minecraft state. */
public final class SortPlanner {
    public record Swap(int inventorySlot, int hotbarSlot) {}
    private record Candidate(int index, int slot, int priority, Rule.Pick pick) {}

    public List<Swap> plan(List<ItemView> inventory, RuleSet rules) {
        if (inventory.size() < 9) throw new IllegalArgumentException("inventory snapshot must include nine hotbar slots");
        List<Candidate> candidates = new ArrayList<>();
        Set<Integer> matchedItems = new HashSet<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemView item = inventory.get(i);
            if (item == null) continue;
            for (int slot = 0; slot < 9; slot++) {
                if (rules.locked(slot)) continue;
                int itemIndex = i;
                int targetSlot = slot;
                rules.rules(slot).stream().filter(r -> !r.lock() && item.matches(r.match()))
                        .max(Comparator.comparingInt(Rule::priority))
                        .ifPresent(r -> {
                            matchedItems.add(itemIndex);
                            candidates.add(new Candidate(itemIndex, targetSlot, r.priority(), r.pick()));
                        });
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::priority).reversed()
                .thenComparing(c -> c.pick == Rule.Pick.BEST ? 0 : 1)
                .thenComparing(Comparator.comparingInt((Candidate c) -> c.pick == Rule.Pick.BEST
                        ? inventory.get(c.index).quality() : -c.index).reversed())
                .thenComparingInt(Candidate::index));
        Set<Integer> usedItems = new HashSet<>(), filledSlots = new HashSet<>();
        List<Swap> result = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!usedItems.add(c.index) || !filledSlots.add(c.slot)) continue;
            if (c.index != c.slot) result.add(new Swap(c.index, c.slot));
        }
        if (rules.unmatched().equals("inventory")) {
            for (int slot = 0; slot < 9; slot++) {
                if (rules.locked(slot) || filledSlots.contains(slot)) continue;
                ItemView item = inventory.get(slot);
                if (item == null || matchedItems.contains(slot)) continue;
                for (int empty = 9; empty < inventory.size(); empty++) {
                    if (inventory.get(empty) == null) {
                        result.add(new Swap(empty, slot));
                        break;
                    }
                }
            }
        }
        return List.copyOf(result);
    }
}
