package com.example.rewind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/** JSON settings: .minecraft/config/rewind.json */
public final class Config {
    public boolean enabled = true, shadow = true, toast = true;
    public boolean mic = false;                                   // phase 2
    public boolean onDeath = true, onLowHealth = false, onKill = false, onElytra = false; // phase 2
    public int shadowSeconds = 60, maxMb = 1024, clipSeconds = 30, postSeconds = 0;
    public int fps = 30, preset = 1, sound = 1;

    public static final String[] PRESET_NAME = {"Low", "Medium", "High"};
    public static final int[] PRESET_H = {480, 720, 1080}, PRESET_FPS = {20, 30, 60};
    public static final float[] PRESET_Q = {0.5f, 0.7f, 0.85f};

    public int effFps() { return Math.min(fps, PRESET_FPS[preset]); }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("rewind.json");
    public static Config I = load();

    private static Config load() {
        Config c = new Config();
        try { if (Files.exists(FILE)) { Config r = GSON.fromJson(Files.readString(FILE), Config.class); if (r != null) c = r; } }
        catch (Exception e) { RewindMod.LOG.warn("Bad rewind.json, using defaults", e); }
        c.preset = Math.max(0, Math.min(2, c.preset));
        c.fps = Math.max(5, Math.min(60, c.fps));
        c.shadowSeconds = Math.max(30, Math.min(600, c.shadowSeconds));
        c.maxMb = Math.max(64, c.maxMb);
        c.clipSeconds = Math.max(5, c.clipSeconds);
        c.postSeconds = Math.max(0, c.postSeconds);
        c.sound = Math.max(0, Math.min(3, c.sound));
        return c;
    }

    public static void save() {
        try { Files.writeString(FILE, GSON.toJson(I)); } catch (Exception e) { RewindMod.LOG.warn("Config save failed", e); }
    }
}
