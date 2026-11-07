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
            if (stack.get(DataComponents.FOOD) != null) tags.add("minecraft:food");
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (id.equals("minecraft:bow") || id.equals("minecraft:crossbow")) tags.add("minecraft:bows");
            if (id.contains("potion")) tags.add("minecraft:potions");
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

    /** Uses vanilla quick-move behavior to merge a partial stack into compatible stacks. */
    public static boolean mergeOneStack(Minecraft client) {
        var inventory = client.player.getInventory();
        for (int source = 9; source < 36; source++) {
            ItemStack stack = inventory.getItem(source);
            if (stack.isEmpty() || stack.getCount() >= stack.getMaxStackSize()) continue;
            for (int target = 0; target < 36; target++) {
                if (target == source) continue;
                ItemStack other = inventory.getItem(target);
                if (!other.isEmpty() && other.getCount() < other.getMaxStackSize()
                        && ItemStack.isSameItemSameComponents(stack, other)) {
                    int menuSlot = source;
                    //? if <26.1 {
                    /*client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, menuSlot,
                            0, ClickType.QUICK_MOVE, client.player);
                    *///?} else {
                    client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, menuSlot,
                            0, ContainerInput.QUICK_MOVE, client.player);
                    //?}
                    return true;
                }
            }
        }
        return false;
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
