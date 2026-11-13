package org.locationbridge.source;

import java.util.function.BooleanSupplier;

/** Shared cancellation gate for every USB query and role command. */
final class UsbOperationGate {
    private final BooleanSupplier cancelled;
    UsbOperationGate(BooleanSupplier cancelled) { this.cancelled=cancelled; }
    void check() throws InterruptedException {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelled");
    }
}
