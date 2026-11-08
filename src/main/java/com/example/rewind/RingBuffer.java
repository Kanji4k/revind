package com.example.rewind;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Disk-backed rolling buffer of JPEG frames, stored in ~5 s segment files.
 * Record = [long ts ms][u16 w][u16 h][int len][jpeg]. maxMs/maxBytes of 0 = unlimited.
 */
final class RingBuffer {
    private static final long SEG_MS = 5000;
    private static final int HDR = 16;

    private static final class Seg { Path f; long start, end, bytes; boolean dead; int pin; }
    private record Ent(Path f, long off, long ts, int w, int h, int len) {}

    final Path dir;
    volatile long maxMs, maxBytes;
    private final ArrayDeque<Seg> segs = new ArrayDeque<>();
    private Seg cur;
    private DataOutputStream out;
    private long total, lastTs = Long.MIN_VALUE;
    private int counter;
    private boolean closed;

    RingBuffer(Path dir) { this.dir = dir; clear(); }

    synchronized long lastTs() { return lastTs; }

    synchronized void clear() {
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        out = null; cur = null; total = 0; lastTs = Long.MIN_VALUE;
        for (Seg s : segs) kill(s);
        segs.clear();
        try { Files.createDirectories(dir); try (var st = Files.list(dir)) { st.forEach(p -> p.toFile().delete()); } }
        catch (IOException ignored) {}
    }

    synchronized void destroy() { clear(); closed = true; try { Files.deleteIfExists(dir); } catch (IOException ignored) {} }

    synchronized void add(long ts, int w, int h, byte[] d) throws IOException {
        if (closed) return;
        if (cur == null || ts - cur.start >= SEG_MS) rotate(ts);
        out.writeLong(ts); out.writeShort(w); out.writeShort(h); out.writeInt(d.length); out.write(d);
        long sz = HDR + d.length;
        cur.bytes += sz; cur.end = ts; total += sz; lastTs = ts;
        evict();
    }

    private void rotate(long ts) throws IOException {
        if (out != null) out.close();
        cur = new Seg(); cur.f = dir.resolve("s" + (counter++) + ".seg"); cur.start = cur.end = ts;
        out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(cur.f), 1 << 18));
        segs.addLast(cur);
    }

    private void evict() {
        while (segs.size() > 1) {
            Iterator<Seg> it = segs.iterator(); Seg o = it.next(), nx = it.next();
            boolean time = maxMs > 0 && lastTs - nx.start >= maxMs;
            boolean size = maxBytes > 0 && total > maxBytes;
            if (!time && !size) break;
            segs.pollFirst(); total -= o.bytes; kill(o);
        }
    }

    private void kill(Seg s) { if (s.pin > 0) s.dead = true; else try { Files.deleteIfExists(s.f); } catch (IOException ignored) {} }

    /** Writes frames with from<=ts<=to as an AVI. Returns clip length in seconds. Runs on a background thread. */
    int export(long from, long to, Path outFile, int fps) throws IOException {
        List<Seg> pinned = new ArrayList<>(); List<Long> lens = new ArrayList<>();
        synchronized (this) {
            if (out != null) out.flush();
            for (Seg s : segs) if (s.end >= from && s.start <= to) { s.pin++; pinned.add(s); lens.add(s.bytes); }
        }
        try {
            List<Ent> es = new ArrayList<>();
            for (int i = 0; i < pinned.size(); i++) {
                Seg s = pinned.get(i); long len = lens.get(i), pos = 0;
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(s.f), 1 << 16))) {
                    while (pos + HDR <= len) {
                        long ts = in.readLong(); int w = in.readUnsignedShort(), h = in.readUnsignedShort(), l = in.readInt();
                        if (pos + HDR + l > len) break;
                        if (ts >= from && ts <= to) es.add(new Ent(s.f, pos + HDR, ts, w, h, l));
                        in.skipNBytes(l); pos += HDR + l;
                    }
                }
            }
            if (es.isEmpty()) throw new IOException("No frames buffered");
            int w = es.get(es.size() - 1).w(), h = es.get(es.size() - 1).h();
            long t0 = es.get(0).ts(), t1 = es.get(es.size() - 1).ts();
            int ticks = (int) ((t1 - t0) * fps / 1000) + 1;
            Jpeg jp = null; Path rp = null; RandomAccessFile rf = null;
            try (AviWriter av = new AviWriter(outFile, w, h, fps)) {
                int j = 0; byte[] data = null;
                for (int i = 0; i < ticks; i++) {
                    long t = t0 + i * 1000L / fps; int k = j;
                    while (k + 1 < es.size() && es.get(k + 1).ts() <= t) k++;
                    if (data == null || k != j) {
                        j = k; Ent e = es.get(j);
                        if (!e.f().equals(rp)) { if (rf != null) rf.close(); rf = new RandomAccessFile(e.f().toFile(), "r"); rp = e.f(); }
                        data = new byte[e.len()]; rf.seek(e.off()); rf.readFully(data);
                        if (e.w() != w || e.h() != h) { // resolution changed mid-clip (adaptive quality)
                            if (jp == null) jp = new Jpeg();
                            data = rescale(jp, data, w, h);
                        }
                    }
                    if (!av.add(data)) break;
                }
            } finally { if (rf != null) rf.close(); }
            return ticks / fps;
        } finally {
            synchronized (this) { for (Seg s : pinned) { s.pin--; if (s.dead && s.pin == 0) kill(s); } }
        }
    }

    private static byte[] rescale(Jpeg jp, byte[] d, int w, int h) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(d));
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null); g.dispose();
        return jp.encode(dst, 0.8f);
    }
}
