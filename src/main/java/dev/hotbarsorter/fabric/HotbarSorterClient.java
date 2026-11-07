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
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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
                List<ItemView> items = InventoryCompat.snapshot(client);
                Map<String, Integer> counts = itemCounts(items);
                List<String> fingerprint = fingerprint(items);
                List<String> hotbarFingerprint = fingerprint(items.subList(0, 9));

                if (previousScreen == null && screen != null) {
                    screenOpenCounts = counts;
                    screenOpenFingerprint = fingerprint;
                }

                boolean screenJustClosed = previousScreen != null && screen == null;
                boolean inventoryChangedInScreen = screenJustClosed
                        && (!screenOpenCounts.equals(counts) || !screenOpenFingerprint.equals(fingerprint));

                boolean manualRearrangement = hasItemSnapshot && !sortingRequested && screen == null
                        && previousItemCounts.equals(counts) && !previousSlotFingerprint.equals(fingerprint);
                if (manualRearrangement) manualMoveCooldown = 20;
                else if (manualMoveCooldown > 0) manualMoveCooldown--;

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
                previousItemCounts = counts;
                previousSlotFingerprint = fingerprint;
                previousHotbarFingerprint = hotbarFingerprint;
                hasItemSnapshot = true;
            } else {
                previousItemCounts = Map.of();
                previousSlotFingerprint = List.of();
                previousHotbarFingerprint = List.of();
                screenOpenCounts = Map.of();
                screenOpenFingerprint = List.of();
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
        if (InventoryCompat.mergeOneStack(client)) return;
        List<SortPlanner.Swap> plan = planner.plan(InventoryCompat.snapshot(client), CONFIG.rules);
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
        if (tag.contains("sword")) return findItem("minecraft:iron_sword");
        if (tag.contains("pickaxe")) return findItem("minecraft:iron_pickaxe");
        if (tag.contains("axe")) return findItem("minecraft:iron_axe");
        if (tag.contains("shovel")) return findItem("minecraft:iron_shovel");
        if (tag.contains("hoe")) return findItem("minecraft:iron_hoe");
        if (tag.contains("bow")) return findItem("minecraft:bow");
        if (tag.contains("food") || tag.contains("meat")) return findItem("minecraft:cooked_beef");
        if (tag.contains("apple")) return findItem("minecraft:golden_apple");
        if (tag.contains("wool")) return findItem("minecraft:white_wool");
        if (tag.contains("log")) return findItem("minecraft:oak_log");
        if (tag.contains("plank")) return findItem("minecraft:oak_planks");
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
            drawMainScreen(graphics, mouseX, mouseY);
        }*///?} else {
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            drawMainScreen(graphics, mouseX, mouseY);
        }
        //?}

        //? if <26.1 {
        /*private void drawMainScreen(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY) {
            renderSlotsAndHints(graphics, mouseX, mouseY);
        }*///?} else {
        private void drawMainScreen(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            renderSlotsAndHints(graphics, mouseX, mouseY);
        }
        //?}

        //? if <26.1 {
        /*private void renderSlotsAndHints(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY) {
            renderSlots(graphics, mouseX, mouseY);
        }*///?} else {
        private void renderSlotsAndHints(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            renderSlots(graphics, mouseX, mouseY);
        }
        //?}

        //? if <26.1 {
        /*private void renderSlots(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY) {
            drawSlotsContent(graphics, mouseX, mouseY);
        }*///?} else {
        private void renderSlots(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
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

            for (int i = start; i < end; i++) {
                String presetName = list.get(i);
                int rowY = 44 + (i - start) * 44;

                addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.preset_load"), b -> {
                    CONFIG.loadPreset(presetName);
                    ClientCompat.setScreen(minecraft, new SettingsScreen());
                }).bounds(cx + 42, rowY + 10, 56, 20).build());

                addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.preset_overwrite"), b -> {
                    CONFIG.savePreset(presetName);
                    rebuildWidgets();
                }).bounds(cx + 102, rowY + 10, 56, 20).build());

                addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                    CONFIG.deletePreset(presetName);
                    rebuildWidgets();
                }).bounds(cx + 162, rowY + 10, 22, 20).build());
            }

            // Bottom actions
            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.new_preset"), b ->
                    ClientCompat.setScreen(minecraft, new PresetNameScreen())).bounds(cx - 184, height - 32, 110, 20).build());

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
                    ClientCompat.setScreen(minecraft, new SettingsScreen())).bounds(cx + 74, height - 32, 110, 20).build());
        }

        //? if <26.1 {
        /*@Override public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            super.render(graphics, mouseX, mouseY, delta);
            drawPresetManagerContent(graphics, mouseX, mouseY);
        }*///?} else {
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            drawPresetManagerContent(graphics, mouseX, mouseY);
        }
        //?}

        //? if <26.1 {
        /*private void drawPresetManagerContent(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY) {
            drawPresetRows(graphics, mouseX, mouseY);
        }*///?} else {
        private void drawPresetManagerContent(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            drawPresetRows(graphics, mouseX, mouseY);
        }
        //?}

        //? if <26.1 {
        /*private void drawPresetRows(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY) {
            renderPresetsList(graphics);
        }*///?} else {
        private void drawPresetRows(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            renderPresetsList(graphics);
        }
        //?}

        //? if <26.1 {
        /*private void renderPresetsList(net.minecraft.client.gui.GuiGraphics graphics) {
            int cx = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, cx, 20, 0xFFFFFFFF);
            List<String> list = new ArrayList<>(CONFIG.presets.keySet());
            int totalPages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.page_info", page + 1, totalPages), cx - 6, height - 26, 0xFFA0A0A0);

            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, list.size());

            int left = cx - 184;
            int cardW = 370;

            for (int i = start; i < end; i++) {
                String name = list.get(i);
                int rowY = 44 + (i - start) * 44;
                boolean isActive = name.equalsIgnoreCase(CONFIG.activePreset);

                GuiCompat.fill(graphics, left, rowY, left + cardW, rowY + 38, isActive ? 0x90282410 : 0x90151515);
                GuiCompat.outline(graphics, left, rowY, cardW, 38, isActive ? 0xFFFFAA00 : 0xFF444444);

                Component nameComp = Component.literal((isActive ? "★ " : "") + name)
                        .withStyle(isActive ? ChatFormatting.GOLD : ChatFormatting.WHITE, ChatFormatting.BOLD);
                GuiCompat.drawText(graphics, font, nameComp, left + 6, rowY + 4, 0xFFFFFFFF);

                RuleSet set = CONFIG.presets.get(name);
                for (int s = 0; s < 9; s++) {
                    int slotX = left + 6 + s * 23;
                    int slotY = rowY + 16;
                    GuiCompat.fill(graphics, slotX, slotY, slotX + 18, slotY + 18, 0x70000000);
                    GuiCompat.outline(graphics, slotX, slotY, 18, 18, 0xFF353535);
                    ItemStack icon = slotIcon(set, s);
                    if (!icon.isEmpty()) GuiCompat.drawItem(graphics, icon, slotX + 1, slotY + 1);
                }
            }
        }*///?} else {
        private void renderPresetsList(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
            int cx = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, cx, 20, 0xFFFFFFFF);
            List<String> list = new ArrayList<>(CONFIG.presets.keySet());
            int totalPages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            GuiCompat.drawCenteredText(graphics, font, Component.translatable("screen.hotbarsorter.page_info", page + 1, totalPages), cx - 6, height - 26, 0xFFA0A0A0);

            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, list.size());

            int left = cx - 184;
            int cardW = 370;

            for (int i = start; i < end; i++) {
                String name = list.get(i);
                int rowY = 44 + (i - start) * 44;
                boolean isActive = name.equalsIgnoreCase(CONFIG.activePreset);

                GuiCompat.fill(graphics, left, rowY, left + cardW, rowY + 38, isActive ? 0x90282410 : 0x90151515);
                GuiCompat.outline(graphics, left, rowY, cardW, 38, isActive ? 0xFFFFAA00 : 0xFF444444);

                Component nameComp = Component.literal((isActive ? "★ " : "") + name)
                        .withStyle(isActive ? ChatFormatting.GOLD : ChatFormatting.WHITE, ChatFormatting.BOLD);
                GuiCompat.drawText(graphics, font, nameComp, left + 6, rowY + 4, 0xFFFFFFFF);

                RuleSet set = CONFIG.presets.get(name);
                for (int s = 0; s < 9; s++) {
                    int slotX = left + 6 + s * 23;
                    int slotY = rowY + 16;
                    GuiCompat.fill(graphics, slotX, slotY, slotX + 18, slotY + 18, 0x70000000);
                    GuiCompat.outline(graphics, slotX, slotY, 18, 18, 0xFF353535);
                    ItemStack icon = slotIcon(set, s);
                    if (!icon.isEmpty()) GuiCompat.drawItem(graphics, icon, slotX + 1, slotY + 1);
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
        private EditBox priority;
        private boolean best;
        private final String initialMatch;
        private final String initialPriority;

        RuleEditorScreen(int slot) { this(slot, "", "10", true); }

        RuleEditorScreen(int slot, String matchValue, String priorityValue, boolean bestValue) {
            super(Component.translatable("screen.hotbarsorter.rule_title", slot + 1));
            this.slot = slot;
            this.initialMatch = matchValue;
            this.initialPriority = priorityValue;
            this.best = bestValue;
        }

        @Override protected void init() {
            int center = width / 2;

            matcher = addRenderableWidget(new EditBox(font, center - 150, 48, 220, 20,
                    Component.translatable("screen.hotbarsorter.match")));
            matcher.setValue(initialMatch);

            priority = addRenderableWidget(new EditBox(font, center + 76, 48, 74, 20,
                    Component.translatable("screen.hotbarsorter.priority")));
            priority.setValue(initialPriority);

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.choose_item"), b ->
                    ClientCompat.setScreen(minecraft, new ItemPickerScreen(slot, matcher.getValue(), priority.getValue(), best, "", 0)))
                    .bounds(center - 150, 72, 146, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.choose_group"), b ->
                    ClientCompat.setScreen(minecraft, new GroupPickerScreen(slot, matcher.getValue(), priority.getValue(), best)))
                    .bounds(center + 4, 72, 146, 20).build());

            addRenderableWidget(Button.builder(lockLabel(), b -> {
                CONFIG.toggleLock(slot);
                ClientCompat.setScreen(minecraft, new RuleEditorScreen(slot));
            }).bounds(center - 150, 96, 146, 20).build());

            addRenderableWidget(Button.builder(pickLabel(), b -> {
                best = !best;
                b.setMessage(pickLabel());
            }).bounds(center + 4, 96, 146, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("screen.hotbarsorter.add_rule"), b -> addRule())
                    .bounds(center - 150, 120, 300, 20).build());

            List<Rule> current = CONFIG.rules.rules(slot);
            for (int i = 0; i < current.size(); i++) {
                Rule rule = current.get(i);
                if (rule.lock()) continue;
                int index = i;
                int rowY = 146 + i * 24;

                Component label = Component.literal(rule.match() + "  ·  " + rule.priority() + (rule.pick() == Rule.Pick.BEST ? " [best]" : ""));
                addRenderableWidget(Button.builder(label, b -> {}).bounds(center - 124, rowY, 246, 20).build());

                addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                    CONFIG.removeRule(slot, index);
                    ClientCompat.setScreen(minecraft, new RuleEditorScreen(slot));
                }).bounds(center + 126, rowY, 24, 20).build());
            }

            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b ->
                    ClientCompat.setScreen(minecraft, new SettingsScreen())).bounds(center - 100, height - 30, 200, 20).build());
        }

        private void addRule() {
            String match = matcher.getValue().trim();
            try {
                int weight = Integer.parseInt(priority.getValue().trim());
                if (match.isEmpty() || match.equals("#")) throw new IllegalArgumentException();
                CONFIG.addRule(slot, new Rule(match, weight, best ? Rule.Pick.BEST : Rule.Pick.FIRST, false));
                ClientCompat.setScreen(minecraft, new RuleEditorScreen(slot));
            } catch (RuntimeException ex) {
                ClientCompat.showSystemMessage(minecraft, Component.translatable("screen.hotbarsorter.invalid_rule"));
            }
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
            drawRuleEditorContent(graphics);
        }*///?} else {
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            drawRuleEditorContent(graphics);
        }
        //?}

        //? if <26.1 {
        /*private void drawRuleEditorContent(net.minecraft.client.gui.GuiGraphics graphics) {
            renderEditorWidgets(graphics);
        }*///?} else {
        private void drawRuleEditorContent(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
            renderEditorWidgets(graphics);
        }
        //?}

        //? if <26.1 {
        /*private void renderEditorWidgets(net.minecraft.client.gui.GuiGraphics graphics) {
            int center = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, center, 20, 0xFFFFFFFF);
            GuiCompat.drawText(graphics, font, Component.translatable("screen.hotbarsorter.match"), center - 150, 36, 0xFFA0A0A0);
            GuiCompat.drawText(graphics, font, Component.translatable("screen.hotbarsorter.priority"), center + 76, 36, 0xFFA0A0A0);

            List<Rule> current = CONFIG.rules.rules(slot);
            for (int i = 0; i < current.size(); i++) {
                Rule rule = current.get(i);
                if (rule.lock()) continue;
                int rowY = 146 + i * 24;
                ItemStack icon = resolveIcon(rule.match());
                if (!icon.isEmpty()) GuiCompat.drawItem(graphics, icon, center - 146, rowY + 2);
            }
        }*///?} else {
        private void renderEditorWidgets(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
            int center = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, center, 20, 0xFFFFFFFF);
            GuiCompat.drawText(graphics, font, Component.translatable("screen.hotbarsorter.match"), center - 150, 36, 0xFFA0A0A0);
            GuiCompat.drawText(graphics, font, Component.translatable("screen.hotbarsorter.priority"), center + 76, 36, 0xFFA0A0A0);

            List<Rule> current = CONFIG.rules.rules(slot);
            for (int i = 0; i < current.size(); i++) {
                Rule rule = current.get(i);
                if (rule.lock()) continue;
                int rowY = 146 + i * 24;
                ItemStack icon = resolveIcon(rule.match());
                if (!icon.isEmpty()) GuiCompat.drawItem(graphics, icon, center - 146, rowY + 2);
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
            drawItemPickerContent(graphics);
        }*///?} else {
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            drawItemPickerContent(graphics);
        }
        //?}

        //? if <26.1 {
        /*private void drawItemPickerContent(net.minecraft.client.gui.GuiGraphics graphics) {
            drawItemRows(graphics);
        }*///?} else {
        private void drawItemPickerContent(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
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
        private final List<GroupOption> groups = List.of(
                new GroupOption("screen.hotbarsorter.group_swords", "#minecraft:swords", "minecraft:iron_sword"),
                new GroupOption("screen.hotbarsorter.group_pickaxes", "#minecraft:pickaxes", "minecraft:iron_pickaxe"),
                new GroupOption("screen.hotbarsorter.group_axes", "#minecraft:axes", "minecraft:iron_axe"),
                new GroupOption("screen.hotbarsorter.group_shovels", "#minecraft:shovels", "minecraft:iron_shovel"),
                new GroupOption("screen.hotbarsorter.group_hoes", "#minecraft:hoes", "minecraft:iron_hoe"),
                new GroupOption("screen.hotbarsorter.group_bows", "#minecraft:bows", "minecraft:bow"),
                new GroupOption("screen.hotbarsorter.group_food", "#minecraft:food", "minecraft:cooked_beef"),
                new GroupOption("screen.hotbarsorter.group_wool", "#minecraft:wool", "minecraft:white_wool"),
                new GroupOption("screen.hotbarsorter.group_logs", "#minecraft:logs", "minecraft:oak_log"),
                new GroupOption("screen.hotbarsorter.group_planks", "#minecraft:planks", "minecraft:oak_planks"),
                new GroupOption("screen.hotbarsorter.group_arrows", "#minecraft:arrows", "minecraft:arrow"),
                new GroupOption("screen.hotbarsorter.group_potions", "#minecraft:potions", "minecraft:potion"));

        GroupPickerScreen(int slot, String ruleMatch, String rulePriority, boolean ruleBest) {
            super(Component.translatable("screen.hotbarsorter.group_picker"));
            this.slot = slot;
            this.ruleMatch = ruleMatch;
            this.rulePriority = rulePriority;
            this.ruleBest = ruleBest;
        }

        @Override protected void init() {
            int cx = width / 2;
            int colW = 175;

            for (int i = 0; i < groups.size(); i++) {
                GroupOption group = groups.get(i);
                int col = i % 2;
                int row = i / 2;
                int x = col == 0 ? cx - colW - 5 : cx + 5;
                int y = 46 + row * 28;

                addRenderableWidget(Button.builder(Component.translatable(group.key()), b ->
                        ClientCompat.setScreen(minecraft, new RuleEditorScreen(slot, group.matcher(), rulePriority, ruleBest)))
                        .bounds(x + 24, y, colW - 24, 24).build());
            }

            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b ->
                    ClientCompat.setScreen(minecraft, new RuleEditorScreen(slot, ruleMatch, rulePriority, ruleBest)))
                    .bounds(cx - 100, height - 30, 200, 20).build());
        }

        //? if <26.1 {
        /*@Override public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            super.render(graphics, mouseX, mouseY, delta);
            drawGroupPickerIcons(graphics);
        }*///?} else {
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            drawGroupPickerIcons(graphics);
        }
        //?}

        //? if <26.1 {
        /*private void drawGroupPickerIcons(net.minecraft.client.gui.GuiGraphics graphics) {
            renderGroupIcons(graphics);
        }*///?} else {
        private void drawGroupPickerIcons(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
            renderGroupIcons(graphics);
        }
        //?}

        //? if <26.1 {
        /*private void renderGroupIcons(net.minecraft.client.gui.GuiGraphics graphics) {
            int cx = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, cx, 20, 0xFFFFFFFF);
            int colW = 175;
            for (int i = 0; i < groups.size(); i++) {
                int col = i % 2;
                int row = i / 2;
                int x = col == 0 ? cx - colW - 5 : cx + 5;
                int y = 46 + row * 28;
                GuiCompat.drawItem(graphics, findItem(groups.get(i).exampleId()), x + 3, y + 4);
            }
        }*///?} else {
        private void renderGroupIcons(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
            int cx = width / 2;
            GuiCompat.drawCenteredText(graphics, font, title, cx, 20, 0xFFFFFFFF);
            int colW = 175;
            for (int i = 0; i < groups.size(); i++) {
                int col = i % 2;
                int row = i / 2;
                int x = col == 0 ? cx - colW - 5 : cx + 5;
                int y = 46 + row * 28;
                GuiCompat.drawItem(graphics, findItem(groups.get(i).exampleId()), x + 3, y + 4);
            }
        }
        //?}
    }
}
