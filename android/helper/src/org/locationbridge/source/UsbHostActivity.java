package org.locationbridge.source;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;
import org.json.JSONObject;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;

/** Authenticated one-shot USB inspection/repair, with a loopback result channel. */
public final class UsbHostActivity extends Activity {
    private static final LinkedHashMap<String,Long> NONCES=new LinkedHashMap<>();
    private static synchronized boolean consume(String nonce,long deadline,long now) {
        Iterator<Map.Entry<String,Long>> it=NONCES.entrySet().iterator();
        while(it.hasNext()) if(it.next().getValue()<=now) it.remove();
        if(NONCES.containsKey(nonce)||NONCES.size()>=256) return false;
        NONCES.put(nonce,deadline); return true;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); Language.init(this);
        TextView view=new TextView(this); view.setPadding(32,48,32,32);view.setTextSize(18);
        view.setText(Language.tr("正在检查 USB 连接…"));setContentView(view);
        if(state!=null) { finish(); return; }
        Intent intent=getIntent(); String operation=intent.getStringExtra("usb_operation"), nonce=intent.getStringExtra("usb_nonce"), signature=intent.getStringExtra("usb_auth");
        long deadline=intent.getLongExtra("usb_deadline",0);int port=intent.getIntExtra("usb_port",0);
        intent.removeExtra("usb_auth");intent.removeExtra("usb_nonce");
        String key=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).getString(MainActivity.TOKEN,null);
        long now=SystemClock.elapsedRealtime();
        try {
            if(!("inspect".equals(operation)||"prepare".equals(operation)||"auto".equals(operation))||deadline<=now||deadline-now>20000||port<1024||port>65535||nonce==null||!nonce.matches("[0-9a-f]{64}")||signature==null||!signature.matches("[0-9a-f]{64}")||!AuthProtocol.isHex64(key)) throw new Exception("invalid");
            AuthProtocol auth=new AuthProtocol(key);
            byte[] actual=AuthProtocol.decode(signature), expected=AuthProtocol.decode(auth.sign("usb\n"+operation+"\n"+deadline+"\n"+port+"\n"+nonce));
            if(!MessageDigest.isEqual(actual,expected)||!consume(nonce,deadline,now)) throw new Exception("invalid");
            new Thread(()->execute(operation,deadline,port,nonce,auth),"usb-host-request").start();
        } catch(Exception e) { view.setText(Language.tr("请求无效或已过期")); view.postDelayed(this::finish,1200); }
    }
    private void execute(String operation,long deadline,int port,String nonce,AuthProtocol auth) {
        try(Socket socket=new Socket()) {
            int timeout=(int)Math.max(1,Math.min(2000,deadline-SystemClock.elapsedRealtime()));
            if(SystemClock.elapsedRealtime()>=deadline) return;
            socket.connect(new InetSocketAddress("127.0.0.1",port),timeout);
            if(SystemClock.elapsedRealtime()>=deadline) return;
            AtomicBoolean cancelled=new AtomicBoolean(false);
            Thread watcher=new Thread(()->{
                try { socket.getInputStream().read(); } catch(Exception ignored) { }
                cancelled.set(true);
            },"usb-callback-watch");
            watcher.setDaemon(true);
            watcher.start();
            UsbHostController controller=new UsbHostController(getApplicationContext(),cancelled::get);
            UsbHostController.Result result=controller.run(operation,deadline);
            if(cancelled.get()) return;
            JSONObject data=new JSONObject().put("protocol",1).put("result",result.result).put("changed",result.changed).put("data_role",result.data).put("power_role",result.power);
            if ("auto".equals(operation)) {
                List<String> devices=List.of();
                if ("host".equals(result.result)) {
                    devices=controller.ipadCandidates();
                    long settle=Math.min(deadline-300,SystemClock.elapsedRealtime()+5000);
                    while (devices.isEmpty() && !cancelled.get() && SystemClock.elapsedRealtime()<settle) {
                        Thread.sleep(200);
                        devices=controller.ipadCandidates();
                    }
                }
                if(cancelled.get()) return;
                data.put("devices",new JSONArray(devices));
            }
            String payload=data.toString();
            JSONObject envelope=new JSONObject().put("nonce",nonce).put("payload",payload).put("mac",auth.sign("usb_result\n"+nonce+"\n"+payload));
            socket.getOutputStream().write((envelope.toString()+"\n").getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
        } catch(Exception ignored) { }
        finally { runOnUiThread(()->{ Intent termux=getPackageManager().getLaunchIntentForPackage("com.termux"); if(termux!=null) try {startActivity(termux);} catch(RuntimeException ignored) {} finish(); }); }
    }
}
