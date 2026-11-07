package dev.hotbarsorter.fabric.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.List;

public final class GuiCompat {
    private GuiCompat() {}

    //? if <26.1 {
    /*public static void drawItem(net.minecraft.client.gui.GuiGraphics graphics, ItemStack stack, int x, int y) {
        graphics.renderItem(stack, x, y);
    }
    public static void fill(net.minecraft.client.gui.GuiGraphics graphics, int minX, int minY, int maxX, int maxY, int color) {
        graphics.fill(minX, minY, maxX, maxY, color);
    }
    public static void outline(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.renderOutline(x, y, width, height, color);
    }
    public static void drawText(net.minecraft.client.gui.GuiGraphics graphics, Font font, Component text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color);
    }
    public static void drawCenteredText(net.minecraft.client.gui.GuiGraphics graphics, Font font, Component text, int x, int y, int color) {
        graphics.drawCenteredString(font, text, x, y, color);
    }
    public static void drawTooltip(net.minecraft.client.gui.GuiGraphics graphics, Font font, List<Component> lines, int x, int y) {
        //? if <1.21.11 {
        graphics.renderComponentTooltip(font, lines, x, y);
        //?} else {
        graphics.setComponentTooltipForNextFrame(font, lines, x, y);
        //?}
    }
    *///?} else {
    public static void drawItem(net.minecraft.client.gui.GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        graphics.item(stack, x, y);
    }
    public static void fill(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int minX, int minY, int maxX, int maxY, int color) {
        graphics.fill(minX, minY, maxX, maxY, color);
    }
    public static void outline(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        graphics.outline(x, y, width, height, color);
    }
    public static void drawText(net.minecraft.client.gui.GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color) {
        graphics.text(font, text, x, y, color);
    }
    public static void drawCenteredText(net.minecraft.client.gui.GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color) {
        graphics.centeredText(font, text, x, y, color);
    }
    public static void drawTooltip(net.minecraft.client.gui.GuiGraphicsExtractor graphics, Font font, List<Component> lines, int x, int y) {
        graphics.setComponentTooltipForNextFrame(font, lines, x, y);
    }
    //?}
}
