package com.example.rewind;

/** Adaptive quality: steps FPS -> resolution -> JPEG quality down when recording costs too much. */
final class Governor {
    private static final float[] FM = {1, .75f, .75f, .5f, .5f}, SM = {1, 1, .75f, .75f, .5f}, QS = {0, 0, .1f, .2f, .2f};
    static volatile int level;
    private static long win, capNs;
    private static int frames, caps, good;

    static void reset() { level = 0; win = 0; good = 0; }
    static int fps() { return Math.max(10, Math.round(Config.I.effFps() * FM[level])); }
    static float scale() { return SM[level]; }
    static float quality() { return Math.max(.4f, Config.PRESET_Q[Config.I.preset] - QS[level]); }
    static void captured(long ns) { capNs += ns; caps++; }

    /** Called every rendered frame (game thread). */
    static void frame(long now, boolean active) {
        if (!active) { win = 0; return; }
        if (win == 0) { restart(now); return; }
        frames++;
        long el = now - win;
        if (el < 2_000_000_000L) return;
        double ft = el / 1e6 / frames;                 // avg game frame time (ms)
        double frac = (double) capNs / el;             // share of time spent capturing on the game thread
        double drop = Encoder.drops.get() / (double) Math.max(1, Encoder.subs.get());
        int old = level;
        if ((frac > .08 || drop > .05 || (ft > 22 && frac > .03)) && level < 4) { level++; good = 0; }
        else if (frac < .03 && drop == 0 && ft < 18) { if (++good >= 5 && level > 0) { level--; good = 0; } }
        else good = 0;
        if (level != old) RewindMod.LOG.info("Rewind quality level {} (frame {}ms, capture {}%, drops {}%)",
                level, Math.round(ft), Math.round(frac * 100), Math.round(drop * 100));
        restart(now);
    }

    private static void restart(long now) { win = now; frames = caps = 0; capNs = 0; Encoder.drops.set(0); Encoder.subs.set(0); }
}
