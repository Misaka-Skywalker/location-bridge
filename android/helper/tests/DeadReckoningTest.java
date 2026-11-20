package org.locationbridge.source;

public final class DeadReckoningTest {
    static final long S=1_000_000_000L, T=100*S;
    static void check(boolean c,String m) { if (!c) throw new AssertionError(m); }
    static Selector.Fix gps(long at,double accuracy) { return new Selector.Fix("gps",0,0,accuracy,at,false,1_700_000_000_000L,1); }
    static void step(DeadReckoning dr,long until,double east,double north) {
        for (long time=T+200_000_000L;time<=until;time+=200_000_000L) check(dr.acceleration(time,east,north,time),"valid sensor step");
    }
    public static void main(String[] args) {
        DeadReckoning dr=new DeadReckoning();
        check(!dr.anchor(gps(T,5),0,1,90,5,T),"low speed rejected");
        check(!dr.anchor(gps(T,5),300,Double.NaN,90,5,T),"unknown speed accuracy rejected");
        check(!dr.anchor(gps(T,5),300,1,Double.NaN,5,T),"unknown bearing rejected");
        check(dr.anchor(gps(T,5),300,1,90,0,T),"anchor 300 m/s east");
        step(dr,T+1_400_000_000L,0,0);
        check(dr.predict(T+1_600_000_000L)==null,"unpropagated sample younger than 1.5 seconds not emitted");
        for (long e=T+1_600_000_000L;e<=T+2*S;e+=200_000_000L) check(dr.acceleration(e,0,0,e),"remaining steps");
        DeadReckoning.Prediction p=dr.predict(T+2*S);
        check(p!=null && Math.abs(p.latitude)<1e-8 && p.longitude>0.0053 && p.longitude<0.0055,"300m/s east displacement");
        check(Math.abs(p.speedMetersPerSecond-300)<1e-8 && Math.abs(p.bearingDegrees-90)<1e-8,"east bearing/speed");
        check(Math.abs(p.uncertaintyMeters-13)<1e-8,"conservative uncertainty growth");
        check(p.elapsed==T+2*S && p.anchorElapsed==T && p.timestampMillis==1_700_000_002_000L,"timestamps retain origin");
        DeadReckoning.Prediction again=dr.predict(T+2*S+100_000_000L);
        check(again!=null && again.elapsed==p.elapsed && again.longitude==p.longitude && again.predictionAgeMillis==2000.0 && again.uncertaintyMeters==p.uncertaintyMeters,"polling does not advance position or model age");
        check(dr.predict(T+2*S+600_000_000L)==null,"stale sensor rejected");
        check(dr.lastAnchorNanos()==T,"a missing INS solution retains its last calibration time");
        check(!dr.acceleration(T+2*S+700_000_000L,0,0,T+2*S+700_000_000L),"stale sensor invalidated until reanchor");
        check(dr.anchor(gps(T+3*S,5),300,1,90,0,T+3*S),"new anchor recovers");
        check(dr.lastAnchorNanos()==T+3*S && dr.lastDeviation()==null,"a new anchor need not have a comparable old position");
        check(!dr.acceleration(T+3*S+300_000_000L,0,0,T+3*S+300_000_000L),"first sensor gap exceeds 250ms");
        check(!dr.anchor(gps(T+3*S,5),300,1,90,0,T+3*S),"old anchor cannot rearm");

        DeadReckoning turn=new DeadReckoning();
        check(turn.anchor(gps(T,5),100,1,0,0,T),"north anchor");
        step(turn,T+2*S,2,0);
        DeadReckoning.Prediction q=turn.predict(T+2*S);
        check(q!=null && q.longitude>0 && q.latitude>0 && q.bearingDegrees>0 && q.bearingDegrees<5,"east acceleration turns northbound motion");
        check(Math.abs(q.speedMetersPerSecond-Math.hypot(100,4))<0.01,"ENU acceleration integration");
        check(!turn.acceleration(T+2*S+200_000_000L,Double.NaN,0,T+2*S+200_000_000L),"nonfinite stops");
        check(turn.predict(T+2*S+200_000_000L)==null,"invalid sample inhibits output");

        DeadReckoning limit=new DeadReckoning();
        check(limit.anchor(gps(T,5),100,1,0,0,T),"horizon anchor");
        step(limit,T+8*S,0,0);
        check(limit.predict(T+8*S)!=null,"horizon inclusive");
        check(limit.predict(T+8*S+1)!=null,"no time-only cutoff");
        DeadReckoning uncertainty=new DeadReckoning();
        check(uncertainty.anchor(gps(T,50),300,5,90,15,T),"uncertainty anchor");
        step(uncertainty,T+2*S,0,0);
        check(uncertainty.predict(T+2*S)!=null && uncertainty.predict(T+2*S).uncertaintyMeters>150,"high model uncertainty remains visible");
        DeadReckoning invalid=new DeadReckoning();
        check(!invalid.anchor(new Selector.Fix("network",0,0,5,T,false,1,1),20,1,0,1,T),"only GPS anchors");
        check(!invalid.anchor(new Selector.Fix("gps",0,0,5,T,true,1,1),20,1,0,1,T),"mock anchor rejected");
        check(!invalid.anchor(new Selector.Fix("gps",85.01,0,5,T,false,1,1),20,1,0,1,T),"polar anchor rejected");
        check(!invalid.anchor(gps(T,51),20,1,0,1,T),"poor GPS accuracy rejected");
        check(!invalid.anchor(gps(T,5),20,1,0,1,T+1_500_000_001L),"old anchor rejected");
        check(invalid.anchor(gps(T,5),20,1,0,1,T),"good anchor starts");
        check(!invalid.acceleration(T+200_000_000L,16,0,T+200_000_000L),"excessive acceleration stops");
        check(invalid.predict(T+2*S)==null,"sensor failure does not rearm");
        check(invalid.anchor(gps(T+3*S,5),449,1,90,1,T+3*S),"new anchor rearms");
        check(!invalid.acceleration(T+3*S+200_000_000L,10,0,T+3*S+200_000_000L),"speed ceiling stops");
        DeadReckoning dateline=new DeadReckoning();
        check(dateline.anchor(new Selector.Fix("gps",0,179.999,5,T,false,1_700_000_000_000L,1),300,1,90,0,T),"dateline anchor");
        step(dateline,T+2*S,0,0);
        check(dateline.predict(T+2*S)!=null && dateline.predict(T+2*S).longitude < -179.99,"longitude wraps");
        DeadReckoning delayed=new DeadReckoning();
        for (int i=1;i<=4;i++) delayed.acceleration(T+i*100_000_000L,0,0,T+i*100_000_000L);
        check(delayed.anchor(gps(T,5),100,1,90,0,T+400_000_000L),"delayed anchor replays history");
        for (int i=5;i<=20;i++) check(delayed.acceleration(T+i*100_000_000L,0,0,T+i*100_000_000L),"continued history");
        check(delayed.predict(T+2*S)!=null,"400ms late GPS retains continuous sensor path");
        DeadReckoning missing=new DeadReckoning();
        missing.acceleration(T+400_000_000L,0,0,T+400_000_000L);
        check(!missing.anchor(gps(T,5),100,1,90,0,T+400_000_000L),"gap before history rejects delayed anchor");
        DeadReckoning crossed=new DeadReckoning();
        crossed.acceleration(T+100_000_000L,0,0,T+100_000_000L);
        crossed.invalidate("sensor_failure",T+150_000_000L);
        crossed.acceleration(T+200_000_000L,0,0,T+200_000_000L);
        check(!crossed.anchor(gps(T,5),100,1,90,0,T+200_000_000L),"invalidation prevents history reuse");
        DeadReckoning queued=new DeadReckoning();
        queued.acceleration(T+100_000_000L,0,0,T+100_000_000L);
        check(queued.anchor(gps(T+200_000_000L,5),100,1,90,0,T+200_000_000L),"on-time anchor");
        check(queued.acceleration(T+150_000_000L,0,0,T+200_000_000L),"queued pre-anchor sample is harmless");
        check(queued.acceleration(T+250_000_000L,0,0,T+250_000_000L),"post-anchor integration continues");
        DeadReckoning ordered=new DeadReckoning();
        ordered.acceleration(T+100_000_000L,0,0,T+100_000_000L);
        check(!ordered.acceleration(T+100_000_000L,0,0,T+100_000_000L),"duplicate sensor rejected");
        ordered.acceleration(T+200_000_000L,0,0,T+200_000_000L);
        check(!ordered.acceleration(T+150_000_000L,0,0,T+200_000_000L),"out-of-order sensor rejected");
        check(!ordered.anchor(gps(T,5),100,1,90,0,T+400_000_000L),"out-of-order clears history");
        System.out.println("DeadReckoning tests passed");
    }
}
