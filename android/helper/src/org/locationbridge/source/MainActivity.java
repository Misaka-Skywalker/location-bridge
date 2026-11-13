package org.locationbridge.source;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private McduPanel panel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean bridgeStart;
    private long bridgeDeadline;
    private String bridgeAuth;
    private String candidateToken;
    static final String PREFS = "location_bridge_pairing";
    static final String TOKEN = "token";
    private final Runnable refresh = new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); Language.init(this);
        receiveCandidate(getIntent());
        receiveBridgeStart(getIntent());
        getWindow().setStatusBarColor(McduPanel.BODY);
        getWindow().setNavigationBarColor(McduPanel.BODY);
        buildPanel();
        if (bridgeStart) {
            if (fineGranted()) startLocation(true);
            else requestPermissionsIfNeeded();
        }
    }
    private void buildPanel() {
        String currentPage=panel==null?"POS":panel.currentPage();
        panel=new McduPanel(this,this::runPanelAction);
        panel.selectPage(currentPage);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);scroll.setBackgroundColor(McduPanel.BODY);scroll.addView(panel);
        setContentView(scroll);
        // Respect status/navigation bars on devices enforcing edge-to-edge layouts.
        scroll.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            return insets;
        });
        updatePairStatus();updateStatus();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        receiveCandidate(intent);
        updatePairStatus();
        receiveBridgeStart(intent);
        if (bridgeStart) {
            if (fineGranted()) startLocation(true); else requestPermissionsIfNeeded();
        }
    }
    private boolean validBridgeStart() {
        String token = getSharedPreferences(PREFS, MODE_PRIVATE).getString(TOKEN, null);
        if (!AuthProtocol.isHex64(token)) return false;
        try {
            return new AuthProtocol(token).verifyStart(bridgeDeadline, SystemClock.elapsedRealtime(), bridgeAuth);
        } catch (Exception e) { return false; }
    }
    private void receiveBridgeStart(Intent intent) {
        boolean requested = intent.getBooleanExtra("bridge_start", false);
        bridgeDeadline = intent.getLongExtra("bridge_deadline", 0);
        bridgeAuth = intent.getStringExtra("bridge_auth");
        intent.removeExtra("bridge_auth");
        intent.removeExtra("bridge_start");
        intent.removeExtra("bridge_deadline");
        bridgeStart = requested && validBridgeStart();
        if (!bridgeStart) bridgeAuth = null;
    }
    private void receiveCandidate(Intent intent) {
        String offered = intent.getStringExtra("pairing_token");
        intent.removeExtra("pairing_token");
        if (offered == null) return;
        if (getSharedPreferences(PREFS, MODE_PRIVATE).contains(TOKEN)) {
            candidateToken = null;
            Toast.makeText(this, Language.tr("已配对，不会更换密钥"), Toast.LENGTH_LONG).show();
        } else if (AuthProtocol.isHex64(offered)) {
            candidateToken = offered;
            Toast.makeText(this, Language.tr("收到配对请求，请前往“设置 → 与 Termux 配对”确认"), Toast.LENGTH_LONG).show();
        } else {
            candidateToken = null;
            Toast.makeText(this, Language.tr("配对请求无效"), Toast.LENGTH_LONG).show();
        }
    }
    private void updatePairStatus() {
        if (panel == null) return;
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(TOKEN, null);
        String token = saved != null ? saved : candidateToken;
        if (!AuthProtocol.isHex64(token)) { panel.pairing(Language.tr("尚未配对")); return; }
        try {
            panel.pairing((saved != null ? Language.tr("已配对，指纹：") : Language.tr("待确认配对，指纹：")) + AuthProtocol.fingerprint(token));
        } catch (Exception e) { panel.pairing(Language.tr("指纹计算失败")); }
    }
    private void confirmPairing() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (preferences.contains(TOKEN)) {
            candidateToken = null;
            Toast.makeText(this, Language.tr("已配对，不会更换密钥"), Toast.LENGTH_LONG).show();
        } else if (candidateToken == null) {
            Toast.makeText(this, Language.tr("请先从 Termux 发起配对"), Toast.LENGTH_LONG).show();
        } else {
            boolean saved = preferences.edit().putString(TOKEN, candidateToken).commit();
            candidateToken = null;
            Toast.makeText(this, saved ? Language.tr("配对成功") : Language.tr("配对保存失败"), Toast.LENGTH_LONG).show();
        }
        updatePairStatus();
    }
    private void runPanelAction(String action) {
        switch(action) {
            case "RUN": startLocation(false); break;
            case "STOP": stopService(new Intent(this,LocationService.class));handler.postDelayed(this::updateStatus,150);break;
            case "AUTH": requestPermissionsIfNeeded();break;
            case "PAIR": confirmPairing();break;
            case "LOC": startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));break;
            case "SETTINGS":
                String[] labels={Language.tr("定位和通知权限"),Language.tr("与 Termux 配对"),Language.tr("系统定位设置"),Language.tr("GNSS 连续跟踪"),Language.tr("打开 Termux（连接 iPad）"),"Language / 语言"};
                String[] actions={"AUTH","PAIR","LOC","TRACK","TERM","LANG"};
                new android.app.AlertDialog.Builder(new android.view.ContextThemeWrapper(this,android.R.style.Theme_Material_Dialog_Alert))
                        .setTitle(Language.tr("设置"))
                        .setItems(labels,(dialog,index)->runPanelAction(actions[index]))
                        .setNegativeButton(Language.tr("返回"),null).show();
                break;
            case "TRACK":
                boolean tracking=getSharedPreferences("native_gnss",MODE_PRIVATE).getBoolean("full_tracking",true);
                new android.app.AlertDialog.Builder(new android.view.ContextThemeWrapper(this,android.R.style.Theme_Material_Dialog_Alert))
                        .setTitle(Language.tr("GNSS 连续跟踪"))
                        .setSingleChoiceItems(new String[]{Language.tr("启用：优先连续观测，功耗较高"),Language.tr("关闭：由系统控制跟踪策略")},tracking?0:1,(dialog,index)->{
                            boolean on=index==0;
                            LocationService current=LocationService.instance;
                            if(current!=null) current.setFullTrackingEnabled(on);
                            else getSharedPreferences("native_gnss",MODE_PRIVATE).edit().putBoolean("full_tracking",on).apply();
                            dialog.dismiss();updateStatus();
                        }).setNegativeButton(Language.tr("返回"),null).show();
                break;
            case "LANG":
                new android.app.AlertDialog.Builder(new android.view.ContextThemeWrapper(this,android.R.style.Theme_Material_Dialog_Alert))
                        .setTitle("Language / 语言")
                        .setSingleChoiceItems(new String[]{"简体中文", "English"}, Language.isEnglish()?1:0, (dialog,index)->{
                            Language.set(this,index==1);
                            dialog.dismiss();
                            buildPanel();
                            if (LocationService.instance != null) LocationService.instance.refreshNotification();
                            BleReceiverService.refreshNotification();
                        }).setNegativeButton(Language.tr("返回"),null).show();
                break;
            case "BLE": startActivity(new Intent(this,BleReceiverActivity.class));break;
            case "TERM":
                Intent termux=getPackageManager().getLaunchIntentForPackage("com.termux");
                if(termux!=null) startActivity(termux);
                break;
            case "DR":
                boolean enabled=!getSharedPreferences("native_inertial",MODE_PRIVATE).getBoolean("enabled",true);
                LocationService service=LocationService.instance;
                if(service!=null) service.setInertialEnabled(enabled);
                else getSharedPreferences("native_inertial",MODE_PRIVATE).edit().putBoolean("enabled",enabled).apply();
                Toast.makeText(this,enabled?Language.tr("已启用短时惯性推算"):Language.tr("已停用惯性推算"),Toast.LENGTH_SHORT).show();
                updateStatus();break;
        }
    }
    private boolean fineGranted() { return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    private void requestPermissionsIfNeeded() {
        ArrayList<String> permissions = new ArrayList<>();
        if (!fineGranted()) { permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION); permissions.add(Manifest.permission.ACCESS_FINE_LOCATION); }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), 1);
        else if (bridgeStart) startLocation(true);
        else Toast.makeText(this,Language.tr("定位和通知权限均已授予"),Toast.LENGTH_SHORT).show();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        // After an external launch requiring permission, stay on this page for a clear user action.
        bridgeStart = false;
        bridgeAuth = null;
        updateStatus();
    }
    private void startLocation(boolean returnToTermux) {
        if (!AuthProtocol.isHex64(getSharedPreferences(PREFS, MODE_PRIVATE).getString(TOKEN, null))) {
            Toast.makeText(this, Language.tr("请先前往“设置 → 与 Termux 配对”"), Toast.LENGTH_LONG).show();
            return;
        }
        if (returnToTermux && !validBridgeStart()) {
            bridgeStart = false;
            bridgeAuth = null;
            Toast.makeText(this, Language.tr("启动请求已过期，请重新启动定位桥"), Toast.LENGTH_LONG).show();
            return;
        }
        if (!fineGranted()) { requestPermissionsIfNeeded(); return; }
        LocationManager manager = (LocationManager)getSystemService(LOCATION_SERVICE);
        if (!manager.isLocationEnabled()) {
            Toast.makeText(this, Language.tr("请先打开系统定位"), Toast.LENGTH_LONG).show(); return;
        }
        try {
            Intent serviceIntent = new Intent(this, LocationService.class);
            if (returnToTermux) serviceIntent.putExtra("bridge_deadline", bridgeDeadline);
            startForegroundService(serviceIntent);
            if (returnToTermux) bridgeAuth = null;
            if (returnToTermux) returnToTermuxWhenReady(30);
        } catch (RuntimeException e) { Toast.makeText(this, Language.tr("启动失败：") + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show(); }
    }
    private void returnToTermuxWhenReady(int attempts) {
        LocationService service = LocationService.instance;
        if (service != null && service.isReady()) {
            Intent termux = getPackageManager().getLaunchIntentForPackage("com.termux");
            if (termux != null) startActivity(termux);
        } else if (attempts > 0) {
            handler.postDelayed(() -> returnToTermuxWhenReady(attempts - 1), 100);
        } else {
            Toast.makeText(this, Language.tr("服务未启动，请检查系统定位和权限"), Toast.LENGTH_LONG).show();
        }
    }
    private void updateStatus() {
        if(panel==null) return;
        LocationService service=LocationService.instance;
        boolean enabled=getSharedPreferences("native_inertial",MODE_PRIVATE).getBoolean("enabled",true);
        JSONObject bridge=BridgeStatusReceiver.snapshot(this);
        try {panel.update(service==null?null:service.snapshot(),fineGranted(),enabled,bridge);}
        catch(Exception error){panel.update(null,fineGranted(),enabled,bridge);panel.fault(Language.tr("定位状态读取失败，请停止后重新启动"));}
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
