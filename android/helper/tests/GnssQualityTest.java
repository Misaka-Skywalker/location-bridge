package org.locationbridge.source;

public final class GnssQualityTest {
    static void check(boolean value, String msg) { if (!value) throw new AssertionError(msg); }
    public static void main(String[] args) {
        GnssQuality q = new GnssQuality();
        q.add(1, 7, true, 35, 1575420000d);
        q.add(1, 7, true, 40, 1176450000d);
        q.add(1, 8, false, Double.NaN, Double.NaN);
        q.add(6, 7, true, 25, 1575420000d);
        GnssQuality.Summary s = q.finish(100);
        check(s.visible == 3 && s.used == 2, "deduplicate by constellation and SVID");
        check(s.signals == 4 && s.cn0Count == 3, "finite C/N0 only");
        check(s.bands.get("gps_l1") == 1 && s.bands.get("gps_l5") == 1, "separate signals");
        check(s.bands.get("galileo_e1") == 1 && s.bands.get("unknown") == 1, "constellation-specific bands");
        check(s.fresh(100 + 10_000_000_000L) && !s.fresh(100 + 10_000_000_001L), "freshness bound");
        check(!s.fresh(99), "future snapshot rejected");
        GnssQuality empty = new GnssQuality();
        empty.add(-1, -1, false, -5, -1);
        GnssQuality.Summary bad = empty.finish(1);
        check(bad.visible == 0 && bad.cn0Count == 0 && bad.bands.get("unknown") == 1, "invalid observations excluded");
        check(GnssQuality.validTime(1_000_000_000L, 1_500_000_000L, 0), "valid hardware timestamp");
        check(!GnssQuality.validTime(1_600_000_000L, 1_500_000_000L, 0), "future rejected");
        check(!GnssQuality.validTime(1, 2_000_000_002L, 0), "old event rejected");
        check(!GnssQuality.validTime(100, 101, 100), "duplicate or backwards rejected");
        System.out.println("GNSS quality tests passed");
    }
}
