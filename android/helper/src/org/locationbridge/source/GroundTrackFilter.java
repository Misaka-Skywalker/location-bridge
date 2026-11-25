package org.locationbridge.source;

/** Reduces lateral measurement jitter during recently confirmed straight, low-speed travel. */
final class GroundTrackFilter {
    private static final long SECOND=1_000_000_000L;
    private static final long MAX_AGE=1_500_000_000L;
    private static final double METRES_PER_DEGREE=111319.49;
    private static final double TIME_CONSTANT=2;
    private long velocityTime,straightSince;
    private double speed,bearing,firstBearing;

    void reset() {
        velocityTime=0;
        straightSince=0;
    }

    void observeVelocity(long elapsed,double speed,double speedAccuracy,double bearing,double bearingAccuracy,long now) {
        if (elapsed<=velocityTime || elapsed>now || now-elapsed>MAX_AGE
                || !Double.isFinite(speed) || !(speedAccuracy>=0 && speedAccuracy<=.5)
                || speed-2*speedAccuracy<1 || speed+2*speedAccuracy>15
                || !(bearing>=0 && bearing<360) || !(bearingAccuracy>=0 && bearingAccuracy<=8)) {
            reset();
            return;
        }
        double dt=(elapsed-velocityTime)/(double)SECOND;
        if (straightSince==0 || elapsed-velocityTime>MAX_AGE || Math.abs(speed-this.speed)>.9*dt
                || courseDifference(bearing,this.bearing)>2*dt || courseDifference(bearing,firstBearing)>2.5) {
            straightSince=elapsed;
            firstBearing=bearing;
        }
        velocityTime=elapsed;
        this.speed=speed;
        this.bearing=bearing;
    }

    Selector.Fix apply(Selector.Fix last,Selector.Fix raw,long now) {
        if (!Selector.valid(raw,now) || !("gps".equals(raw.source) || "fused".equals(raw.source))) {
            reset();
            return raw;
        }
        if (last==null) return raw;
        if (!Selector.valid(last,now) || !raw.source.equals(last.source) || raw.elapsed<=last.elapsed
                || raw.elapsed-last.elapsed>MAX_AGE || now-raw.elapsed>MAX_AGE
                || raw.accuracy>30 || last.accuracy>Math.min(30,2*raw.accuracy+3)
                || Math.abs(raw.latitude)>=85 || Math.abs(last.latitude)>=85) {
            reset();
            return raw;
        }
        if (velocityTime>now || now-velocityTime>MAX_AGE) {
            reset();
            return raw;
        }
        if (straightSince==0 || velocityTime-straightSince<3*SECOND || last.elapsed<straightSince
                || Math.abs(raw.elapsed-velocityTime)>SECOND) return raw;

        double eastScale=METRES_PER_DEGREE*Math.cos(Math.toRadians((raw.latitude+last.latitude)/2));
        double east=eastScale*Math.IEEEremainder(raw.longitude-last.longitude,360);
        double north=METRES_PER_DEGREE*(raw.latitude-last.latitude);
        double radians=Math.toRadians(bearing), sin=Math.sin(radians), cos=Math.cos(radians);
        double along=east*sin+north*cos, lateral=east*cos-north*sin;
        double dt=(raw.elapsed-last.elapsed)/(double)SECOND;
        // A displaced measurement or a large disagreement with the measured speed is a new segment.
        double alongTolerance=Math.min(15,Math.max(6,raw.accuracy+last.accuracy));
        if (Math.abs(lateral)>22 || Math.abs(along-speed*dt)>alongTolerance) {
            reset();
            return raw;
        }
        // Bound the coordinate change and leave the measured along-track projection unaltered.
        double limit=Math.min(8,1.5*raw.accuracy);
        double correction=Math.max(-limit,Math.min(limit,lateral*Math.exp(-dt/TIME_CONSTANT)));
        if (correction==0) return raw;
        return new Selector.Fix(raw.source,raw.latitude+correction*sin/METRES_PER_DEGREE,
                Math.IEEEremainder(raw.longitude-correction*cos/eastScale,360),raw.accuracy,
                raw.elapsed,raw.mock,raw.timestamp,raw.sequence);
    }

    private static double courseDifference(double a,double b) {
        return Math.abs(Math.IEEEremainder(a-b,360));
    }
}
