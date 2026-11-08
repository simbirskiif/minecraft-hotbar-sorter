package dev.hotbarsorter.fabric.compat;

import dev.hotbarsorter.core.ItemView;
import dev.hotbarsorter.core.SortPlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
//? if <26.1 {
/*import net.minecraft.world.inventory.ClickType;
*///?} else
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
//? if <26.1 {
/*import net.minecraft.core.component.DataComponents;
*///?} else
import net.minecraft.core.component.DataComponents;

import java.util.*;

/** Inventory API boundary. Keep version-specific game calls in this package. */
public final class InventoryCompat {
    private InventoryCompat() {}

    public static boolean canSort(Minecraft client) {
        return client.player != null && client.gameMode != null && client.level != null
                && (ClientCompat.currentScreen(client) == null || ClientCompat.currentScreen(client) instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)
                && client.player.containerMenu.getCarried().isEmpty();
    }

    public static List<ItemView> snapshot(Minecraft client) {
        return snapshot(client, Map.of());
    }

    public static List<ItemView> snapshot(Minecraft client, Map<String, List<String>> customGroups) {
        var result = new ArrayList<ItemView>(36);
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty()) { result.add(null); continue; }
            Set<String> tags = new HashSet<>();
            //? if <26.1 {
            /*stack.getTags().forEach(tag -> tags.add(tag.location().toString()));
            int durability = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : 0;
            int count = stack.getCount();
            *///?} else {
            stack.tags().forEach(tag -> tags.add(tag.location().toString()));
            Integer maxDamage = stack.get(DataComponents.MAX_DAMAGE);
            Integer damage = stack.get(DataComponents.DAMAGE);
            int durability = maxDamage == null || damage == null ? 0 : maxDamage - damage;
            int count = stack.count();
            //?}
            if (stack.is(ItemTags.SWORDS)) tags.add("minecraft:swords");
            if (stack.is(ItemTags.PICKAXES)) tags.add("minecraft:pickaxes");
            if (stack.is(ItemTags.AXES)) tags.add("minecraft:axes");
            if (stack.is(ItemTags.SHOVELS)) tags.add("minecraft:shovels");
            if (stack.is(ItemTags.HOES)) tags.add("minecraft:hoes");
            if (stack.is(ItemTags.WOOL)) tags.add("minecraft:wool");
            if (stack.is(ItemTags.LOGS)) tags.add("minecraft:logs");
            if (stack.is(ItemTags.PLANKS)) tags.add("minecraft:planks");
            if (stack.is(ItemTags.ARROWS)) tags.add("minecraft:arrows");
            if (stack.is(ItemTags.BEDS)) tags.add("minecraft:beds");
            if (stack.get(DataComponents.FOOD) != null) {
                tags.add("minecraft:food");
                tags.add("builtin:food");
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (id.equals("minecraft:bow") || id.equals("minecraft:crossbow")) {
                tags.add("minecraft:bows");
                tags.add("builtin:bows");
            }
            if (id.contains("potion")) {
                tags.add("minecraft:potions");
                tags.add("builtin:potions");
            }
            if (id.contains("golden_apple")) tags.add("builtin:golden_apples");
            if (id.endsWith("_concrete") && !id.contains("powder")) tags.add("builtin:concrete");
            if (id.endsWith("_concrete_powder")) tags.add("builtin:concrete_powder");
            if (id.contains("terracotta")) tags.add("builtin:terracotta");
            if (id.contains("glass") && !id.contains("bottle") && !id.contains("spyglass")) tags.add("builtin:glass");
            if (id.contains("stone") || id.contains("deepslate") || id.contains("granite") || id.contains("diorite") || id.contains("andesite") || id.contains("blackstone")) tags.add("builtin:stone");
            if (id.contains("_ore") || id.startsWith("minecraft:raw_")) tags.add("builtin:ores");
            if (id.contains("log") || id.contains("plank") || id.contains("wood") || id.contains("stem") || id.contains("hyphae")) tags.add("builtin:all_wood");

            if (customGroups != null) {
                for (var entry : customGroups.entrySet()) {
                    if (entry.getValue().contains(id)) {
                        tags.add("group:" + entry.getKey());
                        tags.add("custom:" + entry.getKey());
                    }
                }
            }
            result.add(new ItemView(id, tags, count, durability, materialRank(id)));
        }
        return result;
    }

    public static void swap(Minecraft client, SortPlanner.Swap swap) {
        int menuSlot = swap.inventorySlot() < 9 ? 36 + swap.inventorySlot() : swap.inventorySlot();
        //? if <26.1 {
        /*client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, menuSlot,
                swap.hotbarSlot(), ClickType.SWAP, client.player);
        *///?} else {
        client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, menuSlot,
                swap.hotbarSlot(), ContainerInput.SWAP, client.player);
        //?}
    }

    //? if <26.1 {
    /*private static void pickup(Minecraft client, int menuSlot) {
        client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, menuSlot,
                0, ClickType.PICKUP, client.player);
    }
    *///?} else {
    private static void pickup(Minecraft client, int menuSlot) {
        client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, menuSlot,
                0, ContainerInput.PICKUP, client.player);
    }
    //?}

    public static boolean mergeOneStack(Minecraft client) {
        return mergeOneStack(client, null, Map.of());
    }

    public static boolean mergeOneStack(Minecraft client, dev.hotbarsorter.core.RuleSet rules, Map<String, List<String>> customGroups) {
        if (!canSort(client)) return false;
        var inventory = client.player.getInventory();
        //? if <1.21.11 {
        /*int selected = inventory.selected;
        *///?} else {
        int selected = inventory.getSelectedSlot();
        //?}
        List<ItemView> snapshot = snapshot(client, customGroups);

        for (int t = 0; t < 9; t++) {
            ItemStack targetStack = inventory.getItem(t);
            if (targetStack.isEmpty() || targetStack.getCount() >= targetStack.getMaxStackSize()) continue;
            ItemView targetView = snapshot.get(t);
            if (targetView == null) continue;

            int tScore = slotMergeScore(rules, t, targetView, selected);
            boolean isBestTarget = true;
            for (int other = 0; other < 9; other++) {
                if (other == t) continue;
                ItemStack otherStack = inventory.getItem(other);
                if (!otherStack.isEmpty() && otherStack.getCount() < otherStack.getMaxStackSize()
                        && ItemStack.isSameItemSameComponents(targetStack, otherStack)) {
                    ItemView otherView = snapshot.get(other);
                    int otherScore = slotMergeScore(rules, other, otherView, selected);
                    if (otherScore > tScore) {
                        isBestTarget = false;
                        break;
                    }
                }
            }
            if (!isBestTarget) continue;

            for (int s = 0; s < 36; s++) {
                if (s == t) continue;
                ItemStack sourceStack = inventory.getItem(s);
                if (!sourceStack.isEmpty() && ItemStack.isSameItemSameComponents(targetStack, sourceStack)) {
                    int sourceMenu = s < 9 ? 36 + s : s;
                    int targetMenu = 36 + t;
                    pickup(client, sourceMenu);
                    pickup(client, targetMenu);
                    if (!client.player.containerMenu.getCarried().isEmpty()) {
                        pickup(client, sourceMenu);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private static int slotMergeScore(dev.hotbarsorter.core.RuleSet rules, int slot, ItemView view, int selected) {
        int maxPriority = 0;
        if (rules != null && view != null) {
            for (dev.hotbarsorter.core.Rule r : rules.rules(slot)) {
                if (!r.lock() && view.matches(r.match())) {
                    maxPriority = Math.max(maxPriority, r.priority());
                }
            }
        }
        return maxPriority * 100 + (slot == selected ? 10 : 0) - slot;
    }

    private static int materialRank(String itemId) {
        if (itemId.contains("netherite_")) return 6;
        if (itemId.contains("diamond_")) return 5;
        if (itemId.contains("iron_")) return 4;
        if (itemId.contains("golden_") || itemId.contains("gold_")) return 3;
        if (itemId.contains("stone_")) return 2;
        if (itemId.contains("wooden_") || itemId.contains("wood_")) return 1;
        return 0;
    }
}
