package org.locationbridge.source;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Executes bounded dumpsys requests; only observed post-switch state establishes success. */
final class UsbHostController {
    static final class Result {
        final String result, data, power; final boolean changed;
        Result(String result, boolean changed, UsbPortState state) { this.result=result;this.changed=changed;data=state.data;power=state.power; }
    }
    private static final ReentrantLock ACTIVE = new ReentrantLock();
    private final Context context;
    private final UsbOperationGate gate;
    UsbHostController(Context context, BooleanSupplier cancelled) { this.context=context; this.gate=new UsbOperationGate(cancelled); }
    private String command(long deadline, String... args) throws Exception {
        gate.check();
        long left=Math.min(3000,deadline-SystemClock.elapsedRealtime()); if(left<=0) throw new Exception("deadline");
        String[] command=new String[args.length+2];command[0]="/system/bin/dumpsys";command[1]="usb";System.arraycopy(args,0,command,2,args.length);
        gate.check();
        Process p=new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        final boolean[] overflow={false};
        Thread reader=new Thread(()->{ try(InputStream in=p.getInputStream()) { byte[] b=new byte[4096]; int n; while((n=in.read(b))!=-1) { if(bytes.size()+n>262144){overflow[0]=true;p.destroy();break;} bytes.write(b,0,n); } } catch(Exception ignored) {} });
        reader.start(); boolean done=p.waitFor(left,TimeUnit.MILLISECONDS); if(!done) p.destroyForcibly(); reader.join(500);
        if(!done||reader.isAlive()||overflow[0]||p.exitValue()!=0) {
            android.util.Log.w("LocationBridgeUsb","USB query process failed: done="+done+", reader="+reader.isAlive()+", overflow="+overflow[0]+", exit="+(done?p.exitValue():-1));
            throw new Exception("dumpsys failed");
        }
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
    private UsbPortState read(long deadline) throws Exception {
        String text=command(deadline);
        UsbPortState state=UsbPortState.parse(text);
        if("unavailable".equals(state.result)) android.util.Log.w("LocationBridgeUsb","USB state unavailable: chars="+text.length()+", manager="+text.contains("port_manager={")+", permissionDenied="+text.contains("Permission Denial"));
        return state;
    }
    Result run(String operation,long deadline) {
        UsbPortState state=UsbPortState.failure("unavailable"); boolean attempted=false;
        if(!ACTIVE.tryLock()) return new Result("unavailable",false,state);
        try {
            gate.check();
            if(context.checkSelfPermission(Manifest.permission.DUMP)!=PackageManager.PERMISSION_GRANTED) return new Result("permission_required",false,state);
            long limit=Math.min(deadline,SystemClock.elapsedRealtime()+14500);
            state=read(limit);
            if(!"device".equals(state.result)||"inspect".equals(operation)) return new Result(state.result,false,state);
            String id=state.id, original=state.power;
            String[] choices={original,"source".equals(original)?"sink":"source"};
            for(String power:choices) {
                gate.check();
                if(!state.supportsHost(power)) continue;
                if(SystemClock.elapsedRealtime()>=deadline || SystemClock.elapsedRealtime()>=limit) break;
                gate.check();
                attempted=true;
                try { command(limit,"set-port-roles",id,power,"host"); }
                catch(InterruptedException cancelled) { throw cancelled; }
                catch(Exception ignored) { /* The request may have reached the service; verify state. */ }
                long until=Math.min(limit,SystemClock.elapsedRealtime()+4000);
                do {
                    gate.check();
                    Thread.sleep(Math.min(300,Math.max(1,until-SystemClock.elapsedRealtime())));
                    gate.check();
                    state=read(limit);
                    if("host".equals(state.result)&&id.equals(state.id)) return new Result("host",true,state);
                    if(!state.canContinueNegotiation(id)) return new Result("switch_failed",true,state);
                } while(SystemClock.elapsedRealtime()<until);
                // Never issue a fallback while the original port is disconnected.
                if(!"device".equals(state.result) || !id.equals(state.id)) return new Result("switch_failed",true,state);
            }
            return new Result(attempted?"switch_failed":"unsupported",attempted,state);
        } catch(Exception e) {
            android.util.Log.w("LocationBridgeUsb","USB check failed: "+e.getClass().getSimpleName());
            return new Result(attempted?"switch_failed":"unavailable",attempted,state);
        }
        finally { ACTIVE.unlock(); }
    }
}
