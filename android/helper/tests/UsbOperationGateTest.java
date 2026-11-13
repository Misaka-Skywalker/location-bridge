package org.locationbridge.source;

import java.util.concurrent.atomic.AtomicBoolean;

public final class UsbOperationGateTest {
    public static void main(String[] args) throws Exception {
        AtomicBoolean cancelled=new AtomicBoolean(true);
        UsbOperationGate gate=new UsbOperationGate(cancelled::get);
        int[] commands={0};
        try { gate.check(); commands[0]++; } catch(InterruptedException expected) { }
        if(commands[0]!=0) throw new AssertionError("cancelled initial command ran");
        cancelled.set(false);
        gate.check(); commands[0]++; // first request was sent
        cancelled.set(true); // callback closed before a fallback
        try { gate.check(); commands[0]++; } catch(InterruptedException expected) { }
        if(commands[0]!=1) throw new AssertionError("cancelled fallback ran");
        System.out.println("UsbOperationGateTest passed");
    }
}
