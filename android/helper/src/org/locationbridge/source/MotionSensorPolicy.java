package org.locationbridge.source;

/** Conservative quality scaling for optional sensor backends. */
final class MotionSensorPolicy {
    static double uncertaintyGrowthScale(boolean geomagnetic, boolean subtraction, boolean headingKnown) {
        return geomagnetic || subtraction || !headingKnown ? 2.0 : 1.0;
    }
    static boolean magneticValid(float[] values, int accuracy, long timestamp, long now) {
        if(values==null || values.length<3 || accuracy<2 || timestamp<=0 || now<timestamp || now-timestamp>1_000_000_000L) return false;
        double sum=0; for(int i=0;i<3;i++) {if(!Float.isFinite(values[i]))return false;sum+=(double)values[i]*values[i];}
        double strength=Math.sqrt(sum); return strength>=20 && strength<=70;
    }
}
