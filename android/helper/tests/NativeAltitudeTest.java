package org.locationbridge.source;

public final class NativeAltitudeTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static boolean put(NativeAltitude a, String source, long t, boolean hasAlt, double alt, boolean hasAccuracy, double accuracy) {
        return a.update(source, false, t, t, 30, 104, hasAlt, alt, hasAccuracy, accuracy);
    }
    static Selector.Fix selected(String source, long t) { return new Selector.Fix(source, 30, 104, 5, t, false, 1, 1); }
    public static void main(String[] args) {
        long t = 100_000_000_000L;
        NativeAltitude a = new NativeAltitude();
        check(put(a, "fused", t, true, -25.5, true, 4), "accept fused");
        NativeAltitude.Reading r = a.fresh(t, selected("gps", t));
        check(r != null && r.source.equals("fused") && r.altitude == -25.5 && r.verticalAccuracy == 4.0, "fallback fused");
        check(put(a, "gps", t+1, true, 200, true, 9), "accept gps");
        check(a.fresh(t+1, selected("gps", t+1)).altitude == 200, "prefer requested source");
        check(a.fresh(t+1, selected("network", t+1)).source.equals("fused"), "fallback by accuracy");
        check(put(a, "fused", t+2, true, 15, false, 0), "new fused without accuracy");
        check(a.fresh(t+2, selected("fused", t+2)).verticalAccuracy == null, "accuracy from same sample only");
        check(a.fresh(t+2, selected("network", t+2)).source.equals("gps"), "known accuracy preferred");
        check(put(a, "gps", t+3, false, 0, true, 1), "new position without altitude");
        check(a.fresh(t+3, selected("gps", t+3)).source.equals("fused"), "missing altitude clears prior gps");
        check(!put(a, "gps", t+2, true, 500, false, 0), "missing altitude still advances ordering");
        check(a.fresh(t+10_000_000_003L, selected("gps", t+10_000_000_003L)) == null, "expired height");
        check(!a.update("fused", false, t+5, t+4, 30, 104, true, 3, true, 2), "future rejected");
        check(!a.update("fused", false, t+4, t+10_000_000_005L, 30, 104, true, 3, true, 2), "stale update rejected");
        check(!a.update("fused", true, t+4, t+4, 30, 104, true, 3, true, 2), "mock rejected");
        check(!a.update("network", false, t+4, t+4, 30, 104, true, 3, true, 2), "network rejected");
        check(!a.update("fused", false, t+4, t+4, Double.NaN, 104, true, 3, true, 2), "bad coordinate rejected");
        check(put(a, "gps", t+4, true, 3, true, Double.NaN), "invalid accuracy accepted as unknown");
        check(a.fresh(t+4, selected("gps", t+4)).verticalAccuracy == null, "invalid accuracy unknown");
        a.remove("gps"); check(a.fresh(t+4, selected("gps", t+4)).source.equals("fused"), "removed source fallback");
        a.clear(); check(a.fresh(t+4, null) == null, "clear all");
        check(put(a,"gps",t+5,true,1,false,0), "new gps");
        check(put(a,"fused",t+6,true,2,false,0), "new fused");
        check(a.fresh(t+6,selected("network",t+6)).source.equals("fused"), "newest when accuracies unknown");
        check(a.fresh(t+4,selected("network",t+4)) == null, "future reading not fresh");
        NativeLocationPolicy policy = new NativeLocationPolicy();
        NativeAltitude joined = new NativeAltitude();
        Selector.Fix fusedFix = selected("fused",t);
        check(policy.offer(fusedFix,t), "policy accepts fused");
        check(put(joined,"fused",t,true,123,true,4), "fused altitude accepted");
        check(joined.fresh(t,policy.selected(t)) != null, "selected position enables height");
        check(joined.fresh(t+5_000_000_000L,selected("gps",t+5_000_000_000L)) != null, "fused five second boundary valid");
        check(joined.fresh(t+5_000_000_001L,selected("gps",t+5_000_000_001L)) == null, "fused expires after five seconds");
        check(policy.selected(t+6_000_000_000L) == null, "fused horizontal expired");
        check(joined.fresh(t+6_000_000_000L,policy.selected(t+6_000_000_000L)) == null, "no horizontal means no height");
        Selector.Fix gpsFix = selected("gps",t+6_000_000_000L);
        check(policy.offer(gpsFix,t+6_000_000_000L), "fresh gps position");
        check(joined.fresh(t+6_000_000_000L,policy.selected(t+6_000_000_000L)) == null, "expired fused altitude cannot fall back");
        check(joined.fresh(t,null) == null, "null position cannot expose height");
        System.out.println("Native altitude tests passed");
    }
}
