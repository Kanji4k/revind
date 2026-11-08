package com.example.rewind;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Pure-Java JPEG encoder. NOT thread-safe: one instance per thread. */
final class Jpeg {
    private final ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
    private final ImageWriteParam p = w.getDefaultWriteParam();
    private final ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 18);

    byte[] encode(BufferedImage img, float q) throws IOException {
        bo.reset();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(q);
        try (ImageOutputStream os = new MemoryCacheImageOutputStream(bo)) {
            w.setOutput(os);
            w.write(null, new IIOImage(img, null, null), p);
        }
        return bo.toByteArray();
    }
}
