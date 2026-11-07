package dev.hotbarsorter.core;

import java.util.Objects;

/** Minecraft-free rule model. Match expressions are item IDs or #tag IDs. */
public record Rule(String match, int priority, Pick pick, boolean lock) {
    public enum Pick { FIRST, BEST }

    public Rule {
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(pick, "pick");
        if (match.isBlank() && !lock) throw new IllegalArgumentException("match cannot be blank");
        if (match.startsWith("#") && match.length() == 1)
            throw new IllegalArgumentException("tag match cannot be empty");
        if (!lock) {
            String id = match.startsWith("#") ? match.substring(1) : match;
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
                throw new IllegalArgumentException("match must be a namespaced item or tag ID");
        }
    }

    public static Rule lockedRule() { return new Rule("", 0, Pick.FIRST, true); }
}
