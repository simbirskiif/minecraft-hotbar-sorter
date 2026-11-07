package dev.hotbarsorter.core;

import java.util.Set;

/** Plain value snapshot supplied by a version-specific adapter. */
public record ItemView(String id, Set<String> tags, int count, int durabilityRemaining, int materialRank) {
    public ItemView { tags = Set.copyOf(tags); }
    public boolean matches(String expression) {
        return expression.startsWith("#") ? tags.contains(expression.substring(1)) : id.equals(expression);
    }
    public int quality() { return materialRank * 1_000_000 + durabilityRemaining * 1_000 + count; }
}
