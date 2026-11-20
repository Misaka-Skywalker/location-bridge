package org.locationbridge.source;

import java.util.HashMap;
import java.util.Map;

/** Publishes measured or predicted positions with a separate output sequence. */
final class PositionPublication {
    static final class Output {
        final Selector.Fix measured;
        final DeadReckoning.Prediction prediction;
        final String source;
        final long elapsed, sequence;
        Output(Selector.Fix measured, DeadReckoning.Prediction prediction, String source,long elapsed,long sequence) {
            this.measured=measured; this.prediction=prediction; this.elapsed=elapsed; this.sequence=sequence;
            this.source=source;
        }
    }
    private final Map<String,Long> lastElapsed = new HashMap<>();
    private long sequence;
    private Output last;
    synchronized Output select(Selector.Fix measured,DeadReckoning.Prediction prediction,long now) {
        if (measured!=null && (!Selector.valid(measured,now)
                || now-measured.elapsed>("fused".equals(measured.source)?5_000_000_000L:10_000_000_000L))) measured=null;
        if (prediction!=null && (prediction.elapsed>now || prediction.anchorElapsed<=0
                || now-prediction.elapsed>500_000_000L
                || prediction.elapsed-prediction.anchorElapsed<=1_500_000_000L)) prediction=null;
        if (measured!=null) prediction=null;
        if (measured==null && prediction==null) { last=null; return null; }
        String source=measured!=null?measured.source:"inertial";
        long elapsed=measured!=null?measured.elapsed:prediction.elapsed;
        if (last!=null && elapsed==last.elapsed && source.equals(last.source)) return last;
        if (elapsed<=lastElapsed.getOrDefault(source,0L)) return null;
        lastElapsed.put(source,elapsed);
        last=new Output(measured,prediction,source,elapsed,++sequence);
        return last;
    }
}
