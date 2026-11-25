package org.locationbridge.source;
public final class MotionOrientationHistoryTest {
    static void check(boolean value,String text) { if (!value) throw new AssertionError(text); }
    private static void near(float value, double expected, String message) {
        check(Math.abs(value-expected)<1e-6,message+": "+value);
    }

    private static void gyroAlignment() {
        long start=1_000_000_000L, dt=20_000_000L;
        float[] identity={1,0,0,0,1,0,0,0,1};
        MotionOrientationHistory history=new MotionOrientationHistory();
        history.add(start,identity);
        history.addGyroscope(start,new float[]{0,0,1},20);
        history.addGyroscope(start+dt,new float[]{1,0,0},20);
        history.addGyroscope(start+2*dt,new float[]{0,10,0},20);
        check(Double.isNaN(history.angularSpeedAt(start-1)),"future gyro supplies no disturbance evidence");
        check(history.angularSpeedAt(start+dt)==1,"disturbance detector uses the last causal sample");
        check(history.angularSpeedAt(start+2*dt)==10,"gyro norm at the acceleration timestamp");
        check(Double.isNaN(history.angularSpeedAt(start+5*dt)),"stale gyro supplies no disturbance evidence");
        float[] at=history.at(start+2*dt);
        // The first 20ms is around device Z, followed by 20ms around the rotated device X.
        near(at[3],Math.sin(.02),"device Z swing applied");
        near(at[7],Math.sin(.02),"device X swing applied");
        near(at[6],0,"gyro at the target timestamp must not affect the past");
        near(history.at(start)[0],1,"exact orientation does not need gyro");

        history.clearGyroscope();
        check(Double.isNaN(history.angularSpeedAt(start+2*dt)),"cleared gyro supplies no disturbance evidence");
        near(history.at(start+2*dt)[7],0,"cleared gyro falls back to rotation vector");
        history.addGyroscope(start,new float[]{0,0,1},20);
        history.addGyroscope(start+4*dt,new float[]{1,0,0},20);
        near(history.at(start+4*dt)[3],0,"gyro gap falls back to rotation vector");
        near(history.at(start+3*dt)[3],0,"future gyro cannot cover an old gap");
        history.addGyroscope(start+3*dt,new float[]{0,0,1},20);
        near(history.at(start+dt)[3],0,"out-of-order gyro drops the optional history");
        history.addGyroscope(start,new float[]{0,0,1},20);
        history.addGyroscope(start+dt,new float[]{0,0,Float.NaN},20);
        near(history.at(start+2*dt)[3],0,"nonfinite gyro drops the optional history");
        history.addGyroscope(start,new float[]{0,0,1},20);
        history.addGyroscope(start+dt,new float[]{0,0,-20},20);
        near(history.at(start+2*dt)[3],0,"gyro clipped at its hardware limit drops the optional history");
        history.addGyroscope(start,new float[]{0,0,20.1f},20);
        near(history.at(start+dt)[3],0,"gyro above its hardware range is ignored");
        for (int i=0;i<=5;i++) history.addGyroscope(start+i*dt,new float[]{0,0,1},20);
        near(history.at(start+5*dt)[3],Math.sin(.10),"gyro corrects up to 100ms");
        near(history.at(start+6*dt)[3],0,"older orientation uses the original fallback");
        history.clear();
        history.add(start,identity);
        near(history.at(start+dt)[3],0,"full reset clears the gyroscope too");
    }

    public static void main(String[] args) {
        MotionOrientationHistory h=new MotionOrientationHistory();
        float[] a={1,0,0,0,1,0,0,0,1}, b={0,-1,0,1,0,0,0,0,1};
        h.add(1_000_000_000L,a); h.add(1_050_000_000L,b);
        check(h.at(1_025_000_000L)[0]==1,"uses previous orientation when latest is newer");
        check(h.at(1_050_000_000L)[0]==0,"uses newest matching orientation");
        check(h.at(1_300_000_000L)!=null,"250ms boundary");
        check(h.at(1_300_000_001L)==null,"reject stale orientation");
        h.clear(); check(h.at(1_050_000_000L)==null,"invalid heading clears history");
        for(int i=1;i<=9;i++) h.add(i*1_000_000L,a);
        check(h.at(1_000_000L)==null,"bounded history");
        gyroAlignment();
        System.out.println("Motion orientation history tests passed");
    }
}
