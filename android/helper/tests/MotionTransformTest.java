package org.locationbridge.source;

public final class MotionTransformTest {
    static void check(boolean v,String message) { if (!v) throw new AssertionError(message); }
    static void near(double a,double b) { check(Math.abs(a-b)<1e-9,"expected "+b+" got "+a); }
    static void bad(float[] r,float[] a,double d) {
        try { MotionTransform.horizontal(r,a,d); throw new AssertionError("invalid accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        float[] identity={1,0,0,0,1,0,0,0,1};
        double[] north=MotionTransform.horizontal(identity,new float[]{0,1,0},90);
        near(north[0],1); near(north[1],0);
        float[] rotate90={0,-1,0,1,0,0,0,0,1};
        double[] rotated=MotionTransform.horizontal(rotate90,new float[]{1,0,0},0);
        near(rotated[0],0); near(rotated[1],1);
        bad(null,new float[]{1,0,0},0); bad(identity,new float[]{1,0},0);
        bad(identity,new float[]{1,Float.NaN,0},0); bad(identity,new float[]{1,0,0},Double.NaN);
        float[] invalid=identity.clone(); invalid[8]=Float.POSITIVE_INFINITY; bad(invalid,new float[]{1,0,0},0);
        System.out.println("Motion transform tests passed");
    }
}
