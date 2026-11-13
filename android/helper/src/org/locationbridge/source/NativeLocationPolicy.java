package org.locationbridge.source;

import java.util.HashMap;
import java.util.Map;

/** Engineering policy, not an aviation navigation or integrity standard. */
final class NativeLocationPolicy {
    private static final long S=1_000_000_000L;
    private static final long GPS_MAX=10*S, FUSED_MAX=5*S;
    private final Map<String,Selector.Fix> candidates=new HashMap<>();
    private final Map<String,Long> seen=new HashMap<>(), cutoffs=new HashMap<>();
    private final Map<String,String> sourceReasons=new HashMap<>();
    private Selector.Fix anchor;
    private String current, previousSource;
    private static final class Challenge { int count; long first; }
    private final Map<String,Challenge> challenges = new HashMap<>();
    private String holdSource;
    private long holdUntil;
    private static final class Recovery {
        Selector.Fix last;
        long first;
        int count;
    }
    private final Map<String,Recovery> recoveries = new HashMap<>();
    private long rejects, switches;
    private double agingRate=20;
    private String lastReason="waiting";

    private static boolean supported(String source) { return "gps".equals(source)||"fused".equals(source)||"network".equals(source); }
    private static long maxAge(String source) { return "fused".equals(source)?FUSED_MAX:GPS_MAX; }
    private double score(Selector.Fix f,long now) { return f.accuracy + agingRate*((now-f.elapsed)/(double)S); }
    synchronized void setAgingRate(double metersPerSecond) {
        agingRate=Double.isFinite(metersPerSecond)?Math.max(20,Math.min(460,metersPerSecond)):20;
    }
    private static int priority(String source) { return "gps".equals(source)?0:"fused".equals(source)?1:2; }
    private static double distance(Selector.Fix a,Selector.Fix b) {
        double lat1=Math.toRadians(a.latitude),lat2=Math.toRadians(b.latitude);
        double dlat=lat2-lat1,dlon=Math.toRadians(b.longitude-a.longitude);
        double h=Math.sin(dlat/2)*Math.sin(dlat/2)+Math.cos(lat1)*Math.cos(lat2)*Math.sin(dlon/2)*Math.sin(dlon/2);
        return 2*6_371_000.0*Math.asin(Math.sqrt(Math.max(0,Math.min(1,h))));
    }
    private static boolean plausible(Selector.Fix a,Selector.Fix b) {
        if (b.elapsed <= a.elapsed) return false;
        double dt=(b.elapsed-a.elapsed)/(double)S;
        return distance(a,b)<=450.0*dt+3.0*(a.accuracy+b.accuracy)+100.0;
    }
    private static boolean plausibleEither(Selector.Fix a,Selector.Fix b) {
        double dt=Math.abs((double)b.elapsed-a.elapsed)/S;
        return distance(a,b)<=450.0*dt+3.0*(a.accuracy+b.accuracy)+100.0;
    }
    private boolean freshInput(Selector.Fix f,long now) {
        return f != null && Selector.valid(f,now) && f.accuracy<=1000 && now-f.elapsed<=maxAge(f.source);
    }
    private boolean fresh(Selector.Fix f,long now) {
        return freshInput(f,now) && (anchor==null || f==anchor || f.elapsed>anchor.elapsed);
    }
    private Selector.Fix best(long now) {
        boolean good=false;
        for(String s:new String[]{"gps","fused"}) {
            Selector.Fix f=candidates.get(s); if (fresh(f,now) && f.accuracy<=100) good=true;
        }
        Selector.Fix winner=null;
        for(String s:new String[]{"gps","fused","network"}) {
            Selector.Fix f=candidates.get(s);
            if (!fresh(f,now) || (good && "network".equals(s))) continue;
            if (winner==null || score(f,now)<score(winner,now) || (score(f,now)==score(winner,now) && priority(s)<priority(winner.source))) winner=f;
        }
        return winner;
    }
    private void activate(Selector.Fix f) {
        if (f==null) { current=null; return; }
        if (previousSource!=null && !previousSource.equals(f.source)) switches++;
        current=f.source; previousSource=f.source;
        if (anchor==null || f.elapsed>anchor.elapsed) anchor=f;
        lastReason="eligible";
        challenges.clear(); holdSource=null; holdUntil=0;
    }
    private void releaseHold() {
        if (holdSource != null) challenges.remove(holdSource);
        holdSource=null; holdUntil=0;
    }
    private Selector.Fix reconcile(long now) {
        if (holdSource != null) {
            Selector.Fix heldCandidate=candidates.get(holdSource);
            if (now < holdUntil && anchor != null && anchor.source.equals(current)
                    && freshInput(anchor,now) && freshInput(heldCandidate,now) && challenges.containsKey(holdSource)) return anchor;
            releaseHold();
        }
        Selector.Fix selected=current==null?null:candidates.get(current);
        if (!fresh(selected,now)) {
            current=null;
            Selector.Fix replacement=best(now);
            if (replacement!=null) { activate(replacement); return replacement; }
            recoveries.entrySet().removeIf(e -> now >= e.getValue().last.elapsed && now - e.getValue().last.elapsed > maxAge(e.getKey()));
            if (anchor!=null && now>=anchor.elapsed && now-anchor.elapsed>GPS_MAX)
                lastReason = recoveries.isEmpty() ? "stale" : "reacquiring";
            return null;
        }
        if (anchor==null || selected.elapsed>anchor.elapsed) anchor=selected;
        return selected;
    }
    private boolean reject(String source,String reason) {
        rejects++; lastReason=reason;
        if (supported(source)) sourceReasons.put(source,reason);
        return false;
    }
    synchronized boolean offer(Selector.Fix f,long now) {
        if (f==null || !supported(f.source)) return reject(null,"invalid");
        if (!Selector.valid(f,now) || f.timestamp <= 0 || f.sequence <= 0) return reject(f.source,"invalid");
        if (now-f.elapsed>maxAge(f.source)) return reject(f.source,"stale");
        if (f.accuracy>1000) return reject(f.source,"inaccurate");
        if (f.elapsed<=cutoffs.getOrDefault(f.source,0L) || f.elapsed<=seen.getOrDefault(f.source,0L)) return reject(f.source,"out_of_order");
        seen.put(f.source,f.elapsed);
        Selector.Fix selected=reconcile(now);
        boolean olderThanAnchor = anchor!=null && f.elapsed<=anchor.elapsed;
        if (anchor!=null && !plausibleEither(anchor,f)) {
            if (!olderThanAnchor && selected==null && now-anchor.elapsed>GPS_MAX) {
                Recovery recovery = recoveries.get(f.source);
                if (recovery == null || f.elapsed-recovery.first>GPS_MAX || !plausible(recovery.last,f)) {
                    recovery = new Recovery(); recovery.first=f.elapsed; recovery.count=1;
                    recoveries.put(f.source,recovery);
                } else recovery.count++;
                recovery.last=f;
                if (recovery.count<3 || f.elapsed-recovery.first<2*S) return reject(f.source,"reacquiring");
                // Drop pre-loss candidates, then re-anchor to this unchanged fix.
                candidates.clear(); current=null; anchor=null;
                recoveries.clear();
            } else return reject(f.source,"jump");
        } else { recoveries.clear(); }
        candidates.put(f.source,f); sourceReasons.put(f.source,"eligible");
        if (selected==null) { activate(best(now)); return true; }
        if (f.source.equals(current)) {
            if (holdSource == null && (anchor==null || f.elapsed>anchor.elapsed)) anchor=f;
            lastReason="eligible"; return true;
        }
        // Network is a fallback, never a challenger while a precise GPS/fused fix exists.
        boolean precise=false;
        for(String source:new String[]{"gps","fused"}) {
            Selector.Fix x=candidates.get(source); if (freshInput(x,now) && x.accuracy<=100) precise=true;
        }
        double incumbent=score(selected,now), proposed=score(f,now);
        boolean primaryOverFallback = "network".equals(selected.source)
                && ("gps".equals(f.source) || "fused".equals(f.source)) && f.accuracy <= 100;
        boolean improvedScore = proposed<=incumbent*.75 && incumbent-proposed>=5;
        if (!("network".equals(f.source) && precise) && (primaryOverFallback || improvedScore)) {
            Challenge challenge = challenges.get(f.source);
            if (challenge == null || f.elapsed-challenge.first>maxAge(f.source)) {
                challenge = new Challenge(); challenge.count=1; challenge.first=f.elapsed;
                challenges.put(f.source,challenge);
            } else challenge.count++;
            if (challenge.count>=2 && f.elapsed-challenge.first>=S && (anchor==null || f.elapsed>anchor.elapsed)) {
                activate(f);
            } else if (holdSource == null && anchor != null && current != null && anchor.source.equals(current)) {
                // Briefly preserve the last published fix while a lagging source confirms.
                holdSource=f.source; holdUntil=now+2*S;
            }
        } else {
            challenges.remove(f.source);
            if (f.source.equals(holdSource)) releaseHold();
        }
        return true;
    }
    synchronized void remove(String source,long now) {
        if (!supported(source) || now<=0) return;
        cutoffs.put(source,Math.max(now,cutoffs.getOrDefault(source,0L)));
        candidates.remove(source); sourceReasons.put(source,"disabled");
        challenges.remove(source);
        recoveries.remove(source);
        boolean held = source.equals(holdSource);
        if (held) releaseHold();
        if (source.equals(current)) { current=null; releaseHold(); }
        if (held || current == null) reconcile(now);
    }
    synchronized Selector.Fix selected(long now) { return reconcile(now); }
    synchronized String reason(long now) {
        if (reconcile(now)!=null) return "eligible";
        return lastReason;
    }
    synchronized String sourceReason(String source,long now) {
        if (!supported(source)) return "invalid";
        Selector.Fix f=candidates.get(source);
        if (f!=null && now>=f.elapsed && now-f.elapsed>maxAge(source)) return "stale";
        return sourceReasons.getOrDefault(source,"waiting");
    }
    synchronized long rejectedCount() { return rejects; }
    synchronized long switchCount() { return switches; }
}
