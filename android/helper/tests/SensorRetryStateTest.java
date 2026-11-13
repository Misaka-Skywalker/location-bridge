package org.locationbridge.source;

public final class SensorRetryStateTest {
    static void check(boolean value) { if(!value) throw new AssertionError(); }
    public static void main(String[] args) {
        SensorRetryState retry=new SensorRetryState();
        long first=retry.start();
        check(retry.fail(first)==2000);
        check(retry.valid(first));
        retry.success(first);
        check(retry.fail(first)==2000);
        check(retry.fail(first)==4000);
        check(retry.fail(first)==8000);
        check(retry.fail(first)==16000);
        check(retry.fail(first)==30000);
        check(retry.fail(first)==30000);
        retry.stop();
        check(!retry.valid(first));
        long next=retry.start();
        check(!retry.valid(first) && retry.valid(next));
        check(retry.fail(first)==0 && retry.fail(next)==2000);
        System.out.println("SensorRetryStateTest passed");
    }
}
