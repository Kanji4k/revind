package com.example.rewind;

import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

public final class SettingsScreen extends Screen {
    private final Screen parent;
    private int slot;

    public SettingsScreen(Screen parent) { super(Component.literal("Rewind Settings")); this.parent = parent; }

    private static int next(int[] a, int v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return a[(i + 1) % a.length]; return a[0]; }
    private static String onoff(boolean b) { return b ? "ON" : "OFF"; }
    private static String dur(int s) { return s < 60 ? s + " s" : s / 60 + " min"; }

    private void add(Supplier<String> label, Runnable act) {
        int x = width / 2 - 155 + (slot % 2) * 160, y = height / 6 + (slot / 2) * 24;
        slot++;
        addRenderableWidget(Button.builder(Component.literal(label.get()), b -> { act.run(); b.setMessage(Component.literal(label.get())); })
                .bounds(x, y, 150, 20).build());
    }

    @Override protected void init() {
        slot = 0;
        Config c = Config.I;
        add(() -> "Rewind: " + onoff(c.enabled), () -> c.enabled = !c.enabled);
        add(() -> "Shadow Rewind: " + onoff(c.shadow), () -> c.shadow = !c.shadow);
        add(() -> "Buffer length: " + dur(c.shadowSeconds), () -> c.shadowSeconds = next(new int[]{30, 60, 120, 300, 600}, c.shadowSeconds));
        add(() -> "Disk limit: " + c.maxMb + " MB", () -> c.maxMb = next(new int[]{256, 512, 1024, 2048, 4096}, c.maxMb));
        add(() -> "Clip length: " + c.clipSeconds + " s", () -> c.clipSeconds = next(new int[]{15, 30, 60}, c.clipSeconds));
        add(() -> "Record after press: " + c.postSeconds + " s", () -> c.postSeconds = next(new int[]{0, 5, 10, 15}, c.postSeconds));
        add(() -> "Resolution: " + c.res + "p", () -> c.res = next(new int[]{360, 720, 1080}, c.res));
        add(() -> "FPS: " + c.fps, () -> c.fps = next(new int[]{30, 60}, c.fps));
        add(() -> "Quality: " + Config.PRESET_NAME[c.preset], () -> c.preset = (c.preset + 1) % 3);
        add(() -> "Clip sound: " + ClipSaver.SND_NAME[c.sound], () -> c.sound = (c.sound + 1) % 4);
        add(() -> "Toast popup: " + onoff(c.toast), () -> c.toast = !c.toast);
        add(() -> "Keybinds…", () -> minecraft.setScreen(new KeyBindsScreen(this, minecraft.options)));
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    @Override public void render(GuiGraphics g, int mx, int my, float d) {
        super.render(g, mx, my, d);
        g.drawCenteredString(font, title, width / 2, height / 6 - 20, 0xFFFFFFFF);
    }

    @Override public void onClose() {
        Config.save();
        RewindMod.applyConfig();
        minecraft.setScreen(parent);
    }
}
