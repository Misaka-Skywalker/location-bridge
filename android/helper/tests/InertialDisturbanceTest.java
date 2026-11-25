package org.locationbridge.source;

public final class InertialDisturbanceTest {
    private static final long SECOND=1_000_000_000L, START=100*SECOND, STEP=SECOND/20;
    private static final double SPEED=100;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Selector.Fix gps(long at) {
        return new Selector.Fix("gps",0,0,5,at,false,1_700_000_000_000L,1);
    }

    private static void anchor(DeadReckoning ins, long time, long now) {
        check(ins.anchor(gps(time),SPEED,.1,90,0,now),"straight east GNSS anchor");
    }

    private static void vibration() {
        DeadReckoning ins=new DeadReckoning();
        anchor(ins,START,START);
        double lastSpeed=SPEED, peakStep=0;
        for (int i=1;i<=40;i++) {
            long now=START+i*STEP;
            double acceleration=4*Math.sin((i-1)*Math.PI/2);
            check(ins.acceleration(now,acceleration,0,now),"5 Hz horizontal vibration remains valid");
            double speed=ins.estimate(now).speedMetersPerSecond;
            peakStep=Math.max(peakStep,Math.abs(speed-lastSpeed));
            lastSpeed=speed;
        }
        check(Math.abs(peakStep-.10)<1e-9,"5 Hz vibration produces 0.10 m/s steps: "+peakStep);
    }

    private static void tiltedVerticalVibration() {
        DeadReckoning ins=new DeadReckoning();
        anchor(ins,START,START);
        float c=(float)Math.cos(.65), s=(float)Math.sin(.65);
        float[] attitude={c,0,s,0,1,0,-s,0,c};
        float[] device=new float[3];
        for (int i=1;i<=40;i++) {
            long now=START+i*STEP;
            double up=6*Math.sin(i*Math.PI/2);
            device[0]=(float)(-s*up);
            device[2]=(float)(c*up);
            double[] en=MotionTransform.horizontal(attitude,device,0);
            check(ins.acceleration(now,en[0],en[1],Math.abs(up),0,now),"vertical turbulence remains valid");
        }
        DeadReckoning.Prediction p=ins.predict(START+40*STEP);
        check(p!=null && Math.abs(p.speedMetersPerSecond-SPEED)<1e-7 && Math.abs(p.latitude)<1e-9,
                "vertical acceleration maps out of horizontal navigation");
        check(p.accelerationCoastedMillis==0,"a vertical bump without rapid rotation needs no coasting");
    }

    private static void horizontalGust() {
        DeadReckoning ins=new DeadReckoning();
        anchor(ins,START,START);
        for (int i=1;i<=40;i++) {
            long now=START+i*STEP;
            double east=i>=10 && i<20 ? 4 : 0;
            check(ins.acceleration(now,east,0,east,.05,now),"horizontal gust remains observable");
        }
        DeadReckoning.Prediction p=ins.predict(START+40*STEP);
        check(p!=null && Math.abs(p.speedMetersPerSecond-102)<1e-9 && p.accelerationCoastedMillis==0,
                "half a second of 4 m/s² contributes its full 2 m/s impulse");
    }

    private static DeadReckoning.Prediction handling(int reanchor) {
        DeadReckoning ins=new DeadReckoning();
        anchor(ins,START,START);
        long reset=START+7*STEP/2;
        for (int i=1;i<=40;i++) {
            long now=START+i*STEP;
            if (reanchor==1 && i==4) anchor(ins,reset,reset);
            double east=i==2?4:i==3?-4:i>=7?2:0;
            double gyro=i>=2 && i<=5?4:0;
            check(ins.acceleration(now,east,0,Math.abs(east),gyro,now),"handling does not break continuity");
            if (reanchor==2 && i==8) anchor(ins,reset,now);
            if (reanchor==0 && i==6) {
                DeadReckoning.Prediction p=ins.estimate(now);
                check(p.accelerationCoasting && p.accelerationCoastedMillis==250
                        && p.speedMetersPerSecond==SPEED, "handling coasts at GNSS velocity");
                double expected=5+.5*.3+1.5*.3*.3+.5*15*.25*.25;
                check(Math.abs(p.uncertaintyMeters-expected)<1e-9,"coasting raises uncertainty");
            }
            if (reanchor==0 && i==7) {
                DeadReckoning.Prediction p=ins.estimate(now);
                check(!p.accelerationCoasting && p.speedMetersPerSecond>SPEED,
                        "valid acceleration resumes after a brief disturbance without GNSS");
            }
        }
        return ins.predict(START+40*STEP);
    }

    private static void ambiguityAndGates() {
        DeadReckoning ambiguous=new DeadReckoning();
        anchor(ambiguous,START,START);
        check(ambiguous.acceleration(START+STEP,0,0,0,5,START+STEP),"rotation alone");
        check(ambiguous.acceleration(START+2*STEP,4,0,4,Double.NaN,START+2*STEP),"no current gyro");
        check(ambiguous.acceleration(START+3*STEP,4,0,4,5,START+3*STEP),"steady norm despite rotation");
        DeadReckoning.Prediction p=ambiguous.estimate(START+3*STEP);
        check(p.accelerationCoastedMillis==0 && Math.abs(p.speedMetersPerSecond-100.3)<1e-9,
                "either signal alone leaves acceleration observable");

        DeadReckoning excessive=new DeadReckoning();
        anchor(excessive,START,START);
        excessive.acceleration(START+STEP,0,0,0,0,START+STEP);
        check(!excessive.acceleration(START+2*STEP,16,0,16,5,START+2*STEP)
                && excessive.estimate(START+2*STEP)==null,"coasting cannot hide raw over-limit acceleration");

        DeadReckoning gap=new DeadReckoning();
        anchor(gap,START,START);
        gap.acceleration(START+STEP,0,0,0,0,START+STEP);
        gap.acceleration(START+2*STEP,4,0,4,5,START+2*STEP);
        long late=START+8*STEP;
        check(!gap.acceleration(late,0,0,0,5,late) && gap.estimate(late)==null,
                "handling does not bridge a sensor gap");
        anchor(gap,late+1,late+1);
        check(gap.acceleration(late+STEP,0,0,0,0,late+STEP)
                && gap.estimate(late+STEP).accelerationCoastedMillis==0,"new anchor starts with fresh uncertainty");
    }

    public static void main(String[] args) {
        vibration();
        tiltedVerticalVibration();
        horizontalGust();
        DeadReckoning.Prediction recovered=handling(0), immediate=handling(1), delayed=handling(2);
        double uncertaintyAfterRecovery=12+.5*15*.25*.25+(15*.25)*1.7;
        check(recovered!=null && recovered.accelerationCoastedMillis==250
                && Math.abs(recovered.uncertaintyMeters-uncertaintyAfterRecovery)<1e-9,
                "coasting uncertainty persists after acceleration resumes");
        check(Math.abs(recovered.speedMetersPerSecond-103.4)<1e-9,"sustained acceleration passes after handling");
        check(delayed!=null && immediate!=null && delayed.accelerationCoastedMillis==125
                && delayed.longitude==immediate.longitude && delayed.speedMetersPerSecond==immediate.speedMetersPerSecond
                && delayed.uncertaintyMeters==immediate.uncertaintyMeters,
                "delayed GNSS replay applies only disturbance intervals after the new anchor");
        ambiguityAndGates();
        System.out.println("Inertial disturbance tests passed");
    }
}
