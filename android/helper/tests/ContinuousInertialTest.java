package org.locationbridge.source;

public final class ContinuousInertialTest {
    static final long S=1_000_000_000L,T=100*S;
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    static Selector.Fix gps(long time,double east){return new Selector.Fix("gps",0,Math.toDegrees(east/6378137.0),5,time,false,1700000000000L+(time-T)/1000000,1);}
    static void advance(DeadReckoning d,long start,long end){for(long t=start+S/20;t<=end;t+=S/20)check(d.acceleration(t,0,0,t),"continuous sensor path");}
    public static void main(String[] args){
        DeadReckoning d=new DeadReckoning();check(d.anchor(gps(T,0),300,1,90,0,T),"moving anchor");
        advance(d,T,T+3600*S);
        DeadReckoning.Prediction p=d.predict(T+3600*S);
        check(p!=null && p.predictionAgeMillis==3600000,"one hour without an artificial time limit");
        check(p.uncertaintyMeters>150 && Double.isFinite(p.uncertaintyMeters),"growing model error retained");
        check(Math.abs(p.longitude-Math.toDegrees(1080000/6378137.0))<1e-6,"long eastbound geodetic displacement");
        check(new PositionPublication().select(null,p,T+3600*S)!=null,"publication accepts long propagation");
        check(d.predict(T+3600*S+S)==null,"missing new IMU samples still stops output");
        DeadReckoning delayed=new DeadReckoning();check(delayed.anchor(gps(T,0),100,1,90,0,T),"correction anchor");
        advance(delayed,T,T+3*S);
        check(delayed.anchor(gps(T+2700_000_000L,260),100,1,90,0,T+3*S),"delayed correction accepted");
        DeadReckoning.Deviation delta=delayed.lastDeviation();
        check(delta!=null && Math.abs(delta.eastMeters-10)<.1 && Math.abs(delta.northMeters)<.01,"time-aligned pre-correction residual, east positive");
        check(delta.elapsed==T+2700_000_000L && delta.predictionAgeMillis==2700,"residual timestamp and age retained");
        check(Math.abs(delayed.estimate(T+3*S).longitude-Math.toDegrees(290/6378137.0))<1e-8,"delayed anchor replays subsequent motion");
        check(delayed.predict(T+3*S)==null && delayed.estimate(T+3*S)!=null,"independent INS display is available during GNSS alignment");
        check(!delayed.anchor(gps(T+3*S,300),Double.NaN,1,90,0,T+3*S),"bad velocity cannot recalibrate");
        advance(delayed,T+3*S,T+5*S);
        check(delayed.predict(T+5*S)!=null,"rejected velocity does not destroy existing INS");
        DeadReckoning stationary=new DeadReckoning();
        check(stationary.anchor(gps(T,0),0,.2,Double.NaN,Double.NaN,T),"reliable stationary velocity needs no track");
        advance(stationary,T,T+12*S);
        check(stationary.predict(T+12*S).speedMetersPerSecond==0,"stationary propagation remains stationary");
        System.out.println("ContinuousInertial tests passed");
    }
}
