package org.locationbridge.source;

import java.nio.charset.StandardCharsets;

/** Strict single-sentence GPTXT/GNTXT parser; malformed or binary inputs return null. */
final class NmeaTxt {
    static String parse(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 128) return null;
        int length = payload.length;
        if (length >= 2 && payload[length - 2] == '\r' && payload[length - 1] == '\n') length -= 2;
        if (length < 1) return null;
        for (int i = 0; i < length; i++) {
            int value = payload[i] & 0xff;
            if (value < 0x20 || value > 0x7e) return null;
        }
        String sentence = new String(payload, 0, length, StandardCharsets.US_ASCII);
        if (!sentence.startsWith("$GPTXT,") && !sentence.startsWith("$GNTXT,")) return null;
        int star = sentence.indexOf('*');
        if (star < 0 || star != sentence.lastIndexOf('*') || star != sentence.length() - 3) return null;
        int high = Character.digit(sentence.charAt(star + 1), 16);
        int low = Character.digit(sentence.charAt(star + 2), 16);
        if (high < 0 || low < 0) return null;
        int checksum = 0;
        for (int i = 1; i < star; i++) checksum ^= sentence.charAt(i);
        if (checksum != (high << 4 | low)) return null;
        String[] fields = sentence.substring(1, star).split(",", -1);
        if (fields.length != 5 || !"01".equals(fields[1]) || !"01".equals(fields[2])
                || !fields[3].matches("[0-9]{2}") || fields[4].isEmpty()) return null;
        return fields[4];
    }
}
