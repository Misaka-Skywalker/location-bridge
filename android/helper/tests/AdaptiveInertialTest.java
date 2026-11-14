package org.locationbridge.source;

/** Degraded sensors increase uncertainty without imposing an elapsed-time cutoff. */
public final class AdaptiveInertialTest {
    static final long S=1_000_000_000L,T=100*S;
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static DeadReckoning seeded() {
        DeadReckoning d=new DeadReckoning();
        check(d.anchor(new Selector.Fix("gps",0,0,5,T,false,1000,1),50,1,90,0,T),"anchor");
        for(long t=T+100_000_000;t<=T+4*S;t+=100_000_000) d.acceleration(t,0,0,t);
        return d;
    }
    public static void main(String[] args) {
        DeadReckoning d=seeded();
        double original=d.predict(T+4*S).uncertaintyMeters;
        d.setUncertaintyGrowthScale(2);
        double degraded=d.predict(T+4*S).uncertaintyMeters;
        check(degraded>original,"degraded sensors increase uncertainty without a time cutoff");
        d.setUncertaintyGrowthScale(1);
        check(d.predict(T+4*S).uncertaintyMeters==degraded,"quality recovery cannot erase accumulated uncertainty");
        for(long t=T+4*S+100_000_000;t<=T+20*S;t+=100_000_000) check(d.acceleration(t,0,0,t),"continuous samples");
        check(d.predict(T+20*S)!=null,"degraded path continues beyond old horizon");
        System.out.println("AdaptiveInertial tests passed");
    }
}
