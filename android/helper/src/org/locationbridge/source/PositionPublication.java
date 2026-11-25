package org.locationbridge.source;

import java.util.HashMap;
import java.util.Map;

/** Publishes measured or predicted positions with a separate output sequence. */
final class PositionPublication {
    private static final long SECOND = 1_000_000_000L;
    private static final long MOTION_FRESH = 1_500_000_000L;
    private static final double REST_SMOOTHING_SECONDS = 5;
    private final GroundTrackFilter straightTrack = new GroundTrackFilter();
    private final VelocityTrackFilter velocityTrack = new VelocityTrackFilter();
    private long lastSpeedTime, stillSince;
    static final class Output {
        final Selector.Fix measured, position;
        final DeadReckoning.Prediction prediction;
        final String source;
        final long elapsed, sequence;
        final String positionFilter;
        Output(Selector.Fix measured, Selector.Fix position, DeadReckoning.Prediction prediction,
               long sequence, String positionFilter) {
            this.measured=measured; this.position=position; this.prediction=prediction; this.sequence=sequence;
            this.source=measured!=null?measured.source:"inertial";
            this.elapsed=measured!=null?measured.elapsed:prediction.elapsed;
            this.positionFilter=positionFilter;
        }
    }
    private final Map<String,Long> lastElapsed = new HashMap<>();
    private long sequence;
    private Output last;
    synchronized void clearGpsMotion() {
        stillSince=0; lastSpeedTime=0;
        straightTrack.reset(); velocityTrack.reset();
    }
    synchronized void observeGpsMotion(long elapsed, long now, double speed, double speedAccuracy,
                                       double course, double courseAccuracy) {
        if (elapsed <= lastSpeedTime || elapsed <= 0 || elapsed > now) return;
        straightTrack.observeVelocity(elapsed,speed,speedAccuracy,course,courseAccuracy,now);
        velocityTrack.observe(elapsed,now,speed,speedAccuracy,course,courseAccuracy);
        boolean quiet = now-elapsed <= MOTION_FRESH && speed >= 0 && speed <= 0.2
                && speedAccuracy >= 0 && speed+2*speedAccuracy <= 0.75;
        if (!quiet) stillSince=0;
        else if (stillSince == 0 || elapsed-lastSpeedTime > MOTION_FRESH) stillSince=elapsed;
        lastSpeedTime=elapsed;
    }
    private Output measuredOutput(Selector.Fix fix, long now) {
        double lat=fix.latitude, lon=fix.longitude;
        // Use an independent velocity measurement to distinguish rest from slow travel.
        if (stillSince>0 && lastSpeedTime-stillSince>=2*SECOND && now-lastSpeedTime<=MOTION_FRESH
                && now-fix.elapsed<=MOTION_FRESH && last!=null && last.measured!=null
                && last.source.equals(fix.source) && !"network".equals(fix.source)
                && last.elapsed>=stillSince && fix.elapsed-last.elapsed<=MOTION_FRESH
                && fix.accuracy<=50 && last.measured.accuracy<=Math.min(50,2*fix.accuracy+5)
                && Math.abs(lat)<85) {
            double dLat=lat-last.position.latitude, dLon=Math.IEEEremainder(lon-last.position.longitude,360);
            double north=111320*dLat, east=111320*dLon*Math.cos(Math.toRadians(lat));
            // A displaced cluster or an abrupt correction should start a new track segment.
            if (Math.hypot(east,north)<=Math.min(40,Math.max(25,2*(fix.accuracy+last.measured.accuracy)))) {
                double gain=-Math.expm1(-(fix.elapsed-last.elapsed)/(SECOND*REST_SMOOTHING_SECONDS));
                lat=last.position.latitude+gain*dLat;
                lon=Math.IEEEremainder(last.position.longitude+gain*dLon,360);
                Selector.Fix position=new Selector.Fix(fix.source,lat,lon,fix.accuracy,fix.elapsed,fix.mock,
                        fix.timestamp,fix.sequence);
                return new Output(fix,position,null,++sequence,"stationary");
            }
        }
        Selector.Fix previous=last==null?null:last.position;
        Selector.Fix position=velocityTrack.apply(previous,fix,now);
        if (position!=null) return new Output(fix,position,null,++sequence,position==fix?null:"velocity_track");
        position=straightTrack.apply(previous,fix,now);
        return new Output(fix,position,null,++sequence,position==fix?null:"cross_track");
    }
    synchronized Output select(Selector.Fix measured,DeadReckoning.Prediction prediction,long now) {
        if (measured!=null && (!Selector.valid(measured,now)
                || now-measured.elapsed>("fused".equals(measured.source)?5_000_000_000L:10_000_000_000L))) measured=null;
        if (prediction!=null && (prediction.elapsed>now || prediction.anchorElapsed<=0
                || now-prediction.elapsed>500_000_000L
                || prediction.elapsed-prediction.anchorElapsed<=1_500_000_000L)) prediction=null;
        if (measured!=null) prediction=null;
        if (measured==null || last!=null && !measured.source.equals(last.source)) {
            straightTrack.reset(); velocityTrack.reset();
        }
        if (measured==null && prediction==null) { last=null; return null; }
        String source=measured!=null?measured.source:"inertial";
        long elapsed=measured!=null?measured.elapsed:prediction.elapsed;
        if (last!=null && elapsed==last.elapsed && source.equals(last.source)) return last;
        if (elapsed<=lastElapsed.getOrDefault(source,0L)) return null;
        lastElapsed.put(source,elapsed);
        last=measured!=null ? measuredOutput(measured,now) :
                new Output(null,null,prediction,++sequence,null);
        return last;
    }
}
