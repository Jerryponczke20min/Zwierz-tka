package com.plushiemod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class PlushieMod implements ClientModInitializer {
    public static final String MOD_ID = "plushiemod";

    private static KeyBinding openKey;
    private static KeyBinding plushKey;
    private static KeyBinding hayKey;

    private static int danceTicks = 0;
    private static int danceAge = 0;
    private static boolean prevUse = false;

    @Override
    public void onInitializeClient() {
        PlushConfig.get();
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.plushiemod.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.plushiemod"));
        plushKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.plushiemod.place_plush", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.plushiemod"));
        hayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.plushiemod.place_hay", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.plushiemod"));

        ClientTickEvents.START_CLIENT_TICK.register(PlushieMod::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(PlushieMod::endTick);
    }

    public static KeyBinding openKey() {
        return openKey;
    }

    // ---------------- dance state ----------------
    public static boolean isDancing() {
        return danceTicks > 0;
    }

    public static int danceAge() {
        return danceAge;
    }

    public static void startDance() {
        danceTicks = Math.max(20, PlushConfig.get().danceSeconds * 20);
        danceAge = 0;
    }

    public static void stopDance() {
        danceTicks = 0;
    }

    public static void toggleDance() {
        if (isDancing()) stopDance();
        else startDance();
    }

    // ---------------- ticks ----------------
    private static void startTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            prevUse = false;
            return;
        }
        if (mc.currentScreen != null) {
            prevUse = false;
            return;
        }

        while (openKey.wasPressed()) mc.setScreen(new PlushScreen());
        while (plushKey.wasPressed()) PlushManager.placePlush(mc, true);
        while (hayKey.wasPressed()) PlushManager.placeDecoy(mc);

        // Right-click on a (client-only) plush: eat the click so vanilla never sees it.
        boolean use = mc.options.useKey.isPressed();
        if (use && !prevUse) {
            PlushManager.Plush hit = PlushManager.pick(mc);
            if (hit != null) {
                while (mc.options.useKey.wasPressed()) {
                    // swallow
                }
                toggleDance();
            }
        }
        prevUse = use;
    }

    private static void endTick(MinecraftClient mc) {
        PlushConfig cfg = PlushConfig.get();
        ClientPlayerEntity p = mc.player;
        if (p != null && mc.world != null) {
            if (danceTicks > 0) {
                danceTicks--;
                danceAge++;
                if (cfg.playerHop && p.isOnGround() && danceAge % 9 == 0) {
                    Vec3d v = p.getVelocity();
                    p.setVelocity(v.x, 0.3, v.z);
                }
                if (cfg.playerSpin) p.setYaw(p.getYaw() + 6f);
            } else {
                danceAge = 0;
            }
        } else {
            danceTicks = 0;
            danceAge = 0;
        }
        MobManager.tick(mc);
        PlushManager.tick(mc);
    }
}
