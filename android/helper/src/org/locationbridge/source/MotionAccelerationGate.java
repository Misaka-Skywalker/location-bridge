package org.locationbridge.source;

/** Orders accelerometer samples and separates late pre-anchor data from failures. */
final class MotionAccelerationGate {
    private long last;
    String observe(long elapsed,long reference,long now,boolean referenceValid) {
        if (elapsed<=last) return "acceleration_out_of_order";
        last=elapsed;
        if (!referenceValid || now<reference || now-reference>10_000_000_000L) return "reference_expired";
        if (elapsed<reference) return "ignore";
        if (elapsed-reference>10_000_000_000L) return "reference_expired";
        return "accept";
    }
    void clear() { last=0; }
}
