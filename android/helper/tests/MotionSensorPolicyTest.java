package org.locationbridge.source;

public final class MotionSensorPolicyTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args) {
        check(MotionSensorPolicy.uncertaintyGrowthScale(false, false, true)==1.0);
        check(MotionSensorPolicy.uncertaintyGrowthScale(false, false, false)==2.0);
        check(MotionSensorPolicy.uncertaintyGrowthScale(true, false, true)==2.0);
        check(MotionSensorPolicy.uncertaintyGrowthScale(false, true, true)==2.0);
        check(MotionSensorPolicy.magneticValid(new float[]{20,30,40},3,100,120));
        check(!MotionSensorPolicy.magneticValid(new float[]{1,1,1},3,100,120));
        check(!MotionSensorPolicy.magneticValid(new float[]{20,30,40},1,100,120));
        check(!MotionSensorPolicy.magneticValid(new float[]{20,30,40},3,100,2_000_000_100L));
        double[] v=GravitySubtraction.subtract(new float[]{2,3,10},100,new float[]{0,0,9.8f},100,200,100);
        check(Math.abs(v[0]-2)<1e-6 && Math.abs(v[2]-0.2)<1e-5);
        expectBad(100,60_000_101,new float[]{0,0,9.8f});
        expectBad(100,100,new float[]{0,0,2});
        expectBad(100,100,new float[]{Float.NaN,0,9.8f});
        try { GravitySubtraction.subtract(new float[]{100,0,0},100,new float[]{0,0,9.8f},100,100,100); throw new AssertionError(); } catch(IllegalArgumentException expected){}
        try { GravitySubtraction.subtract(new float[]{0,0,10},100,new float[]{0,0,9.8f},100,500_000_101L,100); throw new AssertionError(); } catch(IllegalArgumentException expected){}
        System.out.println("MotionSensorPolicyTest passed");
    }
    static void expectBad(long a,long g,float[] gravity){
        try {GravitySubtraction.subtract(new float[]{2,3,10},a,gravity,g,Math.max(a,g),100); throw new AssertionError();}
        catch(IllegalArgumentException expected){}
    }
}
