package org.locationbridge.source;

/** Generation-scoped, bounded sensor registration retry schedule. */
final class SensorRetryState {
    private long generation;
    private boolean active;
    private int failures;
    synchronized long start() { active=true;failures=0;return ++generation; }
    synchronized void stop() { active=false;failures=0;generation++; }
    synchronized boolean valid(long token) { return active && generation==token; }
    synchronized void success(long token) { if(valid(token)) failures=0; }
    synchronized long fail(long token) {
        if(!valid(token)) return 0;
        long delay=Math.min(30_000L,2_000L << Math.min(failures,4));
        failures++; return delay;
    }
}
