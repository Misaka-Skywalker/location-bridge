package org.locationbridge.source;

/** Gravity removal in the shared device frame; no estimates for stale samples. */
final class GravitySubtraction {
    static double[] subtract(float[] acceleration,long at,float[] gravity,long gt,long now,double maxRange) {
        if(acceleration==null||gravity==null||acceleration.length<3||gravity.length<3||at<=0||gt<=0||now<at||now<gt||now-at>500_000_000L||now-gt>500_000_000L||Math.abs(at-gt)>50_000_000L||!Double.isFinite(maxRange)||maxRange<=0) throw new IllegalArgumentException();
        double norm=0; double[] out=new double[3];
        for(int i=0;i<3;i++) { if(!Float.isFinite(acceleration[i])||!Float.isFinite(gravity[i])||Math.abs(acceleration[i])>=maxRange) throw new IllegalArgumentException(); norm+=(double)gravity[i]*gravity[i]; out[i]=acceleration[i]-gravity[i]; }
        if(Math.sqrt(norm)<8 || Math.sqrt(norm)>11.5) throw new IllegalArgumentException();
        return out;
    }
}
