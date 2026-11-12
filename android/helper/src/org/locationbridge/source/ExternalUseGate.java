package org.locationbridge.source;

/** Eligibility gate for already-validated external position events. No Android dependencies. */
final class ExternalUseGate {
    private static final long MAX_AGE_MS = 10_000L;
    private static final long NANOS_PER_MS = 1_000_000L;
    private boolean manualExcluded;
    private boolean txtPending;
    private long lastTxtNanos;
    private long lastSeenUtc;
    private long acceptanceCutoffNanos;
    private long acceptedSampleUtc;
    private long acceptedSampleNanos;

    ExternalUseGate(boolean manualExcluded) { this.manualExcluded = manualExcluded; }

    synchronized void setManualExcluded(boolean excluded, long nowNanos) {
        if (nowNanos <= 0) throw new IllegalArgumentException("invalid monotonic time");
        if (manualExcluded == excluded) return;
        manualExcluded = excluded;
        if (!excluded) acceptanceCutoffNanos = Math.max(acceptanceCutoffNanos, nowNanos);
        // Re-enabling waits for a later event; neither cached position nor TXT is cleared.
        acceptedSampleUtc = 0;
        acceptedSampleNanos = 0;
    }

    synchronized void resetSession() {
        txtPending = false;
        lastTxtNanos = 0;
        lastSeenUtc = 0;
        acceptedSampleUtc = 0;
        acceptedSampleNanos = 0;
    }

    synchronized void invalidatePosition(long receivedNanos) {
        if (receivedNanos <= 0) throw new IllegalArgumentException("invalid monotonic time");
        acceptedSampleUtc = 0;
        acceptedSampleNanos = 0;
        acceptanceCutoffNanos = Math.max(acceptanceCutoffNanos, receivedNanos);
    }

    synchronized void onTxt(long receivedNanos) {
        if (receivedNanos <= 0 || receivedNanos < lastTxtNanos) return;
        lastTxtNanos = receivedNanos;
        txtPending = true;
        acceptedSampleUtc = 0;
        acceptedSampleNanos = 0;
    }

    synchronized boolean onPosition(Long sampleUtcMillis, long receivedUtcMillis, long receivedNanos) {
        if (sampleUtcMillis == null || sampleUtcMillis <= 0 || receivedUtcMillis < sampleUtcMillis || receivedNanos <= 0) return false;
        long ageMs = receivedUtcMillis - sampleUtcMillis;
        if (ageMs > MAX_AGE_MS || sampleUtcMillis <= lastSeenUtc) return false;
        long ageNanos = ageMs * NANOS_PER_MS;
        if (ageNanos >= receivedNanos) return false;
        long sampleNanos = receivedNanos - ageNanos;
        lastSeenUtc = sampleUtcMillis;
        if (manualExcluded || sampleNanos <= acceptanceCutoffNanos
                || (acceptedSampleNanos != 0 && sampleNanos <= acceptedSampleNanos)
                || (txtPending && sampleNanos <= lastTxtNanos)) return false;
        txtPending = false;
        acceptedSampleUtc = sampleUtcMillis;
        acceptedSampleNanos = sampleNanos;
        return true;
    }

    synchronized String reason(long nowNanos, long nowUtcMillis) {
        if (manualExcluded) return "manual_excluded";
        if (txtPending) return "static_txt";
        if (acceptedSampleNanos == 0) return "waiting_position";
        if (nowNanos < acceptedSampleNanos || nowUtcMillis < acceptedSampleUtc
                || nowNanos - acceptedSampleNanos > MAX_AGE_MS * NANOS_PER_MS) return "stale_position";
        return "eligible";
    }

    synchronized boolean manualExcluded() { return manualExcluded; }
    synchronized boolean waitingAfterTxt() { return txtPending; }
}
