package com.dkalarm.app.core;

import java.nio.ByteBuffer;

/** Copies only real image pixels. GPU row padding is not part of the screenshot. */
public final class RgbaFrame {
    private RgbaFrame() {}
    public static int[] argb(ByteBuffer buffer, int rowStride, int pixelStride,
                             int left, int top, int width, int height) {
        if (width <= 0 || height <= 0 || (long)width * height > 16_000_000 ||
            left < 0 || top < 0 || pixelStride < 4 || rowStride < (long)(left + width) * pixelStride)
            throw new IllegalArgumentException("Invalid frame dimensions or strides");
        ByteBuffer b = buffer.duplicate();
        int start = b.position();
        long last = start + (long)(top + height - 1) * rowStride + (long)(left + width - 1) * pixelStride + 3;
        if (last >= b.limit()) throw new IllegalArgumentException("Incomplete frame buffer");
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int offset = start + (top + y) * rowStride + left * pixelStride;
            for (int x = 0; x < width; x++, offset += pixelStride) {
                int r = b.get(offset) & 255, g = b.get(offset + 1) & 255, blue = b.get(offset + 2) & 255;
                pixels[y * width + x] = 0xff000000 | (r << 16) | (g << 8) | blue;
            }
        }
        return pixels;
    }
}
