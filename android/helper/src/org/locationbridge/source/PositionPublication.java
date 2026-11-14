package org.locationbridge.source;

/** Publishes measured or predicted positions with one monotonic output timeline. */
final class PositionPublication {
    static final class Output {
        final Selector.Fix measured;
        final DeadReckoning.Prediction prediction;
        final long elapsed, sequence;
        Output(Selector.Fix measured, DeadReckoning.Prediction prediction, long elapsed,long sequence) {
            this.measured=measured; this.prediction=prediction; this.elapsed=elapsed; this.sequence=sequence;
        }
    }
    private Output last;
    synchronized Output select(Selector.Fix measured,DeadReckoning.Prediction prediction,long now) {
        if (measured!=null && (!Selector.valid(measured,now)
                || now-measured.elapsed>("fused".equals(measured.source)?5_000_000_000L:10_000_000_000L))) measured=null;
        if (prediction!=null && (prediction.elapsed>now || prediction.anchorElapsed<=0
                || now-prediction.elapsed>500_000_000L
                || prediction.elapsed-prediction.anchorElapsed<=1_500_000_000L)) prediction=null;
        if (measured!=null) prediction=null;
        if (measured==null && prediction==null) return null;
        long elapsed=measured!=null?measured.elapsed:prediction.elapsed;
        if (last!=null) {
            if (elapsed<last.elapsed) return null;
            if (elapsed==last.elapsed) {
                boolean sameKind=measured!=null && last.measured!=null && measured.source.equals(last.measured.source)
                        || prediction!=null && last.prediction!=null;
                return sameKind?last:null;
            }
        }
        last=new Output(measured,prediction,elapsed,last==null?1:last.sequence+1);
        return last;
    }
}
