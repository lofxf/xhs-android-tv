package io.github.lofxf.tvsource.xhs;

import android.util.Base64;

/** Builds the x-s-common header expected next to x-s / x-t. */
final class XsCommon {

    private static final String STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String XHS = "ZmserbBoHQtNP+wOcza/LpngG8yJq42KWYj0DSfdikx3VT16IlUAFM97hECvuRX5";
    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            CRC_TABLE[i] = c;
        }
    }

    private XsCommon() {}

    static String build(String a1, String b1, String xs, String xt) {
        String json = "{\"s0\":3,\"s1\":\"\",\"x0\":\"1\",\"x1\":\"3.7.8-2\",\"x2\":\"Mac OS\","
                + "\"x3\":\"xhs-pc-web\",\"x4\":\"4.27.2\","
                + "\"x5\":" + str(a1) + ",\"x6\":" + str(xt) + ",\"x7\":" + str(xs) + ",\"x8\":" + str(b1)
                + ",\"x9\":" + mrc(xt + xs + b1) + ",\"x10\":154}";
        return encode(json);
    }

    /**
     * CRC32 variant used by the website (over the first 57 characters). The final value is not
     * wrapped to 32 bits (it matches JS/Python big-number semantics), hence the long result.
     */
    static long mrc(String e) {
        int o = -1;
        int n = Math.min(57, e.length());
        for (int i = 0; i < n; i++) o = CRC_TABLE[(o & 255) ^ (e.charAt(i) & 255)] ^ (o >>> 8);
        return ((long) o & 0xFFFFFFFFL) ^ -1L ^ 3988292384L;
    }

    /** Base64 with the website's shuffled alphabet. */
    static String encode(String s) {
        try {
            String std = Base64.encodeToString(s.getBytes("UTF-8"), Base64.NO_WRAP);
            StringBuilder sb = new StringBuilder(std.length());
            for (int i = 0; i < std.length(); i++) {
                char c = std.charAt(i);
                int idx = STD.indexOf(c);
                sb.append(idx >= 0 ? XHS.charAt(idx) : c);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String str(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }
}
