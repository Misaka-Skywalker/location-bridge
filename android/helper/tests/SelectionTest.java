package org.locationbridge.source;

public final class SelectionTest {
    static final long S = 1_000_000_000L;
    static Selector.Fix fix(String source, double accuracy, long time) {
        return new Selector.Fix(source, 30, 104, accuracy, time, false, 1, 1);
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        long now = 100 * S;
        Selector.Fix fused = fix("fused", 80, now - 5*S);
        Selector.Fix gps = fix("gps", 10, now - 4*S);
        Selector.Fix net = fix("network", 5, now - 3*S);
        check(Selector.choose(fused, gps, net, now) == fused, "fresh fused priority at boundary");
        check(Selector.choose(fix("fused",1,now-5*S-1), gps, net, now) == net, "stale fused falls back");
        check(Selector.choose(null, fix("gps", 5, now - 9*S), fix("network", 5, now - 8*S), now).source.equals("network"), "tie uses newer");
        check(Selector.choose(null, fix("gps", 1, now - 10*S), null, now) != null, "10s boundary");
        check(Selector.choose(null, fix("gps", 1, now - 10*S - 1), null, now) == null, "expired");
        check(Selector.choose(fix("fused", 1, now+1), null, null, now) == null, "future rejected");
        check(!Selector.valid(new Selector.Fix("gps", Double.NaN, 0, 1, now, false, 1, 1),now), "nan latitude");
        check(!Selector.valid(new Selector.Fix("gps", 0, 181, 1, now, false, 1, 1),now), "longitude bounds");
        check(!Selector.valid(new Selector.Fix("gps", 0, 0, -1, now, false, 1, 1),now), "negative accuracy");
        check(!Selector.valid(new Selector.Fix("gps", 0, 0, Double.POSITIVE_INFINITY, now, false, 1, 1),now), "nonfinite accuracy");
        check(!Selector.valid(new Selector.Fix("gps", 0, 0, 1, now, true, 1, 1),now), "mock rejected");
        System.out.println("selection tests passed");
    }
}
