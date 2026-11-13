package org.locationbridge.source;

/** Independent exponential registration retry, with elapsed-time deadlines. */
final class GnssRetry {
    private int delaySeconds;
    private long next;
    boolean ready(long now) { return now >= next; }
    void failed(long now) {
        delaySeconds = delaySeconds == 0 ? 2 : Math.min(30, delaySeconds * 2);
        next = now + delaySeconds * 1_000_000_000L;
    }
    void succeeded() { delaySeconds=0; next=0; }
}
