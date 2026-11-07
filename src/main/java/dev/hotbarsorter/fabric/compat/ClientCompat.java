package dev.hotbarsorter.fabric.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.Locale;

/** Keeps screen access stable across Minecraft's client GUI refactors. */
public final class ClientCompat {
    private ClientCompat() {}

    public static Screen currentScreen(Minecraft client) {
        //? if <26.2 {
        /*return client.screen;
        *///?} else {
        return client.gui.screen();
        //?}
    }

    public static void setScreen(Minecraft client, Screen screen) {
        //? if <26.2 {
        /*client.setScreen(screen);
        *///?} else {
        client.gui.setScreen(screen);
        //?}
    }

    public static String serverAddress(Minecraft client) {
        return client.getCurrentServer() == null ? "" : client.getCurrentServer().ip.toLowerCase(Locale.ROOT);
    }

    public static void showSystemMessage(Minecraft client, Component message) {
        if (client.player == null) return;
        //? if <26.1 {
        /*client.player.displayClientMessage(message, false);
        *///?} else {
        client.player.sendSystemMessage(message);
        //?}
    }
}
