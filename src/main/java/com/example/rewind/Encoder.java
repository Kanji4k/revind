package com.example.rewind;

import java.awt.image.BufferedImage;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Background thread: JPEG-compress frames and append them to the ring buffers. */
final class Encoder {
    record Frame(BufferedImage img, long ts) {}

    static final ArrayBlockingQueue<Frame> Q = new ArrayBlockingQueue<>(3); // small on purpose: full = drop frame
    static final AtomicInteger drops = new AtomicInteger(), subs = new AtomicInteger();
    private static final ConcurrentLinkedQueue<BufferedImage> pool = new ConcurrentLinkedQueue<>();
    private static long lastErr;

    static {
        Thread t = new Thread(Encoder::run, "Rewind-Encoder");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 2);
        t.start();
    }

    static BufferedImage obtain(int w, int h) {
        BufferedImage b = pool.poll();
        return b != null && b.getWidth() == w && b.getHeight() == h ? b : new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    }

    static void submit(BufferedImage img, long ts) {
        subs.incrementAndGet();
        if (!Q.offer(new Frame(img, ts))) { drops.incrementAndGet(); pool.offer(img); }
    }

    private static void run() {
        Jpeg jpeg = new Jpeg();
        while (true) {
            try {
                Frame f = Q.take();
                Config c = Config.I;
                RingBuffer s = RewindMod.shadow, m = RewindMod.manual;
                boolean toShadow = c.enabled && c.shadow, toManual = RewindMod.recording && m != null;
                if (toShadow || toManual) {
                    byte[] d = jpeg.encode(f.img(), Governor.quality());
                    int w = f.img().getWidth(), h = f.img().getHeight();
                    if (toManual) m.add(f.ts(), w, h, d);
                    if (toShadow) s.add(f.ts(), w, h, d);
                }
                if (pool.size() < 6) pool.offer(f.img());
            } catch (Throwable t) {
                long now = System.currentTimeMillis();
                if (now - lastErr > 5000) { lastErr = now; RewindMod.LOG.warn("Encoder error", t); }
            }
        }
    }
}
