package com.plushiemod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** All settings, saved to config/plushie-party.json */
public class PlushConfig {
    public static class MobEntry {
        public int count = 1;
        public boolean baby = false;
        public float scale = 1.0f;
    }

    // ---- mobs ----
    public boolean mobsEnabled = true;
    public int radius = 8;
    public float wanderSpeed = 1.0f;
    public Map<String, MobEntry> mobs = new LinkedHashMap<>();
    /** -2 = jeb_ rainbow, -1 = random, 0..15 = dye id */
    public int sheepColor = -2;

    // ---- plush ----
    public String plushItem = "minecraft:stick";
    public float plushScale = 1.0f;
    public float hopHeight = 0.6f;
    public int danceSeconds = 8;
    public boolean playerHop = true;
    public boolean playerSpin = false;

    // ---- hay ----
    public String feedItem = "minecraft:wheat";
    public String decoyItem = "minecraft:hay_block";
    public int temptRadius = 14;

    // ---- style ----
    public float hue = 0.93f;
    public float sat = 0.45f;
    public float bri = 1.0f;
    public boolean rainbowUi = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PlushConfig instance;

    public static PlushConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("plushie-party.json");
    }

    private static PlushConfig load() {
        PlushConfig cfg = null;
        try {
            Path p = path();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    cfg = GSON.fromJson(r, PlushConfig.class);
                }
            }
        } catch (Exception ignored) {
        }
        if (cfg == null) {
            cfg = new PlushConfig();
            MobEntry sheep = new MobEntry();
            sheep.count = 8;
            sheep.baby = true;
            cfg.mobs.put("minecraft:sheep", sheep);
            MobEntry chicken = new MobEntry();
            chicken.count = 3;
            cfg.mobs.put("minecraft:chicken", chicken);
        }
        if (cfg.mobs == null) cfg.mobs = new LinkedHashMap<>();
        return cfg;
    }

    public void save() {
        try {
            try (Writer w = Files.newBufferedWriter(path())) {
                GSON.toJson(this, w);
            }
        } catch (Exception ignored) {
        }
    }
}
