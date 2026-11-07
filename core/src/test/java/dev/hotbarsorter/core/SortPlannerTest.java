package dev.hotbarsorter.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SortPlannerTest {
    private final SortPlanner planner = new SortPlanner();
    private ItemView item(String id, String... tags) { return new ItemView(id, Set.of(tags), 1, 100, 1); }
    @Test void matchesItemsByTagAndPrefersHigherPrioritySlot() {
        var slots = new ArrayList<List<Rule>>(Collections.nCopies(9, List.of()));
        slots.set(0, List.of(new Rule("#minecraft:swords", 2, Rule.Pick.FIRST, false)));
        slots.set(1, List.of(new Rule("minecraft:diamond_sword", 8, Rule.Pick.FIRST, false)));
        var inventory = new ArrayList<ItemView>(Collections.nCopies(36, null));
        inventory.set(0, item("minecraft:diamond_sword", "minecraft:swords"));
        var result = planner.plan(inventory, new RuleSet(slots, "keep"));
        assertEquals(List.of(new SortPlanner.Swap(0, 1)), result);
    }

    @Test void bestChoosesHighestQualityAndDoesNotUseLockedSlot() {
        var slots = new ArrayList<List<Rule>>(Collections.nCopies(9, List.of()));
        slots.set(0, List.of(Rule.lockedRule()));
        slots.set(1, List.of(new Rule("#minecraft:swords", 1, Rule.Pick.BEST, false)));
        var low = item("minecraft:iron_sword", "minecraft:swords");
        var high = new ItemView("minecraft:diamond_sword", Set.of("minecraft:swords"), 1, 500, 4);
        var inventory = new ArrayList<ItemView>(Collections.nCopies(36, null));
        inventory.set(9, low);
        inventory.set(10, high);
        assertEquals(List.of(new SortPlanner.Swap(10, 1)), planner.plan(inventory, new RuleSet(slots, "keep")));
    }

    @Test void requiresNineRulesSlots() {
        assertThrows(IllegalArgumentException.class, () -> new RuleSet(List.of(), "keep"));
    }

    @Test void rejectsMalformedNamespacedIds() {
        assertThrows(IllegalArgumentException.class, () -> new Rule("#not a tag", 0, Rule.Pick.FIRST, false));
    }

    @Test void movesUnmatchedHotbarItemToFirstFreeInventorySlotWhenConfigured() {
        var inventory = new ArrayList<ItemView>(Collections.nCopies(36, null));
        inventory.set(0, item("minecraft:stick"));
        inventory.set(12, item("minecraft:stone"));
        var result = planner.plan(inventory, new RuleSet(Collections.nCopies(9, List.of()), "inventory"));
        assertEquals(List.of(new SortPlanner.Swap(9, 0)), result);
    }
}
