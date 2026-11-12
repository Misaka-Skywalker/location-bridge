package org.locationbridge.source;

public final class GnssAltitudeTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        long t = 100_000_000_000L;
        GnssAltitude a = new GnssAltitude();
        check(a.update("gps", false, t, t, 30, 104, true, -25.5, true, 3.0), "negative altitude valid");
        GnssAltitude.Reading r = a.fresh(t + 10_000_000_000L);
        check(r != null && r.hasAltitude && r.altitude == -25.5 && r.verticalAccuracy == 3.0, "same sample metadata");
        check(a.fresh(t + 10_000_000_001L) == null, "expired");
        check(!a.update("gps", true, t+1, t+1, 30, 104, true, 999, true, 1), "mock rejected");
        check(!a.update("network", false, t+1, t+1, 30, 104, true, 999, true, 1), "network rejected");
        check(!a.update("gps", false, t-1, t+1, 30, 104, true, 999, true, 1), "older rejected");
        check(a.fresh(t+1).altitude == -25.5, "invalid updates do not overwrite");
        check(a.update("gps", false, t+2, t+2, 30, 104, false, 0, false, 0), "new sample without altitude accepted");
        check(a.fresh(t+2) != null && !a.fresh(t+2).hasAltitude, "missing altitude clears prior value");
        check(a.update("gps", false, t+3, t+3, 30, 104, true, Double.NaN, true, -1), "bad altitude is unavailable");
        check(!a.fresh(t+3).hasAltitude && a.fresh(t+3).verticalAccuracy == null, "nonfinite values not displayed");
        a.clear(); check(a.fresh(t+3) == null, "provider disabled clears");
        System.out.println("GNSS altitude tests passed");
    }
}
