package org.locationbridge.source;
import java.util.*;
public final class BridgeStatusAuthTest {
 static void check(boolean ok,String s) { if(!ok) throw new AssertionError(s); }
 static Map<String,Object> report() {
  Map<String,Object> m=new HashMap<>();
  m.put("protocol",1); m.put("run_id","run-0123456789abcdef0123456789abcdef"); m.put("sent_elapsed_ms",100000L); m.put("sent_utc_ms",1000000L);
  m.put("phase","connected"); m.put("transport","usb"); m.put("mode","fusion"); m.put("connected",true); m.put("worker_state","running"); m.put("source","gps"); m.put("accuracy_m",5.0); m.put("accuracy_kind","reported"); m.put("fix_age_ms",5.0); m.put("last_sequence",1); m.put("clear_pending",false); m.put("issue","none"); return m;
 }
 public static void main(String[] args) throws Exception {
  String key="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", payload="YWJj";
  String sig=BridgeStatusAuth.sign(key,payload);
  check(sig.equals("d0f55aa11b646ed0def341e8164295222366c0d9e3e2158bf9051803c2b81c5e"),"known HMAC vector");
  check(BridgeStatusAuth.verify(key,payload,sig),"correct HMAC");
  check(!BridgeStatusAuth.verify("100102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",payload,sig),"wrong key");
  Map<String,Object> m=report(); check(BridgeStatusAuth.validReport(m),"valid schema");
  for(String issue:new String[]{"usb_role","usb_permission","usb_role_failed","usb_busy"}) {
   m.put("issue",issue); check(BridgeStatusAuth.validReport(m),"USB role status accepted");
  }
  m.put("issue","none");
  m.put("extra",1); check(!BridgeStatusAuth.validReport(m),"extra rejected"); m.remove("extra");
  m.put("protocol",true); check(!BridgeStatusAuth.validReport(m),"bool is not integer"); m.put("protocol",1);
  check(BridgeStatusAuth.acceptTime(100000,1000000,100001,1000001,99999,99999),"fresh timestamps");
  check(!BridgeStatusAuth.acceptTime(-1,1000000,100001,1000001,0,0),"negative");
  check(!BridgeStatusAuth.acceptTime(100002,1000000,100001,1000001,0,0),"future elapsed");
  check(!BridgeStatusAuth.acceptTime(80000,1000000,100001,1000001,0,0),"stale elapsed");
  check(!BridgeStatusAuth.acceptTime(100000,1000000,100001,1000001,100000,100000),"replay");
  check(!BridgeStatusAuth.acceptTime(100000,1002002,100001,1000001,0,0),"wall future beyond tolerance");
  check(!BridgeStatusAuth.acceptTime(100000,980000,100001,1000001,0,0),"wall stale");
  check(BridgeStatusAuth.acceptTime(100000,1000000,100001,1000001,200000,200000),"elapsed reset allows new boot");
  check(!BridgeStatusAuth.fresh(100001,1000001,108002,1008002),"stale report");
  check(!BridgeStatusAuth.fresh(100001,1000001,100002,1020000),"wall jump invalidates stored report");
  m.put("phase","stopped"); check(!BridgeStatusAuth.validReport(m),"stopped cannot be connected");
  m.put("connected",false); check(BridgeStatusAuth.validReport(m),"stopped disconnected valid");
  m.put("accuracy_m",Double.NaN); check(!BridgeStatusAuth.validReport(m),"nan rejected");
  System.out.println("BridgeStatusAuth tests passed");
 }
}
