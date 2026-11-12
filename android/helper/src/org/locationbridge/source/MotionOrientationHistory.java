package org.locationbridge.source;

/** Recent rotation matrices; owned by the sensor adapter's serialized handler. */
final class MotionOrientationHistory {
    private final long[] times=new long[8];
    private final float[][] matrices=new float[8][];
    private int next, size;
    void add(long time,float[] matrix) {
        times[next]=time; matrices[next]=matrix.clone();
        next=(next+1)%times.length; size=Math.min(size+1,times.length);
    }
    float[] at(long time) {
        for (int i=0;i<size;i++) {
            int index=(next-1-i+times.length)%times.length;
            if (times[index]<=time) return time-times[index]<=250_000_000L ? matrices[index].clone() : null;
        }
        return null;
    }
    void clear() { java.util.Arrays.fill(matrices,null); next=0; size=0; }
}
