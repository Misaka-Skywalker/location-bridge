package org.locationbridge.source;

/** A degraded orientation path must shorten even an already active propagation. */
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
        check(d.predict(T+4*S)!=null,"good sensor path supports fourth second");
        d.setMaximumHorizonNanos(3*S);
        check(d.predict(T+4*S)==null,"quality reduction immediately stops old propagation");
        d.setMaximumHorizonNanos(8*S);
        check(d.predict(T+4*S)==null,"quality recovery cannot resurrect old anchor");
        DeadReckoning capped=seeded(); capped.setMaximumHorizonNanos(100*S);
        for(long t=T+4*S+100_000_000;t<=T+8*S;t+=100_000_000) capped.acceleration(t,0,0,t);
        check(capped.predict(T+8*S+1)==null,"requested horizon cannot exceed absolute limit");
        DeadReckoning invalid=seeded();invalid.setMaximumHorizonNanos(0);
        check(invalid.predict(T+4*S)==null,"invalid limit fails closed");
        System.out.println("AdaptiveInertial tests passed");
    }
}
