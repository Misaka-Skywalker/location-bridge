package org.locationbridge.source;

public final class GroundPositionTest {
    private static final long S = 1_000_000_000L;
    private static final double METRES_PER_DEGREE = 111319.49;

    private static Selector.Fix point(String source, long time, double east) {
        return new Selector.Fix(source, 0, east / METRES_PER_DEGREE, 6, time, false, 1000, time);
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        PositionPublication publication = new PositionPublication();
        double previous = 0, maxStep = 0;
        long time = 100*S;
        for (int i = 0; i < 45; i++, time += S) {
            double east = (i % 2 == 0 ? 10 : -10);
            Selector.Fix raw = point("gps", time, east);
            publication.observeGpsMotion(time, time, 0.04, 0.12,Double.NaN,Double.NaN);
            PositionPublication.Output out = publication.select(raw, null, time);
            double published = out.position.longitude * METRES_PER_DEGREE;
            if (i >= 15) maxStep = Math.max(maxStep, Math.abs(published - previous));
            check(out.measured == raw && out.elapsed == time && out.sequence == i+1, "preserve raw fix and time");
            if (i>=2) check("stationary".equals(out.positionFilter), "flag the processed coordinates");
            check(publication.select(raw, null, time+S/4) == out, "polls cannot move the output");
            previous = published;
        }
        check(maxStep < 3, "single-source stationary oscillation: " + maxStep);
        System.out.printf(java.util.Locale.ROOT, "Stationary single-provider replay: raw 20 m, filtered max step %.2f m after settling%n", maxStep);

        Selector.Fix moving = point("gps", time, 11);
        publication.observeGpsMotion(time, time, 0.6, 0.1,Double.NaN,Double.NaN);
        check(publication.select(moving, null, time).position.longitude == moving.longitude, "first walking-speed fix unfiltered");
        for (int i = 1; i <= 6; i++) {
            long now = time + i*S;
            Selector.Fix raw = point("fused", now, i);
            publication.observeGpsMotion(now, now, 0, 0.1,Double.NaN,Double.NaN);
            publication.select(raw, null, now);
        }
        long now = time + 7*S;
        Selector.Fix gps = point("gps", now, 24);
        check(publication.select(gps, null, now).position.longitude == gps.longitude, "handover does not mix providers");
        Selector.Fix staleSpeed = point("gps", now+S, 12);
        check(publication.select(staleSpeed, null, now+S).position.longitude == staleSpeed.longitude, "expired speed passes raw fix");
        check(publication.select(null, null, now+2*S) == null, "filter does not coast");
        for (int i=0; i<4; i++) {
            long tick = now+(3+i)*S;
            publication.observeGpsMotion(tick, tick, 0, Double.NaN,Double.NaN,Double.NaN);
            Selector.Fix raw = point("fused", tick, i);
            check(publication.select(raw, null, tick).position.longitude == raw.longitude, "unknown speed accuracy passes raw fix");
        }
        PositionPublication crossing=new PositionPublication();
        for (int i=0;i<15;i++) {
            long tick=now+i*S;
            crossing.observeGpsMotion(tick,tick,0,0.1,Double.NaN,Double.NaN);
            double lon=i%2==0?179.99991:-179.99991;
            Selector.Fix raw=new Selector.Fix("gps",0,lon,6,tick,false,1000,tick);
            PositionPublication.Output output=crossing.select(raw,null,tick);
            check(Math.abs(output.position.longitude)>179.9998, "stay near antimeridian");
        }
        long tick=now+15*S;
        crossing.observeGpsMotion(tick,tick,0,0.1,Double.NaN,Double.NaN);
        Selector.Fix corrected=point("gps",tick,0);
        check(crossing.select(corrected,null,tick).position==corrected, "large correction immediately visible");

        PositionPublication straight=new PositionPublication();
        for (int i=0;i<25;i++) {
            tick=now+i*S;
            double north=i*6, east=i%2==0?10:-10;
            straight.observeGpsMotion(tick,tick,6,0.1,0,1);
            Selector.Fix raw=new Selector.Fix("gps",north/METRES_PER_DEGREE,east/METRES_PER_DEGREE,6,tick,false,1000,tick);
            PositionPublication.Output output=straight.select(raw,null,tick);
            if (i>=15) {
                check("cross_track".equals(output.positionFilter), "straight track reaches publication");
                check(Math.abs(output.position.longitude*METRES_PER_DEGREE)<5, "reduce cross-track error");
                check(Math.abs((output.position.latitude-raw.latitude)*METRES_PER_DEGREE)<0.01, "preserve along-track displacement");
            }
        }
    }
}
