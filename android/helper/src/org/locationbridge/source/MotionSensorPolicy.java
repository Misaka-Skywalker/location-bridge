package org.locationbridge.source;

/** Conservative eligibility and horizon for optional sensor backends. */
final class MotionSensorPolicy {
    static long horizon(boolean geomagnetic, boolean subtraction, boolean headingKnown) {
        return (geomagnetic || subtraction || !headingKnown ? 3L : 8L)*1_000_000_000L;
    }
    static boolean magneticValid(float[] values, int accuracy, long timestamp, long now) {
        if(values==null || values.length<3 || accuracy<2 || timestamp<=0 || now<timestamp || now-timestamp>1_000_000_000L) return false;
        double sum=0; for(int i=0;i<3;i++) {if(!Float.isFinite(values[i]))return false;sum+=(double)values[i]*values[i];}
        double strength=Math.sqrt(sum); return strength>=20 && strength<=70;
    }
}
