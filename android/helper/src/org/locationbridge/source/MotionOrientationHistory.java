package org.locationbridge.source;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Recent rotation matrices; owned by the sensor adapter's serialized handler. */
final class MotionOrientationHistory {
    private static final long MAX_ORIENTATION_AGE=250_000_000L;
    private static final long MAX_GYRO_PROPAGATION=100_000_000L;
    private static final long MAX_GYRO_GAP=50_000_000L;
    private static final int MAX_GYRO_SAMPLES=32;

    private static final class AngularRate {
        final long time;
        final float[] device;
        AngularRate(long time,float[] device) { this.time=time; this.device=Arrays.copyOf(device,3); }
    }

    private final long[] times=new long[8];
    private final float[][] matrices=new float[8][];
    private final ArrayDeque<AngularRate> angularRates=new ArrayDeque<>();
    private int next, size;

    void add(long time,float[] matrix) {
        times[next]=time; matrices[next]=matrix.clone();
        next=(next+1)%times.length; size=Math.min(size+1,times.length);
    }

    void addGyroscope(long time,float[] device,float maximumRange) {
        AngularRate last=angularRates.peekLast();
        if (time<=0 || (last!=null && time<=last.time) || device==null || device.length<3
                || !Float.isFinite(maximumRange)
                || !(Math.abs(device[0])<maximumRange && Math.abs(device[1])<maximumRange
                && Math.abs(device[2])<maximumRange)) {
            clearGyroscope();
            return;
        }
        angularRates.addLast(new AngularRate(time,device));
        if (angularRates.size()>MAX_GYRO_SAMPLES) angularRates.removeFirst();
    }

    float[] at(long time) {
        for (int i=0;i<size;i++) {
            int index=(next-1-i+times.length)%times.length;
            long baseTime=times[index];
            if (baseTime>time) continue;
            if (time-baseTime>MAX_ORIENTATION_AGE) return null;
            if (time>baseTime && time-baseTime<=MAX_GYRO_PROPAGATION && !angularRates.isEmpty()) {
                float[] propagated=propagate(matrices[index],baseTime,time);
                if (propagated!=null) return propagated;
            }
            return matrices[index].clone();
        }
        return null;
    }

    private float[] propagate(float[] matrix,long baseTime,long time) {
        AngularRate previous=null;
        float[] result=null;
        long cursor=baseTime;
        for (AngularRate sample:angularRates) {
            if (sample.time>time) break;
            if (sample.time>baseTime) {
                if (previous==null || sample.time-previous.time>MAX_GYRO_GAP) return null;
                if (result==null) result=matrix.clone();
                rotate(result,previous.device,sample.time-cursor);
                cursor=sample.time;
            }
            previous=sample;
        }
        if (previous==null || time-previous.time>MAX_GYRO_GAP) return null;
        if (result==null) result=matrix.clone();
        rotate(result,previous.device,time-cursor);
        return result;
    }

    private static void rotate(float[] matrix,float[] device,long elapsed) {
        double x=device[0],y=device[1],z=device[2];
        double magnitude=Math.sqrt(x*x+y*y+z*z);
        if (magnitude==0 || elapsed==0) return;
        x/=magnitude; y/=magnitude; z/=magnitude;
        double angle=magnitude*elapsed/1_000_000_000.0;
        double c=Math.cos(angle),s=Math.sin(angle);
        // Postmultiply the device-to-world matrix by the rotation in device coordinates.
        for (int row=0;row<9;row+=3) {
            double a=matrix[row],b=matrix[row+1],d=matrix[row+2];
            double projection=(1-c)*(a*x+b*y+d*z);
            matrix[row]=(float)(c*a+s*(b*z-d*y)+projection*x);
            matrix[row+1]=(float)(c*b+s*(d*x-a*z)+projection*y);
            matrix[row+2]=(float)(c*d+s*(a*y-b*x)+projection*z);
        }
    }

    void clearGyroscope() { angularRates.clear(); }
    void clear() { Arrays.fill(matrices,null); next=0; size=0; clearGyroscope(); }
}
