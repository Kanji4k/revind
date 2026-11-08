package com.example.rewind;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;

/**
 * Game-thread capture. Uses two GPU readback buffers (ping-pong): frame N is copied GPU->buffer,
 * and the buffer from frame N-1 is mapped and downscaled, so the GPU never has to stall for us.
 * Compression/disk happen on the Encoder thread.
 */
public final class FrameGrabber {
    private static final GpuBuffer[] bufs = new GpuBuffer[2];
    private static int w0, h0, wi;
    private static boolean pending;
    private static long pendTs, lastNs, nextNs, off, lastErr;

    public static void onFrame() {
        long now = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        Config c = Config.I;
        boolean active = c.enabled && mc.level != null && (RewindMod.recording || c.shadow)
                && mc.getWindow().getWidth() > 0 && mc.getWindow().getHeight() > 0; // 0x0 = minimized
        Governor.frame(now, active);
        if (!active || now < nextNs) return;

        long iv = 1_000_000_000L / Governor.fps();
        long gap = now - lastNs;
        if (lastNs != 0 && gap > 1_500_000_000L) { off += gap - iv; pending = false; } // skip paused time
        lastNs = now;
        nextNs = (now - nextNs > iv) ? now + iv : nextNs + iv;

        try { grab(mc, now); }
        catch (Throwable t) {
            pending = false;
            if (now - lastErr > 5_000_000_000L) { lastErr = now; RewindMod.LOG.warn("Capture error", t); }
        }
        Governor.captured(System.nanoTime() - now);
    }

    private static void grab(Minecraft mc, long now) {
        RenderTarget rt = mc.getMainRenderTarget();
        GpuTexture tex = rt.getColorTexture();
        int w = rt.width, h = rt.height;
        if (tex == null || w < 2 || h < 2 || tex.getFormat().pixelSize() != 4) return;
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();

        if (bufs[0] == null || w != w0 || h != h0) {
            for (int i = 0; i < 2; i++) {
                if (bufs[i] != null) bufs[i].close();
                bufs[i] = RenderSystem.getDevice().createBuffer(() -> "rewind", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, w * h * 4);
            }
            w0 = w; h0 = h; pending = false;
        }
        if (pending) { pending = false; process(enc, bufs[wi ^ 1]); }
        enc.copyTextureToBuffer(tex, bufs[wi], 0, () -> {}, 0);
        pendTs = (now - off) / 1_000_000L;
        wi ^= 1;
        pending = true;
    }

    /** Nearest-neighbour downscale + vertical flip + RGBA->RGB, straight into the image's int[]. */
    private static void process(CommandEncoder enc, GpuBuffer b) {
        float s = Math.min(1f, Config.I.res * Governor.scale() / h0);
        int tw = Math.max(2, Math.round(w0 * s) & ~1), th = Math.max(2, Math.round(h0 * s) & ~1);
        BufferedImage img = Encoder.obtain(tw, th);
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int[] xm = new int[tw];
        for (int x = 0; x < tw; x++) xm[x] = (int) ((2L * x + 1) * w0 / (2L * tw));
        boolean down = w0 > tw * 1.2f; // 2x2 box filter when shrinking (smoother), plain copy otherwise
        try (GpuBuffer.MappedView mv = enc.mapBuffer(b, true, false)) {
            IntBuffer ib = mv.data().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            for (int y = 0; y < th; y++) {
                int sy = (int) ((2L * y + 1) * h0 / (2L * th));
                int ry = Math.min(h0 - 1, sy + 1);
                int row = (h0 - 1 - sy) * w0, row2 = (h0 - 1 - ry) * w0, o = y * tw;
                for (int x = 0; x < tw; x++) {
                    int sx = xm[x];
                    if (!down) { px[o + x] = ib.get(row + sx) & 0xFFFFFF; continue; }
                    int sx2 = Math.min(w0 - 1, sx + 1);
                    int a = ib.get(row + sx), bb = ib.get(row + sx2), c = ib.get(row2 + sx), d = ib.get(row2 + sx2);
                    int r = ((a & 0xFF0000) + (bb & 0xFF0000) + (c & 0xFF0000) + (d & 0xFF0000)) >> 2 & 0xFF0000;
                    int g = ((a & 0xFF00) + (bb & 0xFF00) + (c & 0xFF00) + (d & 0xFF00)) >> 2 & 0xFF00;
                    int bl = ((a & 0xFF) + (bb & 0xFF) + (c & 0xFF) + (d & 0xFF)) >> 2;
                    px[o + x] = r | g | bl;
                }
            }
        }
        Encoder.submit(img, pendTs);
    }
}
