package org.locationbridge.source;

/** Device-independent request cadence and age cost from trusted recent GNSS velocity. */
final class AdaptiveLocationProfile {
    private static final long SECOND=1_000_000_000L;
    private long motionTime, lastMotionTime, profileTime;
    private double speed, speedAccuracy;
    private long interval=500;

    synchronized boolean observeGpsMotion(double speed,double accuracy,long elapsed,long now) {
        if (!Double.isFinite(speed) || speed<0 || speed>450 || !Double.isFinite(accuracy)
                || accuracy<0 || accuracy>5 || elapsed<=0 || elapsed<=lastMotionTime
                || elapsed>now || now-elapsed>1_500_000_000L) return false;
        this.speed=speed; speedAccuracy=accuracy; motionTime=lastMotionTime=elapsed;
        return true;
    }
    private boolean fresh(long now) { return motionTime>0 && now>=motionTime && now-motionTime<=5*SECOND; }
    synchronized double agingRate(long now) { return fresh(now)?Math.max(20,speed+2*speedAccuracy):20; }
    synchronized long updateIntervalMillis(boolean recentPosition,long now) {
        long wanted=recentPosition && (!fresh(now) || speed<3)?1000:500;
        if (profileTime==0 || now<profileTime || now-profileTime>=5*SECOND) {
            if (wanted!=interval || profileTime==0) { interval=wanted; profileTime=now; }
        }
        return interval;
    }
    synchronized void clearMotion() { motionTime=0; speed=speedAccuracy=0; }
}
