package org.locationbridge.source;

public final class AuthProtocolTest {
    static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        String key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
        String nonce = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
        AuthProtocol auth = new AuthProtocol(key);
        check(AuthProtocol.fingerprint(key).equals("630dcd2966c4"), "pairing fingerprint");
        check(auth.sign("response\n" + nonce + "\n{\"protocol\":1}").equals("6519f9f7b0291427f3f87f80cd9f56ea3546526491f37f687a7503265269ea30"), "response HMAC vector");
        long deadline = 123456L;
        String startMac = auth.sign("start\n" + deadline);
        check(auth.verifyStart(deadline, 120000L, startMac), "valid start auth");
        check(!auth.verifyStart(deadline, deadline, startMac), "expired start denied");
        check(!auth.verifyStart(deadline, deadline - 9001L, startMac), "overlong deadline denied");
        check(!auth.verifyStart(deadline+1, 120000L, startMac), "modified deadline denied");
        check(!auth.verifyStart(deadline, 120000L, "0".repeat(64)), "forged start denied");
        String mac = auth.sign("request\n" + nonce + "\nsnapshot");
        check(mac.equals("e572a6031e361c9b89b437ce9a453ec461c85cd1d1ab4cc2b4be570d8b7a4636"), "known HMAC vector");
        check(!auth.verify(nonce, "snapshot", "0".repeat(64)), "bad mac denied");
        check(auth.verify(nonce, "snapshot", mac), "valid request accepted");
        check(!auth.verify(nonce, "snapshot", mac), "replay rejected");
        check(!auth.verify(nonce, "stop", auth.sign("request\n" + nonce + "\nstop")), "nonce cannot be reused for another operation");
        check(!auth.verify("not-a-nonce", "stop", mac), "invalid nonce rejected");
        for (int i=1; i<=256; i++) {
            String next = String.format(java.util.Locale.ROOT, "%064x", i);
            check(auth.verify(next, "snapshot", auth.sign("request\n" + next + "\nsnapshot")), "fresh nonce accepted");
        }
        check(auth.verify(nonce, "snapshot", mac), "oldest nonce evicted after window");
        String extraNonce="f".repeat(64);
        check(auth.verify(extraNonce,"external_snapshot",auth.sign("request\n"+extraNonce+"\nexternal_snapshot")),"external snapshot authenticated");
        System.out.println("auth protocol tests passed");
    }
}
