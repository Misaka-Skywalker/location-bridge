package org.locationbridge.source;

import java.util.ArrayDeque;

/** Short bounded horizontal propagation, not GNSS, navigation integrity, or a confidence interval. */
final class DeadReckoning {
    private static final long S=1_000_000_000L;
    private static final long GNSS_FRESH=1_500_000_000L, MAX_HORIZON=8*S;
    private static final long MAX_GAP=250_000_000L, MAX_SENSOR_AGE=500_000_000L;
    private static final double MAX_SPEED=450, MAX_ACCEL=15, MAX_UNCERTAINTY=150;
    private static final double WGS84_A=6378137.0, WGS84_E2=6.6943799901413165e-3;

    static final class Prediction {
        final double latitude,longitude,uncertaintyMeters,speedMetersPerSecond,bearingDegrees;
        final long elapsed,anchorElapsed,timestampMillis;
        final double predictionAgeMillis;
        Prediction(double latitude,double longitude,double uncertaintyMeters,double speedMetersPerSecond,
                   double bearingDegrees,long elapsed,long anchorElapsed,long timestampMillis,double predictionAgeMillis) {
            this.latitude=latitude; this.longitude=longitude; this.uncertaintyMeters=uncertaintyMeters;
            this.speedMetersPerSecond=speedMetersPerSecond; this.bearingDegrees=bearingDegrees;
            this.elapsed=elapsed; this.anchorElapsed=anchorElapsed; this.timestampMillis=timestampMillis;
            this.predictionAgeMillis=predictionAgeMillis;
        }
    }
    private static final class Frame {
        final long time; final double east,north;
        Frame(long time,double east,double north) { this.time=time; this.east=east; this.north=north; }
    }
    private final ArrayDeque<Frame> history=new ArrayDeque<>();
    private long lastFrame, historyCutoff;
    private Selector.Fix origin;
    private long lastAcceptedAnchor, lastSensor;
    private double east,north,velocityEast,velocityNorth,anchorSpeed,anchorSpeedAccuracy,anchorBearingAccuracy;
    private String status="waiting_anchor";
    private boolean active;

    synchronized boolean anchor(Selector.Fix gps,double speed,double speedAccuracy,double bearing,double bearingAccuracy,long now) {
        if (gps==null || !"gps".equals(gps.source) || !Selector.valid(gps,now) || gps.timestamp<=0 || gps.sequence<=0
                || gps.elapsed<=lastAcceptedAnchor || now-gps.elapsed>GNSS_FRESH || gps.accuracy>50 || Math.abs(gps.latitude)>85
                || !Double.isFinite(speed) || speed<1 || speed>MAX_SPEED
                || !Double.isFinite(speedAccuracy) || speedAccuracy<0 || speedAccuracy>5
                || !Double.isFinite(bearing) || bearing<0 || bearing>=360
                || !Double.isFinite(bearingAccuracy) || bearingAccuracy<0 || bearingAccuracy>15) return false;
        // A delayed anchor needs a complete real sensor path back to its timestamp.
        if (gps.elapsed<=historyCutoff) return false;
        long cursor=gps.elapsed;
        for (Frame frame:history) {
            if (frame.time<=gps.elapsed) continue;
            if (frame.time-cursor>MAX_GAP) return false;
            cursor=frame.time;
        }
        if (now-cursor>MAX_GAP) return false;
        origin=gps; lastAcceptedAnchor=gps.elapsed; lastSensor=gps.elapsed;
        east=north=0;
        double radians=Math.toRadians(bearing);
        velocityEast=speed*Math.sin(radians); velocityNorth=speed*Math.cos(radians);
        anchorSpeed=speed; anchorSpeedAccuracy=speedAccuracy; anchorBearingAccuracy=bearingAccuracy;
        active=true; status="gnss_fresh";
        for (Frame frame:history) if (frame.time>gps.elapsed && !integrate(frame.time,frame.east,frame.north)) return false;
        return true;
    }

    synchronized boolean acceleration(long elapsed,double accelerationEast,double accelerationNorth,long now) {
        if (elapsed<=0 || now<elapsed || now-elapsed>MAX_SENSOR_AGE || (lastFrame!=0 && elapsed<=lastFrame)) {
            invalidate("sensor_timing"); return false;
        }
        if (!Double.isFinite(accelerationEast) || !Double.isFinite(accelerationNorth)
                || Math.hypot(accelerationEast,accelerationNorth)>MAX_ACCEL) {
            invalidate("invalid_acceleration"); return false;
        }
        if (lastFrame!=0 && elapsed-lastFrame>MAX_GAP) {
            invalidate("sensor_timing");
        }
        lastFrame=elapsed;
        history.addLast(new Frame(elapsed,accelerationEast,accelerationNorth));
        while (history.size()>64 || elapsed-history.peekFirst().time>2*S) history.removeFirst();
        if (!active || origin==null) return false;
        // Samples arriving after the GPS callback may still predate the GPS fix.
        if (elapsed<=origin.elapsed) return true;
        if (now<origin.elapsed || now-origin.elapsed>MAX_HORIZON) { invalidate("horizon_expired"); return false; }
        if (elapsed-lastSensor>MAX_GAP) { invalidate("sensor_timing"); return false; }
        return integrate(elapsed,accelerationEast,accelerationNorth);
    }

    private boolean integrate(long elapsed,double accelerationEast,double accelerationNorth) {
        double dt=(elapsed-lastSensor)/(double)S;
        double nextEast=velocityEast+accelerationEast*dt, nextNorth=velocityNorth+accelerationNorth*dt;
        if (!Double.isFinite(nextEast) || !Double.isFinite(nextNorth) || Math.hypot(nextEast,nextNorth)>MAX_SPEED) {
            invalidate("speed_limit"); return false;
        }
        east += (velocityEast+nextEast)*dt/2.0;
        north += (velocityNorth+nextNorth)*dt/2.0;
        velocityEast=nextEast; velocityNorth=nextNorth; lastSensor=elapsed;
        return true;
    }

    private String evaluate(long now) {
        if (!active || origin==null) return status;
        if (now<origin.elapsed || now<lastSensor) { invalidate("clock_error"); return status; }
        long sinceAnchor=now-origin.elapsed;
        if (sinceAnchor>MAX_HORIZON) { invalidate("horizon_expired"); return status; }
        if (lastSensor==origin.elapsed || now-lastSensor>MAX_SENSOR_AGE) {
            if (sinceAnchor<=GNSS_FRESH) return "gnss_fresh";
            invalidate("sensor_stale"); return status;
        }
        if (lastSensor-origin.elapsed<=GNSS_FRESH) return "gnss_fresh";
        double t=(lastSensor-origin.elapsed)/(double)S;
        double uncertainty=origin.accuracy+(Math.max(anchorSpeedAccuracy,0.5)+anchorSpeed*Math.sin(Math.toRadians(anchorBearingAccuracy)))*t+1.5*t*t;
        if (!Double.isFinite(uncertainty) || uncertainty>MAX_UNCERTAINTY) { invalidate("uncertainty_exceeded"); return status; }
        return "eligible";
    }

    synchronized Prediction predict(long now) {
        if (!"eligible".equals(evaluate(now))) return null;
        double latRad=Math.toRadians(origin.latitude), sin=Math.sin(latRad);
        double denom=1-WGS84_E2*sin*sin;
        double meridional=WGS84_A*(1-WGS84_E2)/Math.pow(denom,1.5);
        double prime=WGS84_A/Math.sqrt(denom);
        double latitude=origin.latitude+Math.toDegrees(north/meridional);
        double longitude=origin.longitude+Math.toDegrees(east/(prime*Math.cos(latRad)));
        longitude=((longitude+180)%360+360)%360-180;
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude)>85) {
            invalidate("latitude_limit"); return null;
        }
        long elapsedMillis=(lastSensor-origin.elapsed)/1_000_000L;
        if (origin.timestamp>Long.MAX_VALUE-elapsedMillis) { invalidate("timestamp_overflow"); return null; }
        double t=(lastSensor-origin.elapsed)/(double)S;
        double uncertainty=origin.accuracy+(Math.max(anchorSpeedAccuracy,0.5)+anchorSpeed*Math.sin(Math.toRadians(anchorBearingAccuracy)))*t+1.5*t*t;
        double speed=Math.hypot(velocityEast,velocityNorth);
        double bearing=(Math.toDegrees(Math.atan2(velocityEast,velocityNorth))+360)%360;
        return new Prediction(latitude,longitude,uncertainty,speed,bearing,lastSensor,origin.elapsed,
                origin.timestamp+elapsedMillis,(lastSensor-origin.elapsed)/1_000_000.0);
    }

    synchronized void invalidate(String reason) {
        active=false;
        historyCutoff=Math.max(historyCutoff,lastFrame);
        history.clear();
        status=reason == null || reason.isEmpty() ? "invalidated" : reason;
    }
    synchronized void invalidate(String reason,long elapsedNanos) {
        invalidate(reason);
        if (elapsedNanos>0) historyCutoff=Math.max(historyCutoff,elapsedNanos);
    }
    synchronized String reason(long now) { return evaluate(now); }
}
