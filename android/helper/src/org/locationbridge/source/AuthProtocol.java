package org.locationbridge.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class AuthProtocol {
    private final byte[] key;
    private final LinkedHashMap<String, Boolean> recent = new LinkedHashMap<String, Boolean>();
    static boolean isHex64(String value) { return value != null && value.matches("[0-9a-fA-F]{64}"); }
    static byte[] decode(String value) {
        byte[] result = new byte[value.length()/2];
        for (int i = 0; i < result.length; i++) result[i] = (byte)Integer.parseInt(value.substring(i*2, i*2+2), 16);
        return result;
    }
    static String fingerprint(String token) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(decode(token));
        StringBuilder out = new StringBuilder();
        for (int i=0; i<6; i++) out.append(String.format(java.util.Locale.ROOT, "%02x", hash[i] & 0xff));
        return out.toString();
    }
    AuthProtocol(String token) {
        if (!isHex64(token)) throw new IllegalArgumentException("invalid pairing token");
        key = decode(token);
    }
    String sign(String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder(digest.length*2);
        for (byte b : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return text.toString();
    }
    boolean verifyStart(long deadline, long now, String signature) throws Exception {
        if (deadline <= now || deadline - now > 9000 || !isHex64(signature)) return false;
        return MessageDigest.isEqual(decode(sign("start\n" + deadline)), decode(signature));
    }
    synchronized boolean verify(String nonce, String operation, String signature) throws Exception {
        if (!isHex64(nonce) || !isHex64(signature) || !("snapshot".equals(operation) || "external_snapshot".equals(operation) || "activate".equals(operation) || "stop".equals(operation))) return false;
        byte[] actual = decode(signature);
        byte[] expected = decode(sign("request\n" + nonce + "\n" + operation));
        if (!MessageDigest.isEqual(expected, actual)) return false;
        String nonceKey = nonce.toLowerCase(java.util.Locale.ROOT);
        if (recent.containsKey(nonceKey)) return false;
        recent.put(nonceKey, Boolean.TRUE);
        if (recent.size() > 256) recent.remove(recent.keySet().iterator().next());
        return true;
    }
}
