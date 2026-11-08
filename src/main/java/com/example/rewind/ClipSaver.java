package com.example.rewind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/** Exports clips on a background thread; shows toast + sound when done. */
final class ClipSaver {
    static final String[] SND_NAME = {"None", "Orb", "Level Up", "Toast"};
    private record Pending(long from, long end, long deadline) {}
    private static final List<Pending> pend = new ArrayList<>();
    private static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Rewind-Export"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY + 1); return t;
    });

    /** Medal-style: last clipSeconds (+ postSeconds after the press). */
    static void clipLast() { queue(Config.I.clipSeconds * 1000L); }

    /** Whole shadow buffer. */
    static void saveBuffer() { queue(-1); }

    private static void queue(long backMs) {
        Config c = Config.I; RingBuffer r = RewindMod.shadow;
        if (!c.enabled || !c.shadow) { msg("Shadow Rewind is off (enable it in settings)"); return; }
        long press = r.lastTs();
        if (press == Long.MIN_VALUE) { msg("Nothing buffered yet"); return; }
        long from = backMs < 0 ? Long.MIN_VALUE : press - backMs;
        long post = c.postSeconds * 1000L;
        synchronized (pend) { pend.add(new Pending(from, press + post, System.currentTimeMillis() + post + 3000)); }
        if (post > 0) msg("Clipping… +" + c.postSeconds + "s");
    }

    /** Game thread, every tick: fire clips whose post-roll is done. */
    static void tick() {
        synchronized (pend) {
            for (Iterator<Pending> it = pend.iterator(); it.hasNext(); ) {
                Pending p = it.next();
                if (RewindMod.shadow.lastTs() >= p.end() || System.currentTimeMillis() >= p.deadline()) {
                    it.remove();
                    export(RewindMod.shadow, p.from(), p.end(), "clip", false);
                }
            }
        }
    }

    static void finishManual(RingBuffer m) { export(m, Long.MIN_VALUE, Long.MAX_VALUE, "rec", true); }

    private static void export(RingBuffer r, long from, long to, String prefix, boolean destroy) {
        int fps = Config.I.effFps();
        EX.execute(() -> {
            try {
                Path out = nextFile(prefix);
                int secs = r.export(from, to, out, fps);
                done("Saved " + out.getFileName() + " (" + secs + "s)", true);
            } catch (Throwable t) {
                RewindMod.LOG.warn("Export failed", t);
                done("Save failed: " + t.getMessage(), false);
            } finally { if (destroy) r.destroy(); }
        });
    }

    private static Path nextFile(String prefix) throws java.io.IOException {
        Files.createDirectories(RewindMod.ROOT);
        String base = prefix + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path p = RewindMod.ROOT.resolve(base + ".avi");
        for (int i = 2; Files.exists(p); i++) p = RewindMod.ROOT.resolve(base + "_" + i + ".avi");
        return p;
    }

    private static void done(String text, boolean ok) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (Config.I.toast) SystemToast.addOrUpdate(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal(ok ? "Clip saved" : "Rewind"), Component.literal(text));
            else msg(text);
            if (ok) sound(mc);
        });
    }

    private static void sound(Minecraft mc) {
        SoundEvent e = switch (Config.I.sound) {
            case 1 -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case 2 -> SoundEvents.PLAYER_LEVELUP;
            case 3 -> SoundEvents.UI_TOAST_IN;
            default -> null;
        };
        if (e != null) mc.getSoundManager().play(SimpleSoundInstance.forUI(e, 1.0f, 1.0f));
    }

    static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Rewind] " + s), true);
        else RewindMod.LOG.info(s);
    }
}
