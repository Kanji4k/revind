package com.example.rewind;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RewindMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("rewind");
    static final Path ROOT = FabricLoader.getInstance().getGameDir().resolve("rewind");
    static volatile boolean recording;
    static volatile RingBuffer shadow, manual;
    private static KeyMapping kRec, kClip, kSave;

    @Override public void onInitializeClient() {
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
        ImageIO.setUseCache(false);
        try { Files.createDirectories(ROOT); } catch (IOException e) { LOG.warn("Cannot create {}", ROOT, e); }

        Path buf = ROOT.resolve(".buffer");
        try (var st = Files.list(buf)) { st.forEach(p -> { try (var s2 = Files.list(p)) { s2.forEach(x -> x.toFile().delete()); } catch (IOException ignored) {} p.toFile().delete(); }); }
        catch (IOException ignored) {} // leftovers from a crash
        shadow = new RingBuffer(buf.resolve("shadow"));
        applyConfig();

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("rewind", "main"));
        kRec = reg("key.rewind.record", GLFW.GLFW_KEY_F8, cat);
        kClip = reg("key.rewind.clip", GLFW.GLFW_KEY_F9, cat);
        kSave = reg("key.rewind.save", GLFW.GLFW_KEY_F10, cat);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (kRec.consumeClick()) toggleRecording(mc);
            while (kClip.consumeClick()) ClipSaver.clipLast();
            while (kSave.consumeClick()) ClipSaver.saveBuffer();
            ClipSaver.tick();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> { if (recording) stopRecording(); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { shadow.destroy(); RingBuffer m = manual; if (m != null) m.destroy(); });
    }

    private static KeyMapping reg(String name, int key, KeyMapping.Category cat) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, InputConstants.Type.KEYSYM, key, cat));
    }

    /** Apply settings changes (called on init and when the settings screen closes). */
    static void applyConfig() {
        Config c = Config.I;
        shadow.maxMs = c.shadowSeconds * 1000L;
        shadow.maxBytes = c.maxMb * 1048576L;
        if (!(c.enabled && c.shadow)) shadow.clear();
        Governor.reset();
    }

    static void toggleRecording(Minecraft mc) {
        if (!Config.I.enabled) { ClipSaver.msg("Rewind is OFF"); return; }
        if (recording) { stopRecording(); return; }
        if (mc.level == null) return;
        RingBuffer m = new RingBuffer(ROOT.resolve(".buffer/manual-" + System.currentTimeMillis()));
        m.maxBytes = 1_700_000_000L; // keeps the AVI under its 2 GB limit (oldest part is dropped beyond that)
        manual = m; recording = true;
        ClipSaver.msg("● Recording started");
    }

    static void stopRecording() {
        recording = false;
        RingBuffer m = manual; manual = null;
        if (m != null) { ClipSaver.msg("Saving recording…"); ClipSaver.finishManual(m); }
    }
}
