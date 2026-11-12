package org.locationbridge.source;

/** Only approved position events can replace the published candidate; raw diagnostics live elsewhere. */
final class ExternalPositionStore {
    static final class Candidate {
        final LnsDecoder.LocationSpeed position;
        final long sequence, receivedUtcMillis, receivedNanos;
        Candidate(LnsDecoder.LocationSpeed position,long sequence,long wall,long nanos) {
            this.position=position;this.sequence=sequence;receivedUtcMillis=wall;receivedNanos=nanos;
        }
    }
    private final ExternalUseGate gate;
    private Candidate accepted;
    ExternalPositionStore(ExternalUseGate gate) { this.gate=gate; }
    void onLocation(byte[] payload,long sequence,long wall,long nanos) {
        try {
            LnsDecoder.LocationSpeed point=LnsDecoder.decodeLocationSpeed(payload);
            if (!point.validPosition) {
                gate.invalidatePosition(nanos);accepted=null;return;
            }
            if (point.validPosition && gate.onPosition(point.utcMillis,wall,nanos))
                accepted=new Candidate(point,sequence,wall,nanos);
        } catch (IllegalArgumentException ignored) { /* Invalid input never replaces an approved point. */ }
    }
    Candidate selected(long nowNanos,long nowUtcMillis) {
        return "eligible".equals(gate.reason(nowNanos,nowUtcMillis)) ? accepted : null;
    }
    void clear() { accepted=null; }
}
