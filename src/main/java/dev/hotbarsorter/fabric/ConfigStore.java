package dev.hotbarsorter.fabric;

import com.google.gson.*;
import dev.hotbarsorter.core.*;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class ConfigStore {
    private final Path path = FabricLoader.getInstance().getConfigDir().resolve("hotbarsorter/config.json");
    boolean enabled = true;
    boolean onPickup;
    boolean onHotbarChange = true;
    boolean onInventoryChange = true;
    boolean onInventoryClose = true;
    boolean onWorldJoin;
    boolean onRespawn;
    int intervalTicks;
    Set<String> disabledServers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    RuleSet rules = RuleSet.empty();
    Map<String, RuleSet> presets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    Map<String, List<String>> customGroups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    String activePreset = "";
    private long lastModifiedMillis = -1;

    void reloadIfChanged() {
        try {
            long current = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1;
            if (current != lastModifiedMillis) load();
        } catch (IOException ex) { System.err.println("[HotbarSorter] Could not check config timestamp: " + ex.getMessage()); }
    }

    void load() {
        if (!Files.exists(path)) {
            initDefaultPresets();
            save();
            return;
        }
        try {
            lastModifiedMillis = Files.getLastModifiedTime(path).toMillis();
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            enabled = root.has("enabled") ? root.get("enabled").getAsBoolean() : true;
            JsonObject trigger = root.has("trigger") && root.get("trigger").isJsonObject()
                    ? root.getAsJsonObject("trigger") : new JsonObject();
            onPickup = bool(trigger, "onPickup", true);
            onHotbarChange = bool(trigger, "onHotbarChange", true);
            onInventoryChange = bool(trigger, "onInventoryChange", true);
            onInventoryClose = bool(trigger, "onInventoryClose", false);
            onWorldJoin = bool(trigger, "onWorldJoin", false);
            onRespawn = bool(trigger, "onRespawn", false);
            intervalTicks = Math.max(0, trigger.has("intervalTicks") ? trigger.get("intervalTicks").getAsInt() : 0);
            disabledServers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            if (root.has("disabledServers") && root.get("disabledServers").isJsonArray()) {
                for (JsonElement entry : root.getAsJsonArray("disabledServers")) {
                    try { disabledServers.add(entry.getAsString().trim().toLowerCase(Locale.ROOT)); }
                    catch (RuntimeException ex) { System.err.println("[HotbarSorter] Ignored invalid disabled server: " + ex.getMessage()); }
                }
            }
            String unmatched = root.has("unmatched") ? root.get("unmatched").getAsString() : "keep";
            List<List<Rule>> slots = new ArrayList<>(Collections.nCopies(9, List.of()));
            if (root.has("slots")) {
                JsonObject configured = root.getAsJsonObject("slots");
                for (int s = 1; s <= 9; s++) {
                    String key = Integer.toString(s);
                    if (!configured.has(key)) continue;
                    List<Rule> list = new ArrayList<>();
                    for (JsonElement element : configured.getAsJsonArray(key)) {
                        try {
                            JsonObject obj = element.getAsJsonObject();
                            if (obj.has("lock") && obj.get("lock").getAsBoolean()) list.add(Rule.lockedRule());
                            else list.add(new Rule(obj.get("match").getAsString(), obj.has("priority") ? obj.get("priority").getAsInt() : 0,
                                    obj.has("pick") && "best".equals(obj.get("pick").getAsString()) ? Rule.Pick.BEST : Rule.Pick.FIRST, false));
                        } catch (RuntimeException ex) { System.err.println("[HotbarSorter] Ignored invalid rule in slot " + key + ": " + ex.getMessage()); }
                    }
                    slots.set(s - 1, List.copyOf(list));
                }
            }
            rules = new RuleSet(slots, unmatched);
            presets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (root.has("presets") && root.get("presets").isJsonObject()) {
                for (var entry : root.getAsJsonObject("presets").entrySet()) {
                    try { presets.put(entry.getKey(), readRuleSet(entry.getValue().getAsJsonObject())); }
                    catch (RuntimeException ex) { System.err.println("[HotbarSorter] Ignored invalid preset " + entry.getKey()); }
                }
            }
            customGroups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (root.has("customGroups") && root.get("customGroups").isJsonObject()) {
                for (var entry : root.getAsJsonObject("customGroups").entrySet()) {
                    try {
                        List<String> items = new ArrayList<>();
                        if (entry.getValue().isJsonArray()) {
                            for (JsonElement el : entry.getValue().getAsJsonArray()) items.add(el.getAsString());
                        }
                        customGroups.put(entry.getKey(), items);
                    } catch (RuntimeException ex) { System.err.println("[HotbarSorter] Ignored invalid group " + entry.getKey()); }
                }
            }
            if (presets.isEmpty()) {
                initDefaultPresets();
            }
            activePreset = root.has("activePreset") ? root.get("activePreset").getAsString() : "";
            if ((activePreset.isEmpty() || !presets.containsKey(activePreset)) && !presets.isEmpty()) {
                activePreset = presets.keySet().iterator().next();
            }
        } catch (Exception ex) { System.err.println("[HotbarSorter] Could not load config " + path + ": " + ex.getMessage()); }
    }

    void save() {
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", 1);
            root.addProperty("enabled", enabled);
            root.addProperty("activePreset", activePreset);
            JsonObject triggers = new JsonObject();
            triggers.addProperty("onPickup", onPickup);
            triggers.addProperty("onInventoryClose", onInventoryClose);
            triggers.addProperty("onHotbarChange", onHotbarChange);
            triggers.addProperty("onInventoryChange", onInventoryChange);
            triggers.addProperty("onWorldJoin", onWorldJoin);
            triggers.addProperty("onRespawn", onRespawn);
            triggers.addProperty("intervalTicks", intervalTicks);
            triggers.addProperty("hotkeyOnly", !onHotbarChange && !onPickup && !onInventoryClose && !onWorldJoin && !onRespawn && intervalTicks == 0);
            root.add("trigger", triggers);
            root.addProperty("unmatched", rules.unmatched());
            JsonArray disabled = new JsonArray();
            disabledServers.forEach(disabled::add);
            root.add("disabledServers", disabled);
            JsonObject slots = new JsonObject();
            for (int i = 0; i < 9; i++) if (!rules.rules(i).isEmpty()) {
                JsonArray list = new JsonArray();
                for (Rule rule : rules.rules(i)) {
                    JsonObject item = new JsonObject();
                    if (rule.lock()) item.addProperty("lock", true);
                    else {
                        item.addProperty("match", rule.match());
                        item.addProperty("priority", rule.priority());
                        if (rule.pick() == Rule.Pick.BEST) item.addProperty("pick", "best");
                    }
                    list.add(item);
                }
                slots.add(Integer.toString(i + 1), list);
            }
            root.add("slots", slots);
            JsonObject savedPresets = new JsonObject();
            presets.forEach((name, preset) -> savedPresets.add(name, rulesJson(preset)));
            root.add("presets", savedPresets);
            JsonObject savedGroups = new JsonObject();
            customGroups.forEach((name, items) -> {
                JsonArray arr = new JsonArray();
                items.forEach(arr::add);
                savedGroups.add(name, arr);
            });
            root.add("customGroups", savedGroups);
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            lastModifiedMillis = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) { System.err.println("[HotbarSorter] Could not save config: " + ex.getMessage()); }
    }

    boolean isModified() {
        if (activePreset.isEmpty() || !presets.containsKey(activePreset)) return false;
        return !rules.equals(presets.get(activePreset));
    }

    void addRule(int slot, Rule rule) {
        List<List<Rule>> slots = mutableSlots();
        List<Rule> target = new ArrayList<>(slots.get(slot));
        target.removeIf(Rule::lock);
        target.add(rule);
        recalculatePriorities(target);
        slots.set(slot, target);
        rules = new RuleSet(slots, rules.unmatched());
        save();
    }

    void removeRule(int slot, int ruleIndex) {
        List<List<Rule>> slots = mutableSlots();
        List<Rule> target = new ArrayList<>(slots.get(slot));
        if (ruleIndex >= 0 && ruleIndex < target.size()) target.remove(ruleIndex);
        recalculatePriorities(target);
        slots.set(slot, target);
        rules = new RuleSet(slots, rules.unmatched());
        save();
    }

    void moveRule(int slot, int fromIndex, int toIndex) {
        List<List<Rule>> slots = mutableSlots();
        List<Rule> target = new ArrayList<>(slots.get(slot));
        if (fromIndex < 0 || fromIndex >= target.size() || toIndex < 0 || toIndex >= target.size() || fromIndex == toIndex) return;
        Rule moved = target.remove(fromIndex);
        target.add(toIndex, moved);
        recalculatePriorities(target);
        slots.set(slot, target);
        rules = new RuleSet(slots, rules.unmatched());
        save();
    }

    void togglePick(int slot, int ruleIndex) {
        List<List<Rule>> slots = mutableSlots();
        List<Rule> target = new ArrayList<>(slots.get(slot));
        if (ruleIndex >= 0 && ruleIndex < target.size()) {
            Rule r = target.get(ruleIndex);
            Rule.Pick nextPick = r.pick() == Rule.Pick.BEST ? Rule.Pick.FIRST : Rule.Pick.BEST;
            target.set(ruleIndex, new Rule(r.match(), r.priority(), nextPick, r.lock()));
            slots.set(slot, target);
            rules = new RuleSet(slots, rules.unmatched());
            save();
        }
    }

    private static void recalculatePriorities(List<Rule> target) {
        for (int i = 0; i < target.size(); i++) {
            Rule r = target.get(i);
            int newPriority = Math.max(1, (target.size() - i) * 10);
            target.set(i, new Rule(r.match(), newPriority, r.pick(), r.lock()));
        }
    }

    void toggleLock(int slot) {
        List<List<Rule>> slots = mutableSlots();
        slots.set(slot, rules.locked(slot) ? List.of() : List.of(Rule.lockedRule()));
        rules = new RuleSet(slots, rules.unmatched());
        save();
    }

    void saveCustomGroup(String name, List<String> items) {
        if (name == null || name.isBlank()) return;
        customGroups.put(name.trim(), new ArrayList<>(items));
        save();
    }

    void deleteCustomGroup(String name) {
        if (name == null) return;
        customGroups.remove(name.trim());
        save();
    }

    List<String> getCustomGroupItems(String name) {
        return customGroups.getOrDefault(name, List.of());
    }

    void savePreset(String name) {
        if (name == null || name.isBlank()) return;
        presets.put(name.trim(), rules);
        activePreset = name.trim();
        save();
    }

    void loadPreset(String name) {
        RuleSet preset = presets.get(name);
        if (preset != null) {
            rules = preset;
            activePreset = name;
            save();
        }
    }

    void deletePreset(String name) {
        presets.remove(name);
        if (activePreset.equalsIgnoreCase(name)) {
            activePreset = presets.isEmpty() ? "" : presets.keySet().iterator().next();
            if (!activePreset.isEmpty()) {
                rules = presets.get(activePreset);
            }
        }
        save();
    }

    void renamePreset(String oldName, String newName) {
        if (oldName == null || newName == null || newName.isBlank()) return;
        RuleSet set = presets.remove(oldName);
        if (set != null) {
            presets.put(newName.trim(), set);
            if (activePreset.equalsIgnoreCase(oldName)) activePreset = newName.trim();
            save();
        }
    }

    void cyclePreset(boolean forward) {
        if (presets.isEmpty()) return;
        List<String> list = new ArrayList<>(presets.keySet());
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(activePreset)) {
                idx = i;
                break;
            }
        }
        int next = forward ? (idx + 1) % list.size() : (idx - 1 + list.size()) % list.size();
        loadPreset(list.get(next));
    }

    void swapSlots(int first, int second) {
        List<List<Rule>> slots = mutableSlots();
        List<Rule> temp = slots.get(first);
        slots.set(first, slots.get(second));
        slots.set(second, temp);
        rules = new RuleSet(slots, rules.unmatched());
        save();
    }

    private void initDefaultPresets() {
        presets.put("Survival", createRuleSet(Map.of(
                0, List.of(new Rule("#minecraft:swords", 10, Rule.Pick.BEST, false)),
                1, List.of(new Rule("#minecraft:pickaxes", 10, Rule.Pick.BEST, false)),
                2, List.of(new Rule("#minecraft:axes", 10, Rule.Pick.BEST, false)),
                3, List.of(new Rule("#minecraft:shovels", 10, Rule.Pick.BEST, false)),
                4, List.of(new Rule("#minecraft:food", 10, Rule.Pick.BEST, false)),
                8, List.of(new Rule("minecraft:torch", 10, Rule.Pick.FIRST, false))
        )));
        presets.put("Mining", createRuleSet(Map.of(
                0, List.of(new Rule("#minecraft:pickaxes", 10, Rule.Pick.BEST, false)),
                1, List.of(new Rule("minecraft:torch", 10, Rule.Pick.FIRST, false)),
                2, List.of(new Rule("#minecraft:shovels", 10, Rule.Pick.BEST, false)),
                3, List.of(new Rule("#minecraft:swords", 10, Rule.Pick.BEST, false)),
                4, List.of(new Rule("minecraft:water_bucket", 10, Rule.Pick.FIRST, false)),
                8, List.of(new Rule("#minecraft:food", 10, Rule.Pick.BEST, false))
        )));
        presets.put("PvP", createRuleSet(Map.of(
                0, List.of(new Rule("#minecraft:swords", 10, Rule.Pick.BEST, false)),
                1, List.of(new Rule("minecraft:golden_apple", 10, Rule.Pick.BEST, false)),
                2, List.of(new Rule("minecraft:ender_pearl", 10, Rule.Pick.FIRST, false)),
                3, List.of(new Rule("minecraft:bow", 10, Rule.Pick.BEST, false)),
                4, List.of(new Rule("minecraft:shield", 10, Rule.Pick.BEST, false)),
                7, List.of(new Rule("minecraft:water_bucket", 10, Rule.Pick.FIRST, false)),
                8, List.of(new Rule("minecraft:totem_of_undying", 10, Rule.Pick.FIRST, false))
        )));
        presets.put("Building", createRuleSet(Map.of(
                0, List.of(new Rule("#minecraft:pickaxes", 10, Rule.Pick.BEST, false)),
                1, List.of(new Rule("#minecraft:axes", 10, Rule.Pick.BEST, false)),
                2, List.of(new Rule("#minecraft:shovels", 10, Rule.Pick.BEST, false)),
                3, List.of(new Rule("#minecraft:planks", 10, Rule.Pick.FIRST, false)),
                4, List.of(new Rule("#minecraft:wool", 10, Rule.Pick.FIRST, false)),
                5, List.of(new Rule("#minecraft:logs", 10, Rule.Pick.FIRST, false))
        )));
        if (rules.equals(RuleSet.empty())) {
            rules = presets.get("Survival");
            activePreset = "Survival";
        }
    }

    private static RuleSet createRuleSet(Map<Integer, List<Rule>> map) {
        List<List<Rule>> slots = new ArrayList<>(Collections.nCopies(9, List.of()));
        map.forEach(slots::set);
        return new RuleSet(slots, "keep");
    }

    private static JsonObject rulesJson(RuleSet set) {
        JsonObject result = new JsonObject();
        result.addProperty("unmatched", set.unmatched());
        JsonObject slots = new JsonObject();
        for (int i = 0; i < 9; i++) if (!set.rules(i).isEmpty()) {
            JsonArray list = new JsonArray();
            for (Rule rule : set.rules(i)) {
                JsonObject item = new JsonObject();
                if (rule.lock()) item.addProperty("lock", true);
                else {
                    item.addProperty("match", rule.match());
                    item.addProperty("priority", rule.priority());
                    if (rule.pick() == Rule.Pick.BEST) item.addProperty("pick", "best");
                }
                list.add(item);
            }
            slots.add(Integer.toString(i + 1), list);
        }
        result.add("slots", slots);
        return result;
    }

    private static RuleSet readRuleSet(JsonObject root) {
        String unmatched = root.has("unmatched") ? root.get("unmatched").getAsString() : "keep";
        List<List<Rule>> slots = new ArrayList<>(Collections.nCopies(9, List.of()));
        if (root.has("slots") && root.get("slots").isJsonObject()) {
            JsonObject configured = root.getAsJsonObject("slots");
            for (int s = 1; s <= 9; s++) if (configured.has(Integer.toString(s))) {
                List<Rule> list = new ArrayList<>();
                for (JsonElement element : configured.getAsJsonArray(Integer.toString(s))) {
                    JsonObject obj = element.getAsJsonObject();
                    list.add(obj.has("lock") && obj.get("lock").getAsBoolean() ? Rule.lockedRule()
                            : new Rule(obj.get("match").getAsString(), obj.has("priority") ? obj.get("priority").getAsInt() : 0,
                            obj.has("pick") && "best".equals(obj.get("pick").getAsString()) ? Rule.Pick.BEST : Rule.Pick.FIRST, false));
                }
                slots.set(s - 1, list);
            }
        }
        return new RuleSet(slots, unmatched);
    }

    private List<List<Rule>> mutableSlots() {
        return new ArrayList<>(rules.slots());
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
}
