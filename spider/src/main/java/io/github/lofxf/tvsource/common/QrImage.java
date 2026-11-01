package io.github.lofxf.tvsource.common;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.util.EnumMap;
import java.util.Map;

public final class QrImage {
    private QrImage() {}

    /**
     * Renders {@code text} as a PNG QR code laid out for a TVBox poster: the hosts show posters in
     * a ~3:4 portrait card with center-crop and overlay the title / remarks on the bottom, so the
     * code is drawn full-width in the top part of a 3:4 white canvas and the bottom is left blank.
     */
    public static byte[] poster(String text, int width) throws Exception {
        int height = width * 4 / 3;
        int qrSize = width * 92 / 100;
        int left = (width - qrSize) / 2;
        int top = left;

        Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L); // fewer, bigger modules
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, qrSize, qrSize, hints);

        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        int mw = Math.min(m.getWidth(), width - left), mh = Math.min(m.getHeight(), height - top);
        for (int y = 0; y < mh; y++) {
            int row = (top + y) * width + left;
            for (int x = 0; x < mw; x++) if (m.get(x, y)) pixels[row + x] = 0xFF000000;
        }
        Bitmap bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        bmp.recycle();
        return bos.toByteArray();
    }
}
