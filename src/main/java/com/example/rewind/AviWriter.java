package com.example.rewind;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;

/** Minimal AVI muxer: one MJPEG video stream (audio is added in phase 2). */
final class AviWriter implements Closeable {
    private static final int MOVI = 220;           // file offset of the 'movi' fourcc
    private static final long LIMIT = 1_900_000_000L; // classic AVI is 32-bit
    private final RandomAccessFile f;
    private final ArrayList<int[]> idx = new ArrayList<>();
    private int frames;

    AviWriter(Path p, int w, int h, int fps) throws IOException {
        f = new RandomAccessFile(p.toFile(), "rw");
        f.setLength(0);
        ByteBuffer b = le(224);
        cc(b, "RIFF"); b.putInt(0); cc(b, "AVI ");
        cc(b, "LIST"); b.putInt(192); cc(b, "hdrl");
        cc(b, "avih"); b.putInt(56);
        b.putInt(1_000_000 / fps); b.putInt(0); b.putInt(0); b.putInt(0x10); b.putInt(0); b.putInt(0);
        b.putInt(1); b.putInt(w * h * 3); b.putInt(w); b.putInt(h); b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0);
        cc(b, "LIST"); b.putInt(116); cc(b, "strl");
        cc(b, "strh"); b.putInt(56); cc(b, "vids"); cc(b, "MJPG");
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(1); b.putInt(fps); b.putInt(0); b.putInt(0);
        b.putInt(w * h * 3); b.putInt(-1); b.putInt(0);
        b.putShort((short) 0); b.putShort((short) 0); b.putShort((short) w); b.putShort((short) h);
        cc(b, "strf"); b.putInt(40);
        b.putInt(40); b.putInt(w); b.putInt(h); b.putShort((short) 1); b.putShort((short) 24);
        cc(b, "MJPG"); b.putInt(w * h * 3); b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0);
        cc(b, "LIST"); b.putInt(0); cc(b, "movi");
        f.write(b.array());
    }

    /** @return false when the 2 GB AVI limit is reached (caller should stop). */
    boolean add(byte[] d) throws IOException {
        long pos = f.getFilePointer();
        if (pos + d.length > LIMIT) return false;
        ByteBuffer h = le(8);
        cc(h, "00dc"); h.putInt(d.length);
        f.write(h.array()); f.write(d);
        if ((d.length & 1) == 1) f.write(0);
        idx.add(new int[]{(int) (pos - MOVI), d.length});
        frames++;
        return true;
    }

    @Override public void close() throws IOException {
        long end = f.getFilePointer();
        ByteBuffer ib = le(8 + 16 * idx.size());
        cc(ib, "idx1"); ib.putInt(16 * idx.size());
        for (int[] e : idx) { cc(ib, "00dc"); ib.putInt(0x10); ib.putInt(e[0]); ib.putInt(e[1]); }
        f.write(ib.array());
        patch(4, (int) (f.length() - 8));
        patch(48, frames);       // avih.dwTotalFrames
        patch(140, frames);      // strh.dwLength
        patch(216, (int) (end - MOVI)); // movi LIST size
        f.close();
    }

    private void patch(long pos, int v) throws IOException { f.seek(pos); f.write(le(4).putInt(v).array()); }
    private static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }
    private static void cc(ByteBuffer b, String s) { b.put(s.getBytes(StandardCharsets.US_ASCII)); }
}
