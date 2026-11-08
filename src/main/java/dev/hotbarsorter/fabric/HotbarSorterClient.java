package dev.hotbarsorter.fabric;

import dev.hotbarsorter.core.*;
import dev.hotbarsorter.fabric.compat.ClientCompat;
import dev.hotbarsorter.fabric.compat.GuiCompat;
import dev.hotbarsorter.fabric.compat.InventoryCompat;
import dev.hotbarsorter.fabric.compat.KeybindCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

public final class HotbarSorterClient implements ClientModInitializer {
    private static final ConfigStore CONFIG = new ConfigStore();
    private static KeyMapping sortKey;
    private static KeyMapping settingsKey;
    private final SortPlanner planner = new SortPlanner();
    private boolean sortingRequested;
    private int clicksRemaining;
    private Screen previousScreen;
    private net.minecraft.client.player.LocalPlayer previousPlayer;
    private net.minecraft.client.multiplayer.ClientLevel previousLevel;
    private Map<String, Integer> previousItemCounts = Map.of();
    private boolean hasItemSnapshot;
    private List<String> previousSlotFingerprint = List.of();
    private List<String> previousHotbarFingerprint = List.of();
    private Map<String, Integer> screenOpenCounts = Map.of();
    private List<String> screenOpenFingerprint = List.of();
    private int screenCloseGraceTicks;
    private int intervalElapsed;
    private int manualMoveCooldown;

    @Override public void onInitializeClient() {
        CONFIG.load();
        sortKey = KeybindCompat.register("key.hotbarsorter.sort", false);
        settingsKey = KeybindCompat.register("key.hotbarsorter.settings", true);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (settingsKey.consumeClick()) ClientCompat.setScreen(client, new SettingsScreen());
            CONFIG.reloadIfChanged();
            Screen screen = ClientCompat.currentScreen(client);
            if (sortKey.consumeClick()) requestSort();
            if (client.level != null && client.player != null) {
                List<ItemView> items = InventoryCompat.snapshot(client, CONFIG.customGroups);
                Map<String, Integer> counts = itemCounts(items);
                List<String> fingerprint = fingerprint(items);
                List<String> hotbarFingerprint = fingerprint(items.subList(0, 9));

                if (previousScreen == null && screen != null) {
                    screenOpenCounts = counts;
                    screenOpenFingerprint = fingerprint;
                }

                boolean screenJustClosed = previousScreen != null && screen == null;
                if (screenJustClosed) {
                    screenCloseGraceTicks = 25;
                }

                boolean inventoryChangedInScreen = screenCloseGraceTicks > 0
                        && (!screenOpenCounts.equals(counts) || !screenOpenFingerprint.equals(fingerprint));

                boolean manualRearrangement = hasItemSnapshot && !sortingRequested && screen == null
                        && previousItemCounts.equals(counts) && !previousSlotFingerprint.equals(fingerprint);
                if (manualRearrangement) manualMoveCooldown = 20;
                else if (manualMoveCooldown > 0) manualMoveCooldown--;

                if (screenCloseGraceTicks > 0) screenCloseGraceTicks--;

                if (CONFIG.onHotbarChange && hasItemSnapshot && !previousHotbarFingerprint.equals(hotbarFingerprint)) requestSort();
                if (manualMoveCooldown == 0) {
                    if (CONFIG.onInventoryChange && hasItemSnapshot && !previousItemCounts.equals(counts)) requestSort();
                    if (CONFIG.onPickup && hasItemSnapshot && !previousItemCounts.equals(counts)) requestSort();
                    if ((CONFIG.onInventoryClose && screenJustClosed) || inventoryChangedInScreen) requestSort();
                    if ((previousLevel == null && CONFIG.onWorldJoin)
                            || (previousPlayer != null && previousPlayer != client.player && CONFIG.onRespawn)) requestSort();
                }
                if (CONFIG.intervalTicks > 0 && ++intervalElapsed >= CONFIG.intervalTicks) {
                    intervalElapsed = 0;
                    if (manualMoveCooldown == 0) requestSort();
                }
                if (screen == null) {
                    previousItemCounts = counts;
                    previousSlotFingerprint = fingerprint;
                }
                previousHotbarFingerprint = hotbarFingerprint;
                hasItemSnapshot = true;
            } else {
                previousItemCounts = Map.of();
                previousSlotFingerprint = List.of();
                previousHotbarFingerprint = List.of();
                screenOpenCounts = Map.of();
                screenOpenFingerprint = List.of();
                screenCloseGraceTicks = 0;
                hasItemSnapshot = false;
                intervalElapsed = 0;
                manualMoveCooldown = 0;
            }
            previousScreen = screen;
            previousPlayer = client.player;
            previousLevel = client.level;
            executeNext(client);
        });
    }

    private void requestSort() {
        if (sortingRequested) return;
        sortingRequested = true;
        clicksRemaining = 18;
    }

    private static Map<String, Integer> itemCounts(List<ItemView> items) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemView item : items) if (item != null) counts.merge(item.id(), item.count(), Integer::sum);
        return counts;
    }

    private static List<String> fingerprint(List<ItemView> items) {
        return items.stream().map(item -> item == null ? "" : item.id() + "@" + item.count() + ":" + item.durabilityRemaining()).toList();
    }

    private void executeNext(Minecraft client) {
        if (!sortingRequested) return;
        String server = ClientCompat.serverAddress(client);
        if (!CONFIG.enabled || CONFIG.disabledServers.contains(server) || clicksRemaining <= 0) {
            sortingRequested = false;
            return;
        }
        if (!InventoryCompat.canSort(client)) {
            return;
        }
        clicksRemaining--;
        if (InventoryCompat.mergeOneStack(client, CONFIG.rules, CONFIG.customGroups)) return;
        List<SortPlanner.Swap> plan = planner.plan(InventoryCompat.snapshot(client, CONFIG.customGroups), CONFIG.rules);
        if (plan.isEmpty()) { sortingRequested = false; return; }
        InventoryCompat.swap(client, plan.get(0));
    }

    private static ItemStack findItem(String id) {
        if (id == null || id.isEmpty()) return ItemStack.EMPTY;
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(item).toString().equals(id)) return new ItemStack(item);
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack slotIcon(RuleSet ruleSet, int slot) {
        if (ruleSet == null) return ItemStack.EMPTY;
        for (Rule rule : ruleSet.rules(slot)) {
            if (rule.lock()) continue;
            ItemStack stack = resolveIcon(rule.match());
            if (!stack.isEmpty()) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack resolveIcon(String match) {
        if (match == null || match.isBlank()) return ItemStack.EMPTY;
        if (!match.startsWith("#")) return findItem(match);
        String tag = match.substring(1).toLowerCase(Locale.ROOT);
        if (tag.startsWith("group:") || tag.startsWith("custom:")) {
            String groupName = tag.substring(tag.indexOf(':') + 1);
            List<String> items = CONFIG.getCustomGroupItems(groupName);
            if (!items.isEmpty()) return findItem(items.get(0));
            return findItem("minecraft:chest");
        }
        if (tag.contains("sword")) return findItem("minecraft:iron_sword");
        if (tag.contains("pickaxe")) return findItem("minecraft:iron_pickaxe");
        if (tag.contains("axe")) return findItem("minecraft:iron_axe");
        if (tag.contains("shovel")) return findItem("minecraft:iron_shovel");
        if (tag.contains("hoe")) return findItem("minecraft:iron_hoe");
        if (tag.contains("bow")) return findItem("minecraft:bow");
        if (tag.contains("food") || tag.contains("meat")) return findItem("minecraft:cooked_beef");
        if (tag.contains("apple")) return findItem("minecraft:golden_apple");
        if (tag.contains("wool")) return findItem("minecraft:white_wool");
        if (tag.contains("concrete_powder")) return findItem("minecraft:white_concrete_powder");
        if (tag.contains("concrete")) return findItem("minecraft:white_concrete");
        if (tag.contains("terracotta")) return findItem("minecraft:terracotta");
        if (tag.contains("glass")) return findItem("minecraft:glass");
        if (tag.contains("stone")) return findItem("minecraft:cobblestone");
        if (tag.contains("ores")) return findItem("minecraft:raw_iron");
        if (tag.contains("all_wood")) return findItem("minecraft:oak_log");
        if (tag.contains("log")) return findItem("minecraft:oak_log");
        if (tag.contains("plank")) return findItem("minecraft:oak_planks");
        if (tag.contains("bed")) return findItem("minecraft:red_bed");
        if (tag.contains("arrow")) return findItem("minecraft:arrow");
        if (tag.contains("potion")) return findItem("minecraft:potion");
        if (tag.contains("bucket")) return findItem("minecraft:water_bucket");
        if (tag.contains("shield")) return findItem("minecraft:shield");
        if (tag.contains("totem")) return findItem("minecraft:totem_of_undying");
        if (tag.contains("pearl")) return findItem("minecraft:ender_pearl");
        if (tag.contains("torch")) return findItem("minecraft:torch");
        return ItemStack.EMPTY;
    }

    private final class SettingsScreen extends Screen {
        private int dragSlot = -1;

        SettingsScreen() { super(Component.translatable("screen.hotbarsorter.title")); }

        @Override protected void init() {
            int cx = width / 2;
            int cy = height / 2;

            // Preset switcher bar
            addRenderableWidget(Button.builder(Component.literal("‹"), b -> {
                CONFIG.cyclePreset(false);
                rebuildWidgets();
            }).bounds(cx - 150, cy - 62, 22, 20).build());

            String presetText = CONFIG.activePreset.isEmpty() ? "Default" : CONFIG.activePreset;
            if (CONFIG.isModified()) presetText += " *";
            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.preset", presetText), b ->
                    ClientCompat.setScreen(minecraft, new PresetManagerScreen())).bounds(cx - 124, cy - 62, 172, 20).build());

            addRenderableWidget(Button.builder(Component.literal("›"), b -> {
                CONFIG.cyclePreset(true);
                rebuildWidgets();
            }).bounds(cx + 52, cy - 62, 22, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.presets_btn"), b ->
                    ClientCompat.setScreen(minecraft, new PresetManagerScreen())).bounds(cx + 78, cy - 62, 72, 20).build());

            // Bottom controls
            addRenderableWidget(Button.builder(enabledLabel(), b -> {
                CONFIG.enabled = !CONFIG.enabled;
                CONFIG.save();
                b.setMessage(enabledLabel());
            }).bounds(cx - 150, cy + 34, 145, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.sort_now"), b ->
                    requestSort()).bounds(cx + 5, cy + 34, 145, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                    .bounds(cx - 100, cy + 60, 200, 20).build());
        }

        private int slotStart() { return width / 2 - 196; }
        private int slotY() { return height / 2 - 20; }

        private int slotAt(double mouseX, double mouseY) {
            int y = slotY();
            if (mouseY < y || mouseY >= y + 40) return -1;
            int rel = (int) mouseX - slotStart();
            if (rel < 0 || rel >= 392) return -1;
            int index = rel / 44;
            return (rel % 44 < 40 && index >= 0 && index < 9) ? index : -1;
        }

        //? if <1.21.9 {
        /*@Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (handleSlotClick(mouseX, mouseY, button)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
            return handleSlotRelease(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
        }*///?} else {
        @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            if (handleSlotClick(event.x(), event.y(), event.button())) return true;
            return super.mouseClicked(event, doubleClick);
        }
        @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
            return handleSlotRelease(event.x(), event.y(), event.button()) || super.mouseReleased(event);
        }
        //?}

        private boolean handleSlotClick(double mouseX, double mouseY, int button) {
            int slot = slotAt(mouseX, mouseY);
            if (slot >= 0 && button == 0) { dragSlot = slot; return true; }
            return false;
        }

        private boolean handleSlotRelease(double mouseX, double mouseY, int button) {
            if (button == 0 && dragSlot >= 0) {
                int source = dragSlot;
                dragSlot = -1;
                int target = slotAt(mouseX, mouseY);
                if (target >= 0 && target != source) {
                    CONFIG.swapSlots(source, target);
                    rebuildWidgets();
                } else if (target == source) {
                    ClientCompat.setScreen(minecraft, new RuleEditorScreen(source));
                }
                return true;
            }
            return false;
        }

        private Component enabledLabel() {
            return Component.translatable(CONFIG.enabled ? "screen.hotbarsorter.enabled" : "screen.hotbarsorter.disabled");
        }

        //? if <26.1 {
        /*@Override public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            super.render(graphics, mouseX, mouseY, delta);
            drawSlotsContent(graphics, mouseX, mouseY);
        }*///?} else {
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            drawSlotsContent(graphics, mouseX, mouseY);
        }
        //?}

        //? if <26.1 {
        /*private void drawSlotsContent(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY) {
            int cx = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, cx, height / 2 - 82, 0xFFFFFFFF);
            GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.slots"), cx, height / 2 - 34, 0xFFA0A0A0);

            int startX = slotStart();
            int sy = slotY();

            for (int i = 0; i < 9; i++) {
                int x = startX + i * 44;
                boolean hovered = (mouseX >= x && mouseX < x + 40 && mouseY >= sy && mouseY < sy + 40);
                boolean locked = CONFIG.rules.locked(i);

                int bg = locked ? 0x90281414 : (hovered ? 0x90383838 : (dragSlot == i ? 0x90443300 : 0x90151515));
                GuiCompat.fill(graphics, x, sy, x + 40, sy + 40, bg);

                int border;
                if (dragSlot >= 0 && hovered && dragSlot != i) border = 0xFF55FF55;
                else if (dragSlot == i) border = 0xFFFFAA00;
                else if (hovered) border = 0xFFFFFFFF;
                else if (locked) border = 0xFFCC3333;
                else border = 0xFF4A4A4A;
                GuiCompat.outline(graphics, x, sy, 40, 40, border);

                if (locked) {
                    GuiCompat.drawText(graphics, font, Component.literal("🔒"), x + 3, sy + 3, 0xFFFF4444);
                } else if (CONFIG.rules.rules(i).size() > 1) {
                    GuiCompat.drawText(graphics, font, Component.literal("+" + (CONFIG.rules.rules(i).size() - 1)), x + 25, sy + 3, 0xFFFFAA00);
                }

                ItemStack stack = slotIcon(CONFIG.rules, i);
                if (!stack.isEmpty()) GuiCompat.drawItem(graphics, stack, x + 12, sy + 8);

                GuiCompat.drawCenteredText(graphics, font, Component.literal(Integer.toString(i + 1)), x + 20, sy + 28, 0xFFB0B0B0);
            }

            if (dragSlot >= 0) {
                ItemStack dragStack = slotIcon(CONFIG.rules, dragSlot);
                if (!dragStack.isEmpty()) GuiCompat.drawItem(graphics, dragStack, mouseX - 8, mouseY - 8);
            } else {
                int hoveredSlot = slotAt(mouseX, mouseY);
                if (hoveredSlot >= 0) {
                    List<Component> tip = new ArrayList<>();
                    tip.add(Component.translatable("screen.hotbarsorter.slot_tooltip_title", hoveredSlot + 1).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                    if (CONFIG.rules.locked(hoveredSlot)) {
                        tip.add(Component.translatable("screen.hotbarsorter.slot_locked").withStyle(ChatFormatting.RED));
                    } else {
                        List<Rule> rules = CONFIG.rules.rules(hoveredSlot);
                        if (rules.isEmpty()) {
                            tip.add(Component.translatable("screen.hotbarsorter.slot_empty").withStyle(ChatFormatting.GRAY));
                        } else {
                            for (Rule r : rules) {
                                String pick = r.pick() == Rule.Pick.BEST ? " [best]" : "";
                                tip.add(Component.literal("• " + r.match() + " (prio " + r.priority() + pick + ")").withStyle(ChatFormatting.WHITE));
                            }
                        }
                    }
                    tip.add(Component.translatable("screen.hotbarsorter.slot_hint").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
                    GuiCompat.drawTooltip(graphics, font, tip, mouseX, mouseY);
                }
            }
        }*///?} else {
        private void drawSlotsContent(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            int cx = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, cx, height / 2 - 82, 0xFFFFFFFF);
            GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.slots"), cx, height / 2 - 34, 0xFFA0A0A0);

            int startX = slotStart();
            int sy = slotY();

            for (int i = 0; i < 9; i++) {
                int x = startX + i * 44;
                boolean hovered = (mouseX >= x && mouseX < x + 40 && mouseY >= sy && mouseY < sy + 40);
                boolean locked = CONFIG.rules.locked(i);

                int bg = locked ? 0x90281414 : (hovered ? 0x90383838 : (dragSlot == i ? 0x90443300 : 0x90151515));
                GuiCompat.fill(graphics, x, sy, x + 40, sy + 40, bg);

                int border;
                if (dragSlot >= 0 && hovered && dragSlot != i) border = 0xFF55FF55;
                else if (dragSlot == i) border = 0xFFFFAA00;
                else if (hovered) border = 0xFFFFFFFF;
                else if (locked) border = 0xFFCC3333;
                else border = 0xFF4A4A4A;
                GuiCompat.outline(graphics, x, sy, 40, 40, border);

                if (locked) {
                    GuiCompat.drawText(graphics, font, Component.literal("🔒"), x + 3, sy + 3, 0xFFFF4444);
                } else if (CONFIG.rules.rules(i).size() > 1) {
                    GuiCompat.drawText(graphics, font, Component.literal("+" + (CONFIG.rules.rules(i).size() - 1)), x + 25, sy + 3, 0xFFFFAA00);
                }

                ItemStack stack = slotIcon(CONFIG.rules, i);
                if (!stack.isEmpty()) GuiCompat.drawItem(graphics, stack, x + 12, sy + 8);

                GuiCompat.drawCenteredText(graphics, font, Component.literal(Integer.toString(i + 1)), x + 20, sy + 28, 0xFFB0B0B0);
            }

            if (dragSlot >= 0) {
                ItemStack dragStack = slotIcon(CONFIG.rules, dragSlot);
                if (!dragStack.isEmpty()) GuiCompat.drawItem(graphics, dragStack, mouseX - 8, mouseY - 8);
            } else {
                int hoveredSlot = slotAt(mouseX, mouseY);
                if (hoveredSlot >= 0) {
                    List<Component> tip = new ArrayList<>();
                    tip.add(Component.translatable("screen.hotbarsorter.slot_tooltip_title", hoveredSlot + 1).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                    if (CONFIG.rules.locked(hoveredSlot)) {
                        tip.add(Component.translatable("screen.hotbarsorter.slot_locked").withStyle(ChatFormatting.RED));
                    } else {
                        List<Rule> rules = CONFIG.rules.rules(hoveredSlot);
                        if (rules.isEmpty()) {
                            tip.add(Component.translatable("screen.hotbarsorter.slot_empty").withStyle(ChatFormatting.GRAY));
                        } else {
                            for (Rule r : rules) {
                                String pick = r.pick() == Rule.Pick.BEST ? " [best]" : "";
                                tip.add(Component.literal("• " + r.match() + " (prio " + r.priority() + pick + ")").withStyle(ChatFormatting.WHITE));
                            }
                        }
                    }
                    tip.add(Component.translatable("screen.hotbarsorter.slot_hint").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
                    GuiCompat.drawTooltip(graphics, font, tip, mouseX, mouseY);
                }
            }
        }
        //?}
    }

    private final class PresetManagerScreen extends Screen {
        private int page;
        private static final int PAGE_SIZE = 4;

        PresetManagerScreen() { super(Component.translatable("screen.hotbarsorter.preset_manager")); }

        @Override protected void init() {
            int cx = width / 2;
            List<String> list = new ArrayList<>(CONFIG.presets.keySet());
            int totalPages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page >= totalPages) page = totalPages - 1;

            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, list.size());
            int cardW = 194;
            int cardH = 70;

            for (int i = start; i < end; i++) {
                String presetName = list.get(i);
                int rel = i - start;
                int col = rel % 2;
                int row = rel / 2;
                int cardX = col == 0 ? cx - cardW - 6 : cx + 6;
                int cardY = 40 + row * (cardH + 8);

                addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                    CONFIG.deletePreset(presetName);
                    rebuildWidgets();
                }).bounds(cardX + cardW - 20, cardY + 3, 16, 16).build());

                addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.preset_load"), b -> {
                    CONFIG.loadPreset(presetName);
                    ClientCompat.setScreen(minecraft, new SettingsScreen());
                }).bounds(cardX + 11, cardY + 44, 82, 18).build());

                addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.preset_overwrite"), b -> {
                    CONFIG.savePreset(presetName);
                    rebuildWidgets();
                }).bounds(cardX + 101, cardY + 44, 82, 18).build());
            }

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.new_preset"), b ->
                    ClientCompat.setScreen(minecraft, new PresetNameScreen())).bounds(cx - 170, height - 32, 105, 20).build());

            if (page > 0) {
                addRenderableWidget(Button.builder(Component.literal("‹"), b -> {
                    page--;
                    rebuildWidgets();
                }).bounds(cx - 40, height - 32, 24, 20).build());
            }
            if (end < list.size()) {
                addRenderableWidget(Button.builder(Component.literal("›"), b -> {
                    page++;
                    rebuildWidgets();
                }).bounds(cx + 16, height - 32, 24, 20).build());
            }

            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b ->
                    ClientCompat.setScreen(minecraft, new SettingsScreen())).bounds(cx + 65, height - 32, 105, 20).build());
        }

        //? if <26.1 {
        /*@Override public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            super.render(graphics, mouseX, mouseY, delta);
            renderPresetsGrid(graphics);
        }*///?} else {
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            renderPresetsGrid(graphics);
        }
        //?}

        //? if <26.1 {
        /*private void renderPresetsGrid(net.minecraft.client.gui.GuiGraphics graphics) {
            int cx = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, cx, 16, 0xFFFFFFFF);
            List<String> list = new ArrayList<>(CONFIG.presets.keySet());
            int totalPages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.page_info", page + 1, totalPages), cx - 6, height - 26, 0xFFA0A0A0);

            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, list.size());
            int cardW = 194;
            int cardH = 70;

            for (int i = start; i < end; i++) {
                String name = list.get(i);
                int rel = i - start;
                int col = rel % 2;
                int row = rel / 2;
                int cardX = col == 0 ? cx - cardW - 6 : cx + 6;
                int cardY = 40 + row * (cardH + 8);
                boolean isActive = name.equalsIgnoreCase(CONFIG.activePreset);

                GuiCompat.fill(graphics, cardX, cardY, cardX + cardW, cardY + cardH, isActive ? 0x90282410 : 0x90151515);
                GuiCompat.outline(graphics, cardX, cardY, cardW, cardH, isActive ? 0xFFFFAA00 : 0xFF444444);

                Component nameComp = Component.literal((isActive ? "★ " : "") + name)
                        .withStyle(isActive ? ChatFormatting.GOLD : ChatFormatting.WHITE, ChatFormatting.BOLD);
                GuiCompat.drawText(graphics, font, nameComp, cardX + 6, cardY + 6, 0xFFFFFFFF);

                RuleSet set = CONFIG.presets.get(name);
                int startSlotX = cardX + 11;
                int slotY = cardY + 22;
                for (int s = 0; s < 9; s++) {
                    int slotX = startSlotX + s * 19;
                    GuiCompat.fill(graphics, slotX, slotY, slotX + 18, slotY + 18, 0x70000000);
                    GuiCompat.outline(graphics, slotX, slotY, 18, 18, 0xFF353535);
                    if (set != null) {
                        if (set.locked(s)) {
                            GuiCompat.drawText(graphics, font, Component.literal("🔒"), slotX + 3, slotY + 3, 0xFFFF4444);
                        } else {
                            ItemStack icon = slotIcon(set, s);
                            if (!icon.isEmpty()) GuiCompat.drawItem(graphics, icon, slotX + 1, slotY + 1);
                        }
                    }
                }
            }
        }*///?} else {
        private void renderPresetsGrid(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
            int cx = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, cx, 16, 0xFFFFFFFF);
            List<String> list = new ArrayList<>(CONFIG.presets.keySet());
            int totalPages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.page_info", page + 1, totalPages), cx - 6, height - 26, 0xFFA0A0A0);

            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, list.size());
            int cardW = 194;
            int cardH = 70;

            for (int i = start; i < end; i++) {
                String name = list.get(i);
                int rel = i - start;
                int col = rel % 2;
                int row = rel / 2;
                int cardX = col == 0 ? cx - cardW - 6 : cx + 6;
                int cardY = 40 + row * (cardH + 8);
                boolean isActive = name.equalsIgnoreCase(CONFIG.activePreset);

                GuiCompat.fill(graphics, cardX, cardY, cardX + cardW, cardY + cardH, isActive ? 0x90282410 : 0x90151515);
                GuiCompat.outline(graphics, cardX, cardY, cardW, cardH, isActive ? 0xFFFFAA00 : 0xFF444444);

                Component nameComp = Component.literal((isActive ? "★ " : "") + name)
                        .withStyle(isActive ? ChatFormatting.GOLD : ChatFormatting.WHITE, ChatFormatting.BOLD);
                GuiCompat.drawText(graphics, font, nameComp, cardX + 6, cardY + 6, 0xFFFFFFFF);

                RuleSet set = CONFIG.presets.get(name);
                int startSlotX = cardX + 11;
                int slotY = cardY + 22;
                for (int s = 0; s < 9; s++) {
                    int slotX = startSlotX + s * 19;
                    GuiCompat.fill(graphics, slotX, slotY, slotX + 18, slotY + 18, 0x70000000);
                    GuiCompat.outline(graphics, slotX, slotY, 18, 18, 0xFF353535);
                    if (set != null) {
                        if (set.locked(s)) {
                            GuiCompat.drawText(graphics, font, Component.literal("🔒"), slotX + 3, slotY + 3, 0xFFFF4444);
                        } else {
                            ItemStack icon = slotIcon(set, s);
                            if (!icon.isEmpty()) GuiCompat.drawItem(graphics, icon, slotX + 1, slotY + 1);
                        }
                    }
                }
            }
        }
        //?}
    }

    private final class PresetNameScreen extends Screen {
        private EditBox name;
        PresetNameScreen() { super(Component.translatable("screen.hotbarsorter.save_preset")); }

        @Override protected void init() {
            int cx = width / 2;
            name = addRenderableWidget(new EditBox(font, cx - 100, height / 2 - 15, 200, 20,
                    Component.translatable("screen.hotbarsorter.preset_name")));
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
                String value = name.getValue().trim();
                if (!value.isEmpty()) {
                    CONFIG.savePreset(value);
                    ClientCompat.setScreen(minecraft, new PresetManagerScreen());
                }
            }).bounds(cx - 100, height / 2 + 14, 200, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b ->
                    ClientCompat.setScreen(minecraft, new PresetManagerScreen())).bounds(cx - 100, height / 2 + 38, 200, 20).build());
        }

        //? if <26.1 {
        /*@Override public void render(net.minecraft.client.gui.GuiGraphics g, int x, int y, float d) {
            super.render(g, x, y, d);
            GuiCompat.drawCenteredText(g, font, title, width / 2, height / 2 - 42, 0xFFFFFFFF);
        }*///?} else {
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float d) {
            super.extractRenderState(g, x, y, d);
            GuiCompat.drawCenteredText(g, font, title, width / 2, height / 2 - 42, 0xFFFFFFFF);
        }
        //?}
    }

    private final class RuleEditorScreen extends Screen {
        private final int slot;
        private EditBox matcher;
        private boolean best;
        private final String initialMatch;
        private final List<ItemStack> inventoryPalette = new ArrayList<>();
        private int dragRule = -1;

        RuleEditorScreen(int slot) { this(slot, "", true); }

        RuleEditorScreen(int slot, String matchValue, boolean bestValue) {
            super(Component.translatable("screen.hotbarsorter.rule_title", slot + 1));
            this.slot = slot;
            this.initialMatch = matchValue;
            this.best = bestValue;
        }

        RuleEditorScreen(int slot, String matchValue, String priorityValue, boolean bestValue) {
            this(slot, matchValue, bestValue);
        }

        @Override protected void init() {
            int center = width / 2;

            matcher = addRenderableWidget(new EditBox(font, center - 150, 26, 300, 18,
                    Component.translatable("screen.hotbarsorter.match")));
            matcher.setValue(initialMatch);

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.choose_item"), b ->
                    ClientCompat.setScreen(minecraft, new ItemPickerScreen(slot, matcher.getValue(), "10", best, "", 0)))
                    .bounds(center - 150, 46, 146, 18).build());

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.choose_group"), b ->
                    ClientCompat.setScreen(minecraft, new GroupPickerScreen(slot, matcher.getValue(), "10", best)))
                    .bounds(center + 4, 46, 146, 18).build());

            addRenderableWidget(Button.builder(lockLabel(), b -> {
                CONFIG.toggleLock(slot);
                ClientCompat.setScreen(minecraft, new RuleEditorScreen(slot));
            }).bounds(center - 150, 66, 146, 18).build());

            addRenderableWidget(Button.builder(pickLabel(), b -> {
                best = !best;
                b.setMessage(pickLabel());
            }).bounds(center + 4, 66, 146, 18).build());

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.add_rule"), b -> addRule())
                    .bounds(center - 150, 86, 300, 18).build());

            List<Rule> current = CONFIG.rules.rules(slot);
            for (int i = 0; i < current.size(); i++) {
                Rule rule = current.get(i);
                if (rule.lock()) continue;
                int index = i;
                int rowY = 118 + i * 22;

                Component label = Component.literal("#" + (i + 1) + "  " + rule.match());
                addRenderableWidget(Button.builder(label, b -> {
                    matcher.setValue(rule.match());
                    best = rule.pick() == Rule.Pick.BEST;
                    rebuildWidgets();
                }).bounds(center - 126, rowY, 154, 20).build());

                Component modeLabel = Component.literal(rule.pick() == Rule.Pick.BEST ? "Best" : "First");
                addRenderableWidget(Button.builder(modeLabel, b -> {
                    CONFIG.togglePick(slot, index);
                    rebuildWidgets();
                }).bounds(center + 30, rowY, 40, 20).build());

                Button upBtn = Button.builder(Component.literal("▲"), b -> {
                    if (index > 0) {
                        CONFIG.moveRule(slot, index, index - 1);
                        rebuildWidgets();
                    }
                }).bounds(center + 72, rowY, 18, 20).build();
                if (index == 0) upBtn.active = false;
                addRenderableWidget(upBtn);

                Button downBtn = Button.builder(Component.literal("▼"), b -> {
                    if (index < current.size() - 1) {
                        CONFIG.moveRule(slot, index, index + 1);
                        rebuildWidgets();
                    }
                }).bounds(center + 92, rowY, 18, 20).build();
                if (index >= current.size() - 1) downBtn.active = false;
                addRenderableWidget(downBtn);

                addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                    CONFIG.removeRule(slot, index);
                    rebuildWidgets();
                }).bounds(center + 112, rowY, 20, 20).build());
            }

            // Quick select palette from player inventory
            inventoryPalette.clear();
            if (minecraft != null && minecraft.player != null) {
                Set<String> seenIds = new HashSet<>();
                var inv = minecraft.player.getInventory();
                for (int s = 0; s < 36; s++) {
                    ItemStack st = inv.getItem(s);
                    if (!st.isEmpty()) {
                        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                        if (seenIds.add(id)) inventoryPalette.add(st);
                    }
                }
            }

            int paletteStartX = center - Math.min(14, inventoryPalette.size()) * 11;
            int paletteY = height - 50;
            for (int i = 0; i < Math.min(14, inventoryPalette.size()); i++) {
                ItemStack st = inventoryPalette.get(i);
                String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                int px = paletteStartX + i * 22;
                addRenderableWidget(Button.builder(Component.empty(), b -> {
                    matcher.setValue(id);
                }).bounds(px, paletteY, 20, 20).build());
            }

            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b ->
                    ClientCompat.setScreen(minecraft, new SettingsScreen())).bounds(center - 100, height - 26, 200, 20).build());
        }

        //? if <1.21.9 {
        /*@Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (handleRuleClick(mouseX, mouseY, button)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
            return handleRuleRelease(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
        }*///?} else {
        @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            if (handleRuleClick(event.x(), event.y(), event.button())) return true;
            return super.mouseClicked(event, doubleClick);
        }
        @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
            return handleRuleRelease(event.x(), event.y(), event.button()) || super.mouseReleased(event);
        }
        //?}

        private boolean handleRuleClick(double mouseX, double mouseY, int button) {
            if (button == 0) {
                int center = width / 2;
                List<Rule> current = CONFIG.rules.rules(slot);
                for (int i = 0; i < current.size(); i++) {
                    int rowY = 118 + i * 22;
                    if (mouseX >= center - 150 && mouseX < center - 126 && mouseY >= rowY && mouseY < rowY + 20) {
                        dragRule = i;
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean handleRuleRelease(double mouseX, double mouseY, int button) {
            if (button == 0 && dragRule >= 0) {
                int source = dragRule;
                dragRule = -1;
                List<Rule> current = CONFIG.rules.rules(slot);
                int target = (int) (mouseY - 118) / 22;
                if (target >= 0 && target < current.size() && target != source) {
                    CONFIG.moveRule(slot, source, target);
                    rebuildWidgets();
                }
                return true;
            }
            return false;
        }

        private void addRule() {
            String match = matcher.getValue().trim();
            if (match.isEmpty() || match.equals("#")) {
                ClientCompat.showSystemMessage(minecraft, Component.translatable("screen.hotbarsorter.invalid_rule"));
                return;
            }
            CONFIG.addRule(slot, new Rule(match, 10, best ? Rule.Pick.BEST : Rule.Pick.FIRST, false));
            matcher.setValue("");
            rebuildWidgets();
        }

        private Component lockLabel() {
            return Component.translatable(CONFIG.rules.locked(slot) ? "screen.hotbarsorter.unlock_slot" : "screen.hotbarsorter.lock_slot");
        }
        private Component pickLabel() {
            return Component.translatable(best ? "screen.hotbarsorter.pick_best" : "screen.hotbarsorter.pick_first");
        }

        //? if <26.1 {
        /*@Override public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            super.render(graphics, mouseX, mouseY, delta);
            renderEditorContent(graphics, mouseX, mouseY);
        }*///?} else {
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            renderEditorContent(graphics, mouseX, mouseY);
        }
        //?}

        //? if <26.1 {
        /*private void renderEditorContent(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY) {
            int center = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, center, 12, 0xFFFFFFFF);

            List<Rule> current = CONFIG.rules.rules(slot);
            if (current.size() > 1) {
                GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.priority_drag_hint"), center, 106, 0xFFA0A0A0);
            }

            for (int i = 0; i < current.size(); i++) {
                Rule rule = current.get(i);
                if (rule.lock()) continue;
                int rowY = 118 + i * 22;

                boolean hoveredDrag = mouseX >= center - 150 && mouseX < center - 126 && mouseY >= rowY && mouseY < rowY + 20;
                GuiCompat.fill(graphics, center - 150, rowY, center - 128, rowY + 20, hoveredDrag ? 0x90404040 : 0x60202020);
                GuiCompat.outline(graphics, center - 150, rowY, 22, 20, dragRule == i ? 0xFFFFAA00 : 0xFF444444);

                ItemStack icon = resolveIcon(rule.match());
                if (!icon.isEmpty()) GuiCompat.drawItem(graphics, icon, center - 147, rowY + 2);
            }

            if (dragRule >= 0 && dragRule < current.size()) {
                int target = (int) (mouseY - 118) / 22;
                target = Math.max(0, Math.min(current.size() - 1, target));
                int lineY = 118 + target * 22;
                GuiCompat.outline(graphics, center - 150, lineY, 282, 20, 0xFF55FF55);
                ItemStack dragIcon = resolveIcon(current.get(dragRule).match());
                if (!dragIcon.isEmpty()) GuiCompat.drawItem(graphics, dragIcon, mouseX - 8, mouseY - 8);
            }

            if (!inventoryPalette.isEmpty()) {
                GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.quick_palette"), center, height - 62, 0xFFA0A0A0);
                int paletteStartX = center - Math.min(14, inventoryPalette.size()) * 11;
                int paletteY = height - 50;
                for (int i = 0; i < Math.min(14, inventoryPalette.size()); i++) {
                    GuiCompat.drawItem(graphics, inventoryPalette.get(i), paletteStartX + i * 22 + 2, paletteY + 2);
                }
            }
        }*///?} else {
        private void renderEditorContent(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            int center = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, center, 12, 0xFFFFFFFF);

            List<Rule> current = CONFIG.rules.rules(slot);
            if (current.size() > 1) {
                GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.priority_drag_hint"), center, 106, 0xFFA0A0A0);
            }

            for (int i = 0; i < current.size(); i++) {
                Rule rule = current.get(i);
                if (rule.lock()) continue;
                int rowY = 118 + i * 22;

                boolean hoveredDrag = mouseX >= center - 150 && mouseX < center - 126 && mouseY >= rowY && mouseY < rowY + 20;
                GuiCompat.fill(graphics, center - 150, rowY, center - 128, rowY + 20, hoveredDrag ? 0x90404040 : 0x60202020);
                GuiCompat.outline(graphics, center - 150, rowY, 22, 20, dragRule == i ? 0xFFFFAA00 : 0xFF444444);

                ItemStack icon = resolveIcon(rule.match());
                if (!icon.isEmpty()) GuiCompat.drawItem(graphics, icon, center - 147, rowY + 2);
            }

            if (dragRule >= 0 && dragRule < current.size()) {
                int target = (int) (mouseY - 118) / 22;
                target = Math.max(0, Math.min(current.size() - 1, target));
                int lineY = 118 + target * 22;
                GuiCompat.outline(graphics, center - 150, lineY, 282, 20, 0xFF55FF55);
                ItemStack dragIcon = resolveIcon(current.get(dragRule).match());
                if (!dragIcon.isEmpty()) GuiCompat.drawItem(graphics, dragIcon, mouseX - 8, mouseY - 8);
            }

            if (!inventoryPalette.isEmpty()) {
                GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.quick_palette"), center, height - 62, 0xFFA0A0A0);
                int paletteStartX = center - Math.min(14, inventoryPalette.size()) * 11;
                int paletteY = height - 50;
                for (int i = 0; i < Math.min(14, inventoryPalette.size()); i++) {
                    GuiCompat.drawItem(graphics, inventoryPalette.get(i), paletteStartX + i * 22 + 2, paletteY + 2);
                }
            }
        }
        //?}
    }

    private record ItemOption(String id, String name, ItemStack icon) {}

    private final class ItemPickerScreen extends Screen {
        private final int slot;
        private final String ruleMatch;
        private final String rulePriority;
        private final boolean ruleBest;
        private final String initialQuery;
        private final int page;
        private EditBox search;
        private List<ItemOption> visibleItems = List.of();
        private static final int PAGE_SIZE = 8;
        private int totalItems;

        ItemPickerScreen(int slot, String ruleMatch, String rulePriority, boolean ruleBest, String query, int page) {
            super(Component.translatable("screen.hotbarsorter.item_picker"));
            this.slot = slot;
            this.ruleMatch = ruleMatch;
            this.rulePriority = rulePriority;
            this.ruleBest = ruleBest;
            this.initialQuery = query;
            this.page = page;
        }

        @Override protected void init() {
            int left = width / 2 - 190;
            search = addRenderableWidget(new EditBox(font, left, 46, 274, 20,
                    Component.translatable("screen.hotbarsorter.search_items")));
            search.setValue(initialQuery);

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.search"), b ->
                    ClientCompat.setScreen(minecraft, new ItemPickerScreen(slot, ruleMatch, rulePriority, ruleBest, search.getValue().trim(), 0)))
                    .bounds(left + 280, 46, 100, 20).build());

            List<ItemOption> matches = matchingItems(initialQuery);
            totalItems = matches.size();
            int firstIndex = Math.min(page * PAGE_SIZE, Math.max(0, matches.size() - 1));
            visibleItems = matches.subList(firstIndex, Math.min(firstIndex + PAGE_SIZE, matches.size()));

            for (int i = 0; i < visibleItems.size(); i++) {
                ItemOption option = visibleItems.get(i);
                int y = 72 + i * 25;
                Component label = Component.literal(option.name() + " (" + option.id() + ")");
                addRenderableWidget(Button.builder(label, b ->
                        ClientCompat.setScreen(minecraft, new RuleEditorScreen(slot, option.id(), rulePriority, ruleBest)))
                        .bounds(left + 24, y, 356, 22).build());
            }

            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b ->
                    ClientCompat.setScreen(minecraft, new RuleEditorScreen(slot, ruleMatch, rulePriority, ruleBest)))
                    .bounds(left, height - 30, 110, 20).build());

            if (page > 0) addRenderableWidget(Button.builder(Component.literal("‹"), b ->
                    ClientCompat.setScreen(minecraft, new ItemPickerScreen(slot, ruleMatch, rulePriority, ruleBest, initialQuery, page - 1)))
                    .bounds(width / 2 + 90, height - 30, 30, 20).build());
            if (firstIndex + PAGE_SIZE < matches.size()) addRenderableWidget(Button.builder(Component.literal("›"), b ->
                    ClientCompat.setScreen(minecraft, new ItemPickerScreen(slot, ruleMatch, rulePriority, ruleBest, initialQuery, page + 1)))
                    .bounds(width / 2 + 130, height - 30, 30, 20).build());
        }

        private List<ItemOption> matchingItems(String query) {
            String filter = query.trim().toLowerCase(Locale.ROOT);
            List<ItemOption> items = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                ItemStack stack = new ItemStack(item);
                String name = stack.getHoverName().getString();
                if (filter.isEmpty() || id.toLowerCase(Locale.ROOT).contains(filter) || name.toLowerCase(Locale.ROOT).contains(filter)) {
                    items.add(new ItemOption(id, name, stack));
                }
            }
            items.sort(Comparator.comparing(ItemOption::name));
            return items;
        }

        //? if <26.1 {
        /*@Override public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            super.render(graphics, mouseX, mouseY, delta);
            drawItemRows(graphics);
        }*///?} else {
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            drawItemRows(graphics);
        }
        //?}

        //? if <26.1 {
        /*private void drawItemRows(net.minecraft.client.gui.GuiGraphics graphics) {
            int left = width / 2 - 190;
            GuiCompat.drawCenteredText(graphics, font, title, width / 2, 22, 0xFFFFFFFF);
            int totalPages = Math.max(1, (totalItems + PAGE_SIZE - 1) / PAGE_SIZE);
            GuiCompat.drawText(graphics, font, Component.translatable("screen.hotbarsorter.page_info", page + 1, totalPages), width / 2 - 30, height - 24, 0xFFA0A0A0);

            for (int i = 0; i < visibleItems.size(); i++) {
                GuiCompat.drawItem(graphics, visibleItems.get(i).icon(), left + 3, 75 + i * 25);
            }
            if (visibleItems.isEmpty()) {
                GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.no_items"), width / 2, 130, 0xFFFFFFFF);
            }
        }*///?} else {
        private void drawItemRows(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
            int left = width / 2 - 190;
            GuiCompat.drawCenteredText(graphics, font, title, width / 2, 22, 0xFFFFFFFF);
            int totalPages = Math.max(1, (totalItems + PAGE_SIZE - 1) / PAGE_SIZE);
            GuiCompat.drawText(graphics, font, Component.translatable("screen.hotbarsorter.page_info", page + 1, totalPages), width / 2 - 30, height - 24, 0xFFA0A0A0);

            for (int i = 0; i < visibleItems.size(); i++) {
                GuiCompat.drawItem(graphics, visibleItems.get(i).icon(), left + 3, 75 + i * 25);
            }
            if (visibleItems.isEmpty()) {
                GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.no_items"), width / 2, 130, 0xFFFFFFFF);
            }
        }
        //?}
    }

    private record GroupOption(String key, String matcher, String exampleId) {}

    private final class GroupPickerScreen extends Screen {
        private final int slot;
        private final String ruleMatch;
        private final String rulePriority;
        private final boolean ruleBest;
        private boolean showCustom;
        private int page;
        private static final int PAGE_SIZE = 12;

        private final List<GroupOption> builtins = List.of(
                new GroupOption("screen.hotbarsorter.group_wood", "#builtin:all_wood", "minecraft:oak_log"),
                new GroupOption("screen.hotbarsorter.group_concrete", "#builtin:concrete", "minecraft:white_concrete"),
                new GroupOption("screen.hotbarsorter.group_concrete_powder", "#builtin:concrete_powder", "minecraft:white_concrete_powder"),
                new GroupOption("screen.hotbarsorter.group_terracotta", "#builtin:terracotta", "minecraft:terracotta"),
                new GroupOption("screen.hotbarsorter.group_glass", "#builtin:glass", "minecraft:glass"),
                new GroupOption("screen.hotbarsorter.group_stone", "#builtin:stone", "minecraft:cobblestone"),
                new GroupOption("screen.hotbarsorter.group_ores", "#builtin:ores", "minecraft:raw_iron"),
                new GroupOption("screen.hotbarsorter.group_golden_apples", "#builtin:golden_apples", "minecraft:golden_apple"),
                new GroupOption("screen.hotbarsorter.group_beds", "#minecraft:beds", "minecraft:red_bed"),
                new GroupOption("screen.hotbarsorter.group_swords", "#minecraft:swords", "minecraft:iron_sword"),
                new GroupOption("screen.hotbarsorter.group_pickaxes", "#minecraft:pickaxes", "minecraft:iron_pickaxe"),
                new GroupOption("screen.hotbarsorter.group_axes", "#minecraft:axes", "minecraft:iron_axe"),
                new GroupOption("screen.hotbarsorter.group_shovels", "#minecraft:shovels", "minecraft:iron_shovel"),
                new GroupOption("screen.hotbarsorter.group_hoes", "#minecraft:hoes", "minecraft:iron_hoe"),
                new GroupOption("screen.hotbarsorter.group_bows", "#builtin:bows", "minecraft:bow"),
                new GroupOption("screen.hotbarsorter.group_food", "#builtin:food", "minecraft:cooked_beef"),
                new GroupOption("screen.hotbarsorter.group_wool", "#minecraft:wool", "minecraft:white_wool"),
                new GroupOption("screen.hotbarsorter.group_logs", "#minecraft:logs", "minecraft:oak_log"),
                new GroupOption("screen.hotbarsorter.group_planks", "#minecraft:planks", "minecraft:oak_planks"),
                new GroupOption("screen.hotbarsorter.group_arrows", "#minecraft:arrows", "minecraft:arrow"),
                new GroupOption("screen.hotbarsorter.group_potions", "#builtin:potions", "minecraft:potion"));

        GroupPickerScreen(int slot, String ruleMatch, String rulePriority, boolean ruleBest) {
            super(Component.translatable("screen.hotbarsorter.group_picker"));
            this.slot = slot;
            this.ruleMatch = ruleMatch;
            this.rulePriority = rulePriority;
            this.ruleBest = ruleBest;
        }

        @Override protected void init() {
            int cx = width / 2;
            int colW = 185;

            // Tabs: Built-in vs Custom
            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.builtin_groups"), b -> {
                showCustom = false;
                page = 0;
                rebuildWidgets();
            }).bounds(cx - 180, 24, 110, 18).build());

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.custom_groups"), b -> {
                showCustom = true;
                page = 0;
                rebuildWidgets();
            }).bounds(cx - 65, 24, 110, 18).build());

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.new_group"), b -> {
                ClientCompat.setScreen(minecraft, new CustomGroupEditorScreen("", slot, ruleMatch, rulePriority, ruleBest));
            }).bounds(cx + 50, 24, 130, 18).build());

            if (!showCustom) {
                int totalPages = Math.max(1, (builtins.size() + PAGE_SIZE - 1) / PAGE_SIZE);
                if (page >= totalPages) page = totalPages - 1;
                int start = page * PAGE_SIZE;
                int end = Math.min(start + PAGE_SIZE, builtins.size());

                for (int i = start; i < end; i++) {
                    GroupOption group = builtins.get(i);
                    int rel = i - start;
                    int col = rel % 2;
                    int row = rel / 2;
                    int x = col == 0 ? cx - colW - 5 : cx + 5;
                    int y = 46 + row * 24;

                    addRenderableWidget(Button.builder(Component.translatable(group.key()), b ->
                            ClientCompat.setScreen(minecraft, new RuleEditorScreen(slot, group.matcher(), rulePriority, ruleBest)))
                            .bounds(x + 22, y, colW - 22, 22).build());
                }

                if (page > 0) addRenderableWidget(Button.builder(Component.literal("‹"), b -> {
                    page--;
                    rebuildWidgets();
                }).bounds(cx - 30, height - 28, 24, 20).build());

                if (end < builtins.size()) addRenderableWidget(Button.builder(Component.literal("›"), b -> {
                    page++;
                    rebuildWidgets();
                }).bounds(cx + 6, height - 28, 24, 20).build());
            } else {
                List<String> customList = new ArrayList<>(CONFIG.customGroups.keySet());
                int totalPages = Math.max(1, (customList.size() + PAGE_SIZE - 1) / PAGE_SIZE);
                if (page >= totalPages) page = totalPages - 1;
                int start = page * PAGE_SIZE;
                int end = Math.min(start + PAGE_SIZE, customList.size());

                for (int i = start; i < end; i++) {
                    String groupName = customList.get(i);
                    int rel = i - start;
                    int col = rel % 2;
                    int row = rel / 2;
                    int x = col == 0 ? cx - colW - 5 : cx + 5;
                    int y = 46 + row * 24;

                    List<String> items = CONFIG.getCustomGroupItems(groupName);
                    Component label = Component.literal("#group:" + groupName + " (" + items.size() + ")");
                    addRenderableWidget(Button.builder(label, b ->
                            ClientCompat.setScreen(minecraft, new RuleEditorScreen(slot, "#group:" + groupName, rulePriority, ruleBest)))
                            .bounds(x + 22, y, colW - 68, 22).build());

                    addRenderableWidget(Button.builder(Component.literal("✎"), b ->
                            ClientCompat.setScreen(minecraft, new CustomGroupEditorScreen(groupName, slot, ruleMatch, rulePriority, ruleBest)))
                            .bounds(x + colW - 44, y, 20, 22).build());

                    addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                        CONFIG.deleteCustomGroup(groupName);
                        rebuildWidgets();
                    }).bounds(x + colW - 22, y, 20, 22).build());
                }

                if (page > 0) addRenderableWidget(Button.builder(Component.literal("‹"), b -> {
                    page--;
                    rebuildWidgets();
                }).bounds(cx - 30, height - 28, 24, 20).build());

                if (end < customList.size()) addRenderableWidget(Button.builder(Component.literal("›"), b -> {
                    page++;
                    rebuildWidgets();
                }).bounds(cx + 6, height - 28, 24, 20).build());
            }

            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b ->
                    ClientCompat.setScreen(minecraft, new RuleEditorScreen(slot, ruleMatch, rulePriority, ruleBest)))
                    .bounds(cx - 160, height - 28, 120, 20).build());
        }

        //? if <26.1 {
        /*@Override public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            super.render(graphics, mouseX, mouseY, delta);
            renderGroupIcons(graphics);
        }*///?} else {
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            renderGroupIcons(graphics);
        }
        //?}

        //? if <26.1 {
        /*private void renderGroupIcons(net.minecraft.client.gui.GuiGraphics graphics) {
            int cx = width / 2;
            int colW = 185;
            if (!showCustom) {
                int start = page * PAGE_SIZE;
                int end = Math.min(start + PAGE_SIZE, builtins.size());
                for (int i = start; i < end; i++) {
                    int rel = i - start;
                    int col = rel % 2;
                    int row = rel / 2;
                    int x = col == 0 ? cx - colW - 5 : cx + 5;
                    int y = 46 + row * 24;
                    GuiCompat.drawItem(graphics, findItem(builtins.get(i).exampleId()), x + 3, y + 3);
                }
            } else {
                List<String> customList = new ArrayList<>(CONFIG.customGroups.keySet());
                int start = page * PAGE_SIZE;
                int end = Math.min(start + PAGE_SIZE, customList.size());
                for (int i = start; i < end; i++) {
                    int rel = i - start;
                    int col = rel % 2;
                    int row = rel / 2;
                    int x = col == 0 ? cx - colW - 5 : cx + 5;
                    int y = 46 + row * 24;
                    List<String> items = CONFIG.getCustomGroupItems(customList.get(i));
                    ItemStack icon = items.isEmpty() ? findItem("minecraft:chest") : findItem(items.get(0));
                    GuiCompat.drawItem(graphics, icon, x + 3, y + 3);
                }
                if (customList.isEmpty()) {
                    GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.no_custom_groups"), cx, 80, 0xFFA0A0A0);
                }
            }
        }*///?} else {
        private void renderGroupIcons(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
            int cx = width / 2;
            int colW = 185;
            if (!showCustom) {
                int start = page * PAGE_SIZE;
                int end = Math.min(start + PAGE_SIZE, builtins.size());
                for (int i = start; i < end; i++) {
                    int rel = i - start;
                    int col = rel % 2;
                    int row = rel / 2;
                    int x = col == 0 ? cx - colW - 5 : cx + 5;
                    int y = 46 + row * 24;
                    GuiCompat.drawItem(graphics, findItem(builtins.get(i).exampleId()), x + 3, y + 3);
                }
            } else {
                List<String> customList = new ArrayList<>(CONFIG.customGroups.keySet());
                int start = page * PAGE_SIZE;
                int end = Math.min(start + PAGE_SIZE, customList.size());
                for (int i = start; i < end; i++) {
                    int rel = i - start;
                    int col = rel % 2;
                    int row = rel / 2;
                    int x = col == 0 ? cx - colW - 5 : cx + 5;
                    int y = 46 + row * 24;
                    List<String> items = CONFIG.getCustomGroupItems(customList.get(i));
                    ItemStack icon = items.isEmpty() ? findItem("minecraft:chest") : findItem(items.get(0));
                    GuiCompat.drawItem(graphics, icon, x + 3, y + 3);
                }
                if (customList.isEmpty()) {
                    GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.no_custom_groups"), cx, 80, 0xFFA0A0A0);
                }
            }
        }
        //?}
    }

    private final class CustomGroupEditorScreen extends Screen {
        private final int returnSlot;
        private final String returnMatch;
        private final String returnPriority;
        private final boolean returnBest;
        private final String initialGroupName;
        private EditBox nameBox;
        private EditBox searchBox;
        private String searchQuery = "";
        private int page;
        private final Set<String> selectedItems = new TreeSet<>();
        private List<ItemOption> filteredItems = List.of();
        private static final int COLS = 9;
        private static final int ROWS = 4;
        private static final int ITEMS_PER_PAGE = COLS * ROWS;

        CustomGroupEditorScreen(String groupName, int returnSlot, String returnMatch, String returnPriority, boolean returnBest) {
            super(Component.translatable("screen.hotbarsorter.custom_group_editor", groupName.isEmpty() ? "..." : groupName));
            this.initialGroupName = groupName;
            this.returnSlot = returnSlot;
            this.returnMatch = returnMatch;
            this.returnPriority = returnPriority;
            this.returnBest = returnBest;
            if (!groupName.isEmpty()) {
                selectedItems.addAll(CONFIG.getCustomGroupItems(groupName));
            }
        }

        @Override protected void init() {
            int cx = width / 2;

            nameBox = addRenderableWidget(new EditBox(font, cx - 180, 24, 140, 18,
                    Component.translatable("screen.hotbarsorter.group_name")));
            nameBox.setValue(initialGroupName);

            searchBox = addRenderableWidget(new EditBox(font, cx - 35, 24, 150, 18,
                    Component.translatable("screen.hotbarsorter.search_items")));
            searchBox.setValue(searchQuery);

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.search"), b -> {
                searchQuery = searchBox.getValue().trim();
                page = 0;
                rebuildWidgets();
            }).bounds(cx + 120, 24, 60, 18).build());

            filteredItems = computeFilteredItems(searchQuery);
            int totalPages = Math.max(1, (filteredItems.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
            if (page >= totalPages) page = totalPages - 1;

            int start = page * ITEMS_PER_PAGE;
            int end = Math.min(start + ITEMS_PER_PAGE, filteredItems.size());
            int gridStartX = cx - (COLS * 24) / 2;
            int gridStartY = 46;

            for (int i = start; i < end; i++) {
                int rel = i - start;
                int col = rel % COLS;
                int row = rel / COLS;
                int x = gridStartX + col * 24;
                int y = gridStartY + row * 24;
                String itemId = filteredItems.get(i).id();

                addRenderableWidget(Button.builder(Component.empty(), b -> {
                    if (selectedItems.contains(itemId)) selectedItems.remove(itemId);
                    else selectedItems.add(itemId);
                    rebuildWidgets();
                }).bounds(x, y, 22, 22).build());
            }

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.add_from_inventory"), b -> {
                if (minecraft != null && minecraft.player != null) {
                    var inv = minecraft.player.getInventory();
                    for (int s = 0; s < 36; s++) {
                        ItemStack st = inv.getItem(s);
                        if (!st.isEmpty()) {
                            selectedItems.add(BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
                        }
                    }
                    rebuildWidgets();
                }
            }).bounds(cx - 180, 146, 110, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.clear_selection"), b -> {
                selectedItems.clear();
                rebuildWidgets();
            }).bounds(cx - 65, 146, 65, 20).build());

            if (page > 0) {
                addRenderableWidget(Button.builder(Component.literal("‹"), b -> {
                    page--;
                    rebuildWidgets();
                }).bounds(cx + 40, 146, 24, 20).build());
            }
            if (end < filteredItems.size()) {
                addRenderableWidget(Button.builder(Component.literal("›"), b -> {
                    page++;
                    rebuildWidgets();
                }).bounds(cx + 100, 146, 24, 20).build());
            }

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.save_group"), b -> {
                String name = nameBox.getValue().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
                if (!name.isEmpty() && !selectedItems.isEmpty()) {
                    CONFIG.saveCustomGroup(name, new ArrayList<>(selectedItems));
                    ClientCompat.setScreen(minecraft, new GroupPickerScreen(returnSlot, returnMatch, returnPriority, returnBest));
                }
            }).bounds(cx - 150, height - 28, 145, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> {
                ClientCompat.setScreen(minecraft, new GroupPickerScreen(returnSlot, returnMatch, returnPriority, returnBest));
            }).bounds(cx + 5, height - 28, 145, 20).build());
        }

        private List<ItemOption> computeFilteredItems(String query) {
            String filter = query.trim().toLowerCase(Locale.ROOT);
            List<ItemOption> list = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                ItemStack stack = new ItemStack(item);
                String name = stack.getHoverName().getString();
                if (filter.isEmpty() || id.toLowerCase(Locale.ROOT).contains(filter) || name.toLowerCase(Locale.ROOT).contains(filter)) {
                    list.add(new ItemOption(id, name, stack));
                }
            }
            list.sort(Comparator.comparing(ItemOption::name));
            return list;
        }

        //? if <26.1 {
        /*@Override public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            super.render(graphics, mouseX, mouseY, delta);
            renderGridContent(graphics, mouseX, mouseY);
        }*///?} else {
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            renderGridContent(graphics, mouseX, mouseY);
        }
        //?}

        //? if <26.1 {
        /*private void renderGridContent(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY) {
            int cx = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, cx, 10, 0xFFFFFFFF);
            GuiCompat.drawText(graphics, font, Component.translatable("screen.hotbarsorter.selected_items", selectedItems.size()), cx + 5, 151, 0xFF55FF55);

            int start = page * ITEMS_PER_PAGE;
            int end = Math.min(start + ITEMS_PER_PAGE, filteredItems.size());
            int gridStartX = cx - (COLS * 24) / 2;
            int gridStartY = 46;

            for (int i = start; i < end; i++) {
                int rel = i - start;
                int col = rel % COLS;
                int row = rel / COLS;
                int x = gridStartX + col * 24;
                int y = gridStartY + row * 24;
                ItemOption opt = filteredItems.get(i);
                boolean selected = selectedItems.contains(opt.id());

                if (selected) {
                    GuiCompat.fill(graphics, x, y, x + 22, y + 22, 0x80206020);
                    GuiCompat.outline(graphics, x, y, 22, 22, 0xFF55FF55);
                }
                GuiCompat.drawItem(graphics, opt.icon(), x + 3, y + 3);

                if (mouseX >= x && mouseX < x + 22 && mouseY >= y && mouseY < y + 22) {
                    GuiCompat.drawTooltip(graphics, font, List.of(Component.literal(opt.name()), Component.literal(opt.id()).withStyle(ChatFormatting.DARK_GRAY)), mouseX, mouseY);
                }
            }
        }*///?} else {
        private void renderGridContent(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            int cx = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, cx, 10, 0xFFFFFFFF);
            GuiCompat.drawText(graphics, font, Component.translatable("screen.hotbarsorter.selected_items", selectedItems.size()), cx + 5, 151, 0xFF55FF55);

            int start = page * ITEMS_PER_PAGE;
            int end = Math.min(start + ITEMS_PER_PAGE, filteredItems.size());
            int gridStartX = cx - (COLS * 24) / 2;
            int gridStartY = 46;

            for (int i = start; i < end; i++) {
                int rel = i - start;
                int col = rel % COLS;
                int row = rel / COLS;
                int x = gridStartX + col * 24;
                int y = gridStartY + row * 24;
                ItemOption opt = filteredItems.get(i);
                boolean selected = selectedItems.contains(opt.id());

                if (selected) {
                    GuiCompat.fill(graphics, x, y, x + 22, y + 22, 0x80206020);
                    GuiCompat.outline(graphics, x, y, 22, 22, 0xFF55FF55);
                }
                GuiCompat.drawItem(graphics, opt.icon(), x + 3, y + 3);

                if (mouseX >= x && mouseX < x + 22 && mouseY >= y && mouseY < y + 22) {
                    GuiCompat.drawTooltip(graphics, font, List.of(Component.literal(opt.name()), Component.literal(opt.id()).withStyle(ChatFormatting.DARK_GRAY)), mouseX, mouseY);
                }
            }
        }
        //?}
    }
}
