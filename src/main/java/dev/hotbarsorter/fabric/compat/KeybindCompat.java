package dev.hotbarsorter.fabric.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
//? if <26.3 {
/*import org.lwjgl.glfw.GLFW;
*///?}
//? if <26.1 {
/*import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
*///?} else
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

public final class KeybindCompat {
    //? if <1.21.9 {
    /* */
    //?} else
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("hotbarsorter", "general"));

    private KeybindCompat() {}

    public static KeyMapping register(String translationKey, boolean defaultK) {
        //? if <26.3 {
        /*int key = defaultK ? GLFW.GLFW_KEY_K : GLFW.GLFW_KEY_UNKNOWN;
        KeyMapping mapping;
        //? if <1.21.9 {
        mapping = new KeyMapping(translationKey, InputConstants.Type.KEYSYM, key, "category.hotbarsorter.general");
        //?} else {
        mapping = new KeyMapping(translationKey, key, CATEGORY);
        //?}
        *///?} else {
        int key = defaultK ? InputConstants.KEY_K : InputConstants.UNKNOWN.getValue();
        KeyMapping mapping = new KeyMapping(translationKey, key, CATEGORY);
        //?}
        //? if <26.1 {
        /*return KeyBindingHelper.registerKeyBinding(mapping);
        *///?} else
        return KeyMappingHelper.registerKeyMapping(mapping);
    }
}
