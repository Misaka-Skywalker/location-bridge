package org.locationbridge.source;

public final class NativeLocationPolicyTest {
    static final long S=1_000_000_000L;
    static Selector.Fix fix(String src,double latitude,double longitude,double accuracy,long time) {
        return new Selector.Fix(src,latitude,longitude,accuracy,time,false,1,time);
    }
    static void check(boolean b,String msg) { if (!b) throw new AssertionError(msg); }
    static String source(NativeLocationPolicy p,long now) { Selector.Fix f=p.selected(now); return f==null?"none":f.source; }
    static void cadenceReplay(double agingRate) {
        long t=100*S;
        NativeLocationPolicy p=new NativeLocationPolicy();
        p.setAgingRate(agingRate);
        check(p.offer(fix("gps",0,0,10,t),t),"cadence GPS start");
        for (int i=0;i<60;i++) {
            long fusedTime=t+i*S+S/2;
            check(p.offer(fix("fused",0,0.00018,10,fusedTime),fusedTime),"cadence fused");
            long gpsTime=t+(i+1)*S;
            check(p.offer(fix("gps",0,0,10,gpsTime),gpsTime),"cadence GPS");
            check(p.selected(gpsTime).elapsed==gpsTime,"incumbent update is timely");
        }
        System.out.println("cadence aging="+agingRate+" switches="+p.switchCount());
        check(p.switchCount()<=1,"equal quality staggered providers should not oscillate");
    }
    public static void main(String[] args) {
        cadenceReplay(20);
        cadenceReplay(252);
        long t=100*S;
        NativeLocationPolicy p=new NativeLocationPolicy();
        check(p.offer(fix("gps",30,104,10,t),t),"initial GPS accepted");
        check(p.offer(fix("fused",30,104,90,t+S),t+S),"fused stored");
        check(source(p,t+S).equals("gps"),"bad fused does not beat GPS");
        check(p.offer(fix("network",30,104,1,t+2*S),t+2*S),"network stored");
        check(source(p,t+2*S).equals("gps"),"network cannot challenge precise GPS");
        check(p.offer(fix("fused",30,104,2,t+3*S),t+3*S),"challenger update one");
        for(int i=0;i<10;i++) check(source(p,t+3*S).equals("gps"),"poll must not count");
        check(p.offer(fix("fused",30,104,2,t+4*S),t+4*S),"challenger update two");
        check(source(p,t+4*S).equals("fused"),"sustained challenger switches");
        check(p.switchCount()==1,"one switch");
        check(p.offer(fix("gps",30,104,10,t+5*S),t+5*S),"new GPS fallback available");
        check(source(p,t+10*S).equals("gps"),"expired fused immediately falls back to newer eligible GPS");

        NativeLocationPolicy q=new NativeLocationPolicy();
        check(q.offer(fix("gps",0,0,10,t),t),"initial");
        q.remove("gps",t+S);
        check(!q.offer(fix("gps",0,0,10,t+S),t+2*S),"disabled cutoff");
        check(q.offer(fix("network",0,0,300,t+2*S),t+2*S),"network fallback");
        check(source(q,t+2*S).equals("network"),"fallback selected");
        check(q.offer(fix("fused",0,0,5,t+S),t+3*S),"older but per-source new candidate may be assessed");
        check(q.selected(t+3*S).elapsed>=t+2*S,"older candidate never emitted");
        check(q.offer(fix("gps",0,.0027,15,t+3*S),t+3*S),"airliner speed plausible");
        check(!q.offer(fix("gps",0,1,15,t+4*S),t+4*S),"100km jump rejected");
        check(q.rejectedCount()>=2,"rejections counted");

        NativeLocationPolicy r=new NativeLocationPolicy();
        check(r.offer(fix("gps",0,0,5,t),t),"initial anchor");
        check(!r.offer(fix("gps",1,1,5,t+11*S),t+11*S),"first distant fix isolated");
        check(!r.offer(fix("gps",1.0001,1,5,t+12*S),t+12*S),"second isolated");
        check(r.offer(fix("gps",1.0002,1,5,t+13*S),t+13*S),"third consistent reacquires");
        check(source(r,t+13*S).equals("gps"),"reacquired source");
        check(Math.abs(r.selected(t+13*S).latitude-1.0002)<1e-9,"no smoothing");
        check(r.selected(t+24*S)==null,"stale none");
        check(!r.offer(new Selector.Fix("gps",0,0,5,t+25*S,true,1,1),t+25*S),"mock rejected");
        check(!r.offer(fix("gps",0,0,5,t+27*S),t+26*S),"future rejected");
        check(!r.offer(fix("gps",0,0,1001,t+26*S),t+26*S),"inaccurate rejected");
        NativeLocationPolicy interleaved=new NativeLocationPolicy();
        check(interleaved.offer(fix("network",0,0,300,t),t),"incumbent network");
        check(interleaved.offer(fix("gps",0,0,150,t+S),t+S),"GPS challenger first");
        check(interleaved.offer(fix("fused",0,0,90,t+S+S/2),t+S+S/2),"fused challenger interleaves");
        check(source(interleaved,t+S+S/2).equals("network"),"no challenger confirmed");
        check(interleaved.offer(fix("gps",0,0,140,t+2*S),t+2*S),"GPS challenger second");
        check(source(interleaved,t+2*S).equals("gps"),"different challenger cannot reset GPS confirmation");
        NativeLocationPolicy tier = new NativeLocationPolicy();
        check(tier.offer(fix("network",0,0,1,t),t),"precise reported network initial");
        check(tier.offer(fix("gps",0,0,20,t+S),t+S),"first healthy GPS challenge");
        for (int i=0;i<10;i++) check(source(tier,t+S).equals("network"),"network remains during confirmation");
        check(tier.offer(fix("gps",0,0,20,t+2*S),t+2*S),"second healthy GPS challenge");
        check(source(tier,t+2*S).equals("gps"),"healthy primary outranks network regardless of score");
        NativeLocationPolicy fusedTier = new NativeLocationPolicy();
        check(fusedTier.offer(fix("network",0,0,1,t),t),"network initial for fused");
        check(fusedTier.offer(fix("fused",0,0,20,t+S),t+S),"first fused primary");
        check(fusedTier.offer(fix("fused",0,0,20,t+S+S/2),t+S+S/2),"early second fused");
        check(source(fusedTier,t+S+S/2).equals("network"),"two samples under one second cannot switch");
        check(fusedTier.offer(fix("fused",0,0,20,t+2*S),t+2*S),"fused span reaches one second");
        check(source(fusedTier,t+2*S).equals("fused"),"healthy fused outranks network");
        NativeLocationPolicy latest = new NativeLocationPolicy();
        latest.setAgingRate(252);
        check(latest.offer(fix("gps",0,0,10,t),t),"latest incumbent start");
        check(latest.offer(fix("fused",0,0,30,t+2*S),t+2*S),"temporary age advantage");
        check(latest.offer(fix("gps",0,0,10,t+2*S+S/2),t+2*S+S/2),"incumbent catches up");
        check(latest.selected(t+2*S+S/2).elapsed==t+2*S+S/2,"new incumbent is immediately visible");
        check(latest.offer(fix("fused",0,0,30,t+3*S),t+3*S),"challenger compared with latest incumbent");
        check(source(latest,t+3*S).equals("gps"),"temporary age advantage does not confirm after incumbent update");
        NativeLocationPolicy metadata = new NativeLocationPolicy();
        check(!metadata.offer(new Selector.Fix("unknown",0,0,1,t,false,1,1),t),"unknown source rejected");
        check(!metadata.offer(new Selector.Fix("gps",0,0,1,t,false,0,1),t),"missing timestamp rejected");
        check(!metadata.offer(new Selector.Fix("gps",0,0,1,t,false,1,0),t),"missing sequence rejected");
        check(metadata.selected(t)==null,"invalid metadata does not leak into selected");
        NativeLocationPolicy fallback = new NativeLocationPolicy();
        PositionPublication publication = new PositionPublication();
        Selector.Fix network = fix("network",0,0,90,t);
        Selector.Fix fused = fix("fused",0,0,5,t+S);
        check(fallback.offer(fused,t+S),"fused initial");
        check(fallback.offer(network,t+S),"lagging network stored");
        check(publication.select(fallback.selected(t+S),null,t+S).measured==fused,"fused stays active");
        PositionPublication.Output fallbackOutput=publication.select(fallback.selected(t+7*S),null,t+7*S);
        check(fallbackOutput!=null && fallbackOutput.measured==network && fallbackOutput.elapsed==t,
                "fresh network fallback keeps its actual measurement time after fused expires");
        check(fallback.selected(t+11*S)==null,"network's own freshness window still applies");
        NativeLocationPolicy cautious=new NativeLocationPolicy();
        check(cautious.offer(fix("gps",0,0,5,t),t),"anchor for reacquisition");
        check(!cautious.offer(fix("gps",1,1,5,t+11*S),t+11*S),"first isolated");
        check(cautious.reason(t+11*S).equals("reacquiring"),"pending reanchor is visible");
        for(int i=0;i<20;i++) check(cautious.selected(t+11*S)==null,"poll cannot confirm isolated fix");
        check(!cautious.offer(fix("gps",1.0001,1,5,t+12*S),t+12*S),"second isolated");
        check(!cautious.offer(fix("network",1.0002,1,5,t+13*S),t+13*S),"different source cannot complete sequence");
        check(cautious.offer(fix("gps",1.0003,1,5,t+14*S),t+14*S),"alternating network cannot reset three GPS points");
        check(Math.abs(cautious.selected(t+14*S).latitude-1.0003)<1e-9,"reanchor source is correct");
        NativeLocationPolicy delayed=new NativeLocationPolicy();
        check(delayed.offer(fix("gps",0,0,5,t),t),"delayed reacquisition anchor");
        check(!delayed.offer(fix("gps",1,1,5,t+11*S),t+11*S),"old first candidate");
        check(!delayed.offer(fix("gps",1.0001,1,5,t+12*S),t+12*S),"old second candidate");
        check(!delayed.offer(fix("gps",1.0002,1,5,t+40*S),t+40*S),"expired chain cannot reanchor");
        check(!delayed.offer(fix("gps",1.0003,1,5,t+41*S),t+41*S),"second recent candidate");
        check(delayed.offer(fix("gps",1.0004,1,5,t+42*S),t+42*S),"three recent consistent points");
        NativeLocationPolicy delayedGps = new NativeLocationPolicy();
        check(delayedGps.offer(fix("fused",30,104,90,t),t),"lagged case fused starts");
        check(delayedGps.offer(fix("gps",30,104,5,t-S/10),t),"slightly older GPS passes quality check");
        check(delayedGps.offer(fix("fused",30,104.003,90,t+S),t+S),"airliner incumbent updates while confirming");
        check(delayedGps.selected(t+S).elapsed==t+S,"incumbent is published during confirmation");
        check(delayedGps.offer(fix("gps",30,104.0027,5,t+S-S/10),t+S),"second lagging GPS confirm");
        check(source(delayedGps,t+S).equals("gps"),"consistently lagging accurate GPS can take over");
        check(delayedGps.selected(t+S).elapsed==t+S-S/10,"lagging GPS keeps its actual timestamp");
        long lastPublished=delayedGps.selected(t+S).elapsed;
        for (int i=2; i<=4; i++) {
            long now=t+i*S;
            check(delayedGps.offer(fix("fused",30,104+.003*i,90,now),now),"later fused input");
            check(delayedGps.offer(fix("gps",30,104+.003*i,5,now-S/10),now),"later lagging GPS input");
            Selector.Fix out=delayedGps.selected(now);
            check(out != null && out.source.equals("gps") && out.elapsed>=lastPublished,"four lagged rounds stay monotonic GPS");
            lastPublished=out.elapsed;
        }
        NativeLocationPolicy equalTime = new NativeLocationPolicy();
        check(equalTime.offer(fix("fused",0,0,90,t),t),"equal time baseline");
        check(equalTime.offer(fix("gps",0,0,5,t),t),"same-timestamp different provider eligible for confirmation");
        check(equalTime.offer(fix("fused",0,.0027,90,t+S),t+S),"cache incumbent at normal flight speed");
        check(equalTime.offer(fix("gps",0,.0027,5,t+S),t+S),"second matching GPS accepted");
        check(source(equalTime,t+S).equals("gps"),"same timestamp GPS takes over after confirmation");
        NativeLocationPolicy holdTimeout = new NativeLocationPolicy();
        check(holdTimeout.offer(fix("fused",0,0,90,t),t),"hold timeout baseline");
        check(holdTimeout.offer(fix("gps",0,0,5,t-S/10),t),"start hold");
        check(holdTimeout.offer(fix("fused",0,.0027,90,t+S),t+S),"cache while waiting");
        check(holdTimeout.selected(t+S).elapsed==t+S,"challenger does not hold incumbent");
        check(holdTimeout.selected(t+2*S+1).elapsed==t+S,"latest incumbent remains available");
        NativeLocationPolicy holdRemoved = new NativeLocationPolicy();
        check(holdRemoved.offer(fix("fused",0,0,90,t),t),"remove baseline");
        check(holdRemoved.offer(fix("gps",0,0,5,t-S/10),t),"challenger before disable");
        check(holdRemoved.offer(fix("fused",0,.0027,90,t+S),t+S),"incumbent cached before disable");
        holdRemoved.remove("gps",t+S);
        check(holdRemoved.selected(t+S).elapsed==t+S,"disable immediately releases incumbent");
        check(!holdRemoved.offer(fix("gps",0,1,5,t+2*S),t+2*S),"jumping GPS not approved for height");
        NativeLocationPolicy blocked=new NativeLocationPolicy();
        check(!blocked.offer(fix("network",0,0,50,t),t-1),"future discarded");
        check(!blocked.offer(fix("gps",0,0,1000,t),t+11*S),"expired receipt discarded");
        check(blocked.selected(t+11*S)==null,"invalid samples cannot provide location");
        System.out.println("NativeLocationPolicy tests passed");
    }
}
