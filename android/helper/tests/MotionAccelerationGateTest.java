package org.locationbridge.source;
public final class MotionAccelerationGateTest {
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        long s=1_000_000_000L, anchor=100*s;
        MotionAccelerationGate gate=new MotionAccelerationGate();
        check(gate.observe(anchor-s/100,anchor,anchor,true).equals("ignore"),"queued pre-anchor sample ignored");
        check(gate.observe(anchor+s/100,anchor,anchor+s/100,true).equals("accept"),"new anchor continues");
        check(gate.observe(anchor,anchor,anchor+s/100,true).equals("acceleration_out_of_order"),"actual regression invalidates");
        check(gate.observe(anchor+11*s,anchor,anchor+11*s,true).equals("accept"),"reference remains usable without an elapsed-time cutoff");
        gate.clear();
        check(gate.observe(anchor,anchor,anchor,false).equals("reference_expired"),"missing reference invalidates");
        System.out.println("Motion acceleration gate tests passed");
    }
}
