package org.locationbridge.source;

/** Guards stale speed, invalid motion and request flapping across devices. */
public final class AdaptiveLocationProfileTest {
    static final long S=1_000_000_000L;
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        long t=100*S;
        AdaptiveLocationProfile p=new AdaptiveLocationProfile();
        check(p.agingRate(t)==20,"unknown motion keeps conservative baseline");
        check(p.observeGpsMotion(250,1,t,t),"usable GNSS speed accepted");
        check(p.agingRate(t)==252,"age penalty includes high-speed displacement");
        check(!p.observeGpsMotion(0,1,t,t+S),"replayed speed cannot change profile");
        check(!p.observeGpsMotion(Double.NaN,1,t+S,t+S),"nonfinite speed rejected");
        check(!p.observeGpsMotion(1,20,t+S,t+S),"uncertain velocity rejected");
        check(!p.observeGpsMotion(1,1,t+10*S,t+S),"future speed rejected");
        check(p.agingRate(t+6*S)==20,"old velocity cannot permanently bias choices");
        check(p.updateIntervalMillis(false,t)==500,"acquisition requests faster fixes");
        check(p.observeGpsMotion(.1,.2,t+S,t+S),"new stationary velocity accepted");
        check(p.updateIntervalMillis(true,t+S)==500,"profile does not flap immediately");
        check(p.updateIntervalMillis(true,t+5*S)==1000,"stable low speed settles to one second");
        check(p.updateIntervalMillis(false,t+6*S)==1000,"one missing fix does not churn subscriptions");
        check(p.updateIntervalMillis(false,t+10*S)==500,"continued loss reenters acquisition");
        p.clearMotion();
        check(p.agingRate(t+10*S)==20,"disabled GNSS drops speed influence");
        System.out.println("AdaptiveLocationProfile tests passed");
    }
}
