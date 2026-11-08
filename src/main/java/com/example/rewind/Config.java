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
    public int fps = 30, res = 720, preset = 2, sound = 1; // res = video height; preset = JPEG quality

    public static final String[] PRESET_NAME = {"Low", "Medium", "High"};
    public static final float[] PRESET_Q = {0.6f, 0.8f, 0.92f};

    public int effFps() { return fps; }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("rewind.json");
    public static Config I = load();

    private static Config load() {
        Config c = new Config();
        try { if (Files.exists(FILE)) { Config r = GSON.fromJson(Files.readString(FILE), Config.class); if (r != null) c = r; } }
        catch (Exception e) { RewindMod.LOG.warn("Bad rewind.json, using defaults", e); }
        c.preset = Math.max(0, Math.min(2, c.preset));
        c.fps = c.fps >= 60 ? 60 : 30;
        c.res = c.res >= 1080 ? 1080 : c.res >= 720 ? 720 : 360;
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
