package org.locationbridge.source;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class GnssDispatchTest {
    static void check(boolean v, String text) { if (!v) throw new AssertionError(text); }
    public static void main(String[] args) throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        GnssDispatch d = new GnssDispatch(clock::get);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        d.execute(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        check(entered.await(1,TimeUnit.SECONDS), "worker started");
        for (int i=0; i<20; i++) d.execute(calls::incrementAndGet);
        check(d.queuedCount() <= 8, "bounded queue");
        clock.addAndGet(2_000_000_001L);
        d.execute(done::countDown);
        release.countDown();
        check(done.await(1,TimeUnit.SECONDS), "fresh event delivered");
        check(calls.get()==0, "expired callbacks dropped");
        d.close(); d.execute(calls::incrementAndGet);
        check(calls.get()==0, "stopped dispatcher rejects events");
        GnssRetry retry = new GnssRetry();
        check(retry.ready(1),"first try immediate");
        retry.failed(1); check(!retry.ready(2_000_000_000L),"backoff applies");
        check(retry.ready(2_000_000_001L),"first retry at two seconds");
        retry.failed(2_000_000_001L); check(!retry.ready(6_000_000_000L),"backoff doubles");
        retry.succeeded(); check(retry.ready(1),"reset after success");
        System.out.println("GNSS dispatch tests passed");
    }
}
