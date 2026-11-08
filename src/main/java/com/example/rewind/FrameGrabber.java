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
        float s = Math.min(1f, Config.PRESET_H[Config.I.preset] * Governor.scale() / h0);
        int tw = Math.max(2, Math.round(w0 * s) & ~1), th = Math.max(2, Math.round(h0 * s) & ~1);
        BufferedImage img = Encoder.obtain(tw, th);
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int[] xm = new int[tw];
        for (int x = 0; x < tw; x++) xm[x] = (int) ((2L * x + 1) * w0 / (2L * tw));
        try (GpuBuffer.MappedView mv = enc.mapBuffer(b, true, false)) {
            IntBuffer ib = mv.data().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            for (int y = 0; y < th; y++) {
                int sy = (int) ((2L * y + 1) * h0 / (2L * th));
                int row = (h0 - 1 - sy) * w0, o = y * tw;
                for (int x = 0; x < tw; x++) {
                    int v = ib.get(row + xm[x]); // bytes R,G,B,A -> little-endian int 0xAABBGGRR
                    px[o + x] = ((v & 0xFF) << 16) | (v & 0xFF00) | ((v >>> 16) & 0xFF);
                }
            }
        }
        Encoder.submit(img, pendTs);
    }
}
