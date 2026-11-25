package org.locationbridge.source;

public final class InertialTurnReplayTest {
    private static final long SECOND=1_000_000_000L, START=100*SECOND;
    private static final long STEP=SECOND/20, ATTITUDE_DELAY=SECOND/10;
    private static final double AIRSPEED=50, WIND_EAST=20;
    private static final double EQUATOR_EAST_RADIUS=6378137.0, EQUATOR_NORTH_RADIUS=6335439.327;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Selector.Fix gps(long time, double east, double north) {
        return new Selector.Fix("gps",Math.toDegrees(north/EQUATOR_NORTH_RADIUS),
                Math.toDegrees(east/EQUATOR_EAST_RADIUS),4,time,false,
                1_700_000_000_000L+(time-START)/1_000_000L,1);
    }

    private static float[] orientation(double heading, double roll) {
        double c=Math.cos(heading), s=Math.sin(heading), r=Math.cos(roll), u=Math.sin(roll);
        return new float[]{(float)(c*r),(float)s,(float)(c*u), (float)(-s*r),(float)c,(float)(-s*u),
                (float)-u,0,(float)r};
    }

    private static float[] toDevice(float[] r, double east, double north, double up) {
        return new float[]{(float)(r[0]*east+r[3]*north+r[6]*up),
                (float)(r[1]*east+r[4]*north+r[7]*up),(float)(r[2]*east+r[5]*north+r[8]*up)};
    }

    private static void turnAnchor(DeadReckoning ins, double rate, long time, long now) {
        double seconds=(time-START)/(double)SECOND, angle=rate*seconds;
        double east=WIND_EAST*seconds+AIRSPEED/rate*(1-Math.cos(angle));
        double north=AIRSPEED/rate*Math.sin(angle);
        double velocityEast=WIND_EAST+AIRSPEED*Math.sin(angle), velocityNorth=AIRSPEED*Math.cos(angle);
        double course=(Math.toDegrees(Math.atan2(velocityEast,velocityNorth))+360)%360;
        check(ins.anchor(gps(time,east,north),Math.hypot(velocityEast,velocityNorth),.2,course,0,now),
                "GNSS ground velocity initializes the turn");
    }

    private static DeadReckoning.Prediction turn(double rate, boolean withGyroscope, int anchorDelaySteps) {
        DeadReckoning ins=new DeadReckoning();
        MotionOrientationHistory attitude=new MotionOrientationHistory();
        turnAnchor(ins,rate,START,START);
        long end=START+30*SECOND, recalibration=START+15*SECOND;
        double roll=Math.atan(AIRSPEED*rate/9.81);
        float[] angularRate={(float)(rate*Math.sin(roll)),0,(float)(-rate*Math.cos(roll))};
        if (withGyroscope) {
            attitude.addGyroscope(START-STEP,angularRate,20);
            attitude.addGyroscope(START,angularRate,20);
        }
        for (long time=START+STEP;time<=end;time+=STEP) {
            double angle=rate*(time-START)/SECOND;
            attitude.add(time-ATTITUDE_DELAY,orientation(angle-rate*ATTITUDE_DELAY/SECOND,roll));
            if (withGyroscope) attitude.addGyroscope(time,angularRate,20);
            float[] linear=toDevice(orientation(angle,roll),
                    AIRSPEED*rate*Math.cos(angle),-AIRSPEED*rate*Math.sin(angle),0);
            double[] en=MotionTransform.horizontal(attitude.at(time),linear,0);
            check(ins.acceleration(time,en[0],en[1],time),"continuous turn sensor frame");
            if (anchorDelaySteps>=0 && time==recalibration+anchorDelaySteps*STEP)
                turnAnchor(ins,rate,recalibration,time);
        }
        DeadReckoning.Prediction prediction=ins.predict(end);
        check(prediction!=null && prediction.elapsed==end,"turn continues during GNSS dropout");
        return prediction;
    }

    private static double endError(DeadReckoning.Prediction prediction, double rate) {
        double east=WIND_EAST*30+AIRSPEED/rate, north=AIRSPEED/Math.abs(rate);
        return Math.hypot(Math.toRadians(prediction.longitude)*EQUATOR_EAST_RADIUS-east,
                Math.toRadians(prediction.latitude)*EQUATOR_NORTH_RADIUS-north);
    }

    private static void coordinatedTurn(double rate) {
        DeadReckoning.Prediction unassisted=turn(rate,false,-1);
        DeadReckoning.Prediction assisted=turn(rate,true,-1);
        double oldError=endError(unassisted,rate), newError=endError(assisted,rate);
        check(oldError>4 && newError<1.6,"turn endpoint with 100ms attitude skew: "+oldError+"m → "+newError+"m");
        double expectedCourse=rate>0?90:270;
        check(Math.abs(assisted.bearingDegrees-expectedCourse)<.2,"aircraft turn follows ground course");
        double expectedSpeed=rate>0?70:30;
        check(Math.abs(assisted.speedMetersPerSecond-expectedSpeed)<.1,"crosswind changes ground speed");
    }

    private static void handling(boolean rollOnly,double initialSpeed,double rate) {
        DeadReckoning ins=new DeadReckoning();
        MotionOrientationHistory attitude=new MotionOrientationHistory();
        float[] angularRate=rollOnly?new float[]{0,(float)rate,0}:new float[]{0,0,(float)-rate};
        check(ins.anchor(gps(START,0,0),initialSpeed,.2,0,0,START),"straight north anchor");
        attitude.addGyroscope(START-STEP,angularRate,20);
        attitude.addGyroscope(START,angularRate,20);
        for (long time=START+STEP;time<=START+4*SECOND;time+=STEP) {
            double angle=1+rate*(time-START)/SECOND, older=angle-rate*ATTITUDE_DELAY/SECOND;
            attitude.add(time-ATTITUDE_DELAY,rollOnly?orientation(0,older):orientation(older,0));
            attitude.addGyroscope(time,angularRate,20);
            float[] linear=toDevice(rollOnly?orientation(0,angle):orientation(angle,0),0,1,.5);
            double[] en=MotionTransform.horizontal(attitude.at(time),linear,0);
            check(ins.acceleration(time,en[0],en[1],time),"continuous straight path");
        }
        DeadReckoning.Prediction prediction=ins.predict(START+4*SECOND);
        check(prediction!=null && Math.abs(prediction.longitude)<1e-10,"device yaw or roll does not turn the path");
        check(Math.abs(prediction.speedMetersPerSecond-initialSpeed-4)<1e-6,"measured forward acceleration remains");
    }

    public static void main(String[] args) {
        double rate=Math.PI/60;
        coordinatedTurn(rate);
        coordinatedTurn(-rate);
        handling(false,20,.5);
        handling(true,20,.5);
        handling(false,2,.12);
        DeadReckoning.Prediction immediate=turn(rate,true,0), delayed=turn(rate,true,20);
        check(delayed.latitude==immediate.latitude && delayed.longitude==immediate.longitude
                && delayed.bearingDegrees==immediate.bearingDegrees && delayed.anchorElapsed==immediate.anchorElapsed,
                "delayed GNSS anchor replays the same synchronized turn frames");
        System.out.println("Inertial turn replay tests passed");
    }
}
