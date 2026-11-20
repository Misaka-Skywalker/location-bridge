package org.locationbridge.source;

import java.util.ArrayDeque;

/** GNSS-initialized horizontal propagation; uncertainty is a model, not measured error. */
final class DeadReckoning {
    private static final long S=1_000_000_000L, GNSS_FRESH=1_500_000_000L;
    private static final long MAX_GAP=250_000_000L, MAX_SENSOR_AGE=500_000_000L;
    // Cover the delayed GNSS replay window on devices delivering up to 200 Hz.
    private static final long HISTORY_WINDOW = 2*S;
    private static final int MAX_HISTORY_FRAMES = 512;
    private static final double MAX_SPEED=450, MAX_ACCEL=15;
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
    static final class Deviation {
        final double eastMeters,northMeters,distanceMeters,referenceAccuracyMeters,predictionAgeMillis;
        final long elapsed;
        Deviation(double east,double north,double distance,double accuracy,double age,long elapsed) {
            eastMeters=east;northMeters=north;distanceMeters=distance;referenceAccuracyMeters=accuracy;
            predictionAgeMillis=age;this.elapsed=elapsed;
        }
    }
    private static final class Frame {
        final long time; final double east,north;
        Frame(long time,double east,double north) { this.time=time; this.east=east; this.north=north; }
    }
    private static final class Position {
        final long time; final double lat,lon;
        Position(long time,double lat,double lon) {this.time=time;this.lat=lat;this.lon=lon;}
    }
    private final ArrayDeque<Frame> history=new ArrayDeque<>();
    private final ArrayDeque<Position> positions=new ArrayDeque<>();
    private long lastFrame, historyCutoff;
    private Selector.Fix origin;
    private long lastAcceptedAnchor, lastSensor;
    private double latitude,longitude,velocityEast,velocityNorth,anchorSpeed,anchorSpeedAccuracy,anchorBearingAccuracy;
    private double currentGrowthScale=1, anchorGrowthScale=1;
    private Deviation deviation;
    private String status="waiting_anchor";
    private boolean active;

    synchronized void setUncertaintyGrowthScale(double scale) {
        currentGrowthScale=Double.isFinite(scale)?Math.max(1,scale):2;
        // Better sensor quality must not erase uncertainty accumulated since the anchor.
        anchorGrowthScale=Math.max(anchorGrowthScale,currentGrowthScale);
    }
    synchronized boolean anchor(Selector.Fix gps,double speed,double speedAccuracy,double bearing,double bearingAccuracy,long now) {
        boolean stationary=Double.isFinite(speed) && speed>=0 && Double.isFinite(speedAccuracy)
                && speedAccuracy>=0 && speed+2*speedAccuracy<=1;
        if (gps==null || !"gps".equals(gps.source) || !Selector.valid(gps,now) || gps.timestamp<=0 || gps.sequence<=0
                || gps.elapsed<=lastAcceptedAnchor || now-gps.elapsed>GNSS_FRESH || gps.accuracy>50 || Math.abs(gps.latitude)>85
                || !Double.isFinite(speed) || speed<0 || speed>MAX_SPEED
                || !Double.isFinite(speedAccuracy) || speedAccuracy<0 || speedAccuracy>5
                || !stationary && (speed<1 || !Double.isFinite(bearing) || bearing<0 || bearing>=360
                || !Double.isFinite(bearingAccuracy) || bearingAccuracy<0 || bearingAccuracy>15)) return false;
        if (gps.elapsed<=historyCutoff) return false;
        long cursor=gps.elapsed;
        for (Frame frame:history) {
            if (frame.time<=gps.elapsed) continue;
            if (frame.time-cursor>MAX_GAP) return false;
            cursor=frame.time;
        }
        if (now-cursor>MAX_GAP) return false;
        Deviation next=correction(gps);
        origin=gps; lastAcceptedAnchor=gps.elapsed; lastSensor=gps.elapsed;
        latitude=gps.latitude;longitude=gps.longitude;
        double radians=stationary?0:Math.toRadians(bearing);
        velocityEast=stationary?0:speed*Math.sin(radians);velocityNorth=stationary?0:speed*Math.cos(radians);
        anchorSpeed=stationary?0:speed;anchorSpeedAccuracy=speedAccuracy+(stationary?speed:0);
        anchorBearingAccuracy=stationary?0:bearingAccuracy;anchorGrowthScale=currentGrowthScale;
        active=true;status="gnss_fresh";
        positions.clear();positions.addLast(new Position(lastSensor,latitude,longitude));
        for (Frame frame:history) if (frame.time>gps.elapsed && !integrate(frame.time,frame.east,frame.north)) return false;
        if(next!=null) deviation=next;
        return true;
    }
    synchronized boolean acceleration(long elapsed,double accelerationEast,double accelerationNorth,long now) {
        if (elapsed<=0 || now<elapsed || now-elapsed>MAX_SENSOR_AGE || (lastFrame!=0 && elapsed<=lastFrame)) {
            invalidate("sensor_timing");return false;
        }
        if (!Double.isFinite(accelerationEast) || !Double.isFinite(accelerationNorth)
                || Math.hypot(accelerationEast,accelerationNorth)>MAX_ACCEL) {
            invalidate("invalid_acceleration");return false;
        }
        long continuityStart = active ? Math.max(lastFrame, origin.elapsed) : lastFrame;
        if (continuityStart > 0 && elapsed - continuityStart > MAX_GAP) invalidate("sensor_timing");
        lastFrame=elapsed;history.addLast(new Frame(elapsed,accelerationEast,accelerationNorth));
        while (history.size() > MAX_HISTORY_FRAMES || elapsed-history.peekFirst().time > HISTORY_WINDOW) {
            Frame removed = history.removeFirst();
            historyCutoff = Math.max(historyCutoff, removed.time);
        }
        if(!active || origin==null) return false;
        if(elapsed<=origin.elapsed) return true;
        if(now<origin.elapsed || elapsed-lastSensor>MAX_GAP) {invalidate("sensor_timing");return false;}
        return integrate(elapsed,accelerationEast,accelerationNorth);
    }
    private static double wrap(double lon) {return ((lon+180)%360+360)%360-180;}
    private static double[] offset(double lat,double lon,double east,double north) {
        double rad=Math.toRadians(lat),sin=Math.sin(rad),denom=1-WGS84_E2*sin*sin;
        double m=WGS84_A*(1-WGS84_E2)/Math.pow(denom,1.5);
        double mid=Math.toRadians(lat+Math.toDegrees(north/m)/2);
        sin=Math.sin(mid);denom=1-WGS84_E2*sin*sin;
        m=WGS84_A*(1-WGS84_E2)/Math.pow(denom,1.5);
        double n=WGS84_A/Math.sqrt(denom);
        return new double[]{lat+Math.toDegrees(north/m),wrap(lon+Math.toDegrees(east/(n*Math.cos(mid))))};
    }
    private boolean integrate(long elapsed,double ae,double an) {
        double dt=(elapsed-lastSensor)/(double)S;
        double ve=velocityEast+ae*dt,vn=velocityNorth+an*dt;
        if(!Double.isFinite(ve) || !Double.isFinite(vn) || Math.hypot(ve,vn)>MAX_SPEED) {invalidate("speed_limit");return false;}
        double[] p=offset(latitude,longitude,(velocityEast+ve)*dt/2,(velocityNorth+vn)*dt/2);
        if(!Double.isFinite(p[0]) || !Double.isFinite(p[1]) || Math.abs(p[0])>85) {invalidate("latitude_limit");return false;}
        latitude=p[0];longitude=p[1];velocityEast=ve;velocityNorth=vn;lastSensor=elapsed;
        positions.addLast(new Position(elapsed,latitude,longitude));
        while(positions.size()>MAX_HISTORY_FRAMES || elapsed-positions.peekFirst().time>HISTORY_WINDOW) positions.removeFirst();
        return true;
    }
    private double uncertainty() {
        double t=(lastSensor-origin.elapsed)/(double)S;
        return origin.accuracy+(Math.max(anchorSpeedAccuracy,0.5)+anchorSpeed*Math.sin(Math.toRadians(anchorBearingAccuracy)))*t
                +1.5*anchorGrowthScale*t*t;
    }
    private String evaluate(long now) {
        if(!active || origin==null) return status;
        if(now<origin.elapsed || now<lastSensor) {invalidate("clock_error");return status;}
        if(lastSensor==origin.elapsed || now-lastSensor>MAX_SENSOR_AGE) {
            if(now-origin.elapsed<=GNSS_FRESH) return "gnss_fresh";
            invalidate("sensor_stale");return status;
        }
        if(!Double.isFinite(uncertainty())) {invalidate("numeric_error");return status;}
        return lastSensor-origin.elapsed<=GNSS_FRESH?"gnss_fresh":"eligible";
    }
    synchronized Prediction estimate(long now) {
        evaluate(now);
        if(!active || origin==null || now-lastSensor>MAX_SENSOR_AGE) return null;
        long millis=(lastSensor-origin.elapsed)/1_000_000L;
        if(origin.timestamp>Long.MAX_VALUE-millis) {invalidate("timestamp_overflow");return null;}
        return new Prediction(latitude,longitude,uncertainty(),Math.hypot(velocityEast,velocityNorth),
                (Math.toDegrees(Math.atan2(velocityEast,velocityNorth))+360)%360,lastSensor,origin.elapsed,
                origin.timestamp+millis,(lastSensor-origin.elapsed)/1_000_000.0);
    }
    synchronized Prediction predict(long now) {
        Prediction p=estimate(now);
        return p!=null && p.predictionAgeMillis>1500?p:null;
    }
    private Position at(long time) {
        Position previous=null;
        for(Position p:positions) {
            if(p.time==time) return p;
            if(p.time>time) {
                if(previous==null || p.time-previous.time>MAX_GAP) return null;
                double ratio=(time-previous.time)/(double)(p.time-previous.time);
                return new Position(time,previous.lat+(p.lat-previous.lat)*ratio,wrap(previous.lon+wrap(p.lon-previous.lon)*ratio));
            }
            previous=p;
        }
        if(previous!=null && time>previous.time && time-previous.time<=MAX_GAP) {
            double dt=(time-previous.time)/(double)S;
            double[] p=offset(previous.lat,previous.lon,velocityEast*dt,velocityNorth*dt);
            return new Position(time,p[0],p[1]);
        }
        return null;
    }
    private Deviation correction(Selector.Fix gps) {
        if(!active || origin==null || gps.elapsed<=origin.elapsed) return null;
        Position p=at(gps.elapsed);if(p==null) return null;
        double a=Math.toRadians(gps.latitude),b=Math.toRadians(p.lat),dl=Math.toRadians(wrap(p.lon-gps.longitude));
        double h=Math.pow(Math.sin((b-a)/2),2)+Math.cos(a)*Math.cos(b)*Math.pow(Math.sin(dl/2),2);
        double distance=2*6_371_000*Math.asin(Math.sqrt(Math.max(0,Math.min(1,h))));
        double bearing=Math.atan2(Math.sin(dl)*Math.cos(b),Math.cos(a)*Math.sin(b)-Math.sin(a)*Math.cos(b)*Math.cos(dl));
        return new Deviation(distance*Math.sin(bearing),distance*Math.cos(bearing),distance,gps.accuracy,
                (gps.elapsed-origin.elapsed)/1_000_000.0,gps.elapsed);
    }
    synchronized Deviation lastDeviation() {return deviation;}
    synchronized long lastAnchorNanos() {return lastAcceptedAnchor;}
    synchronized void invalidate(String reason) {
        active=false;historyCutoff=Math.max(historyCutoff,lastFrame);history.clear();positions.clear();
        status=reason==null || reason.isEmpty()?"invalidated":reason;
    }
    synchronized void invalidate(String reason,long elapsedNanos) {
        invalidate(reason);if(elapsedNanos>0) historyCutoff=Math.max(historyCutoff,elapsedNanos);
    }
    synchronized String reason(long now) {return evaluate(now);}
}
