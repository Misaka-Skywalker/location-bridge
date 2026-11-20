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
    private TermuxCommand pendingTermuxCommand;
    private static final int TERMUX_PERMISSION_REQUEST=24;
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
        buildPanel(state == null ? "POS" : state.getString("instrument_page", "POS"));
        if (bridgeStart) {
            if (fineGranted()) startLocation(true);
            else requestPermissionsIfNeeded();
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("instrument_page", panel.currentPage());
    }
    private void buildPanel(String currentPage) {
        ScrollView scroll=new ScrollView(this);
        panel=new McduPanel(this,this::runPanelAction,()->scroll.scrollTo(0,0));
        panel.selectPage(currentPage);
        scroll.setFillViewport(true);
        scroll.addView(panel);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(McduPanel.BODY);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(panel.pageBar,new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);
        // Respect status/navigation bars on devices enforcing edge-to-edge layouts.
        root.setOnApplyWindowInsetsListener((view,insets)->{
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
        if (AuthProtocol.isHex64(saved)) panel.pairing("");
        else if (AuthProtocol.isHex64(candidateToken)) panel.pairing(pairingFingerprint(candidateToken, false));
        else panel.pairing(Language.tr("尚未配对"));
    }
    private String pairingFingerprint(String token, boolean paired) {
        try {
            return Language.tr(paired ? "已配对，指纹：" : "待确认配对，指纹：") + AuthProtocol.fingerprint(token);
        } catch (Exception e) { return Language.tr("指纹计算失败"); }
    }
    private void confirmPairing() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (preferences.contains(TOKEN)) {
            candidateToken = null;
            new android.app.AlertDialog.Builder(new android.view.ContextThemeWrapper(this,android.R.style.Theme_Material_Dialog_Alert))
                    .setTitle(Language.tr("Termux 配对信息"))
                    .setMessage(pairingFingerprint(preferences.getString(TOKEN,null),true))
                    .setNegativeButton(Language.tr("返回"),null).show();
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
                boolean paired=getSharedPreferences(PREFS,MODE_PRIVATE).contains(TOKEN);
                String[] labels={Language.tr("定位和通知权限"),Language.tr(paired?"Termux 配对信息":"与 Termux 配对"),Language.tr("系统定位设置"),Language.tr("GNSS 连续跟踪"),Language.tr("启动 iPad 连接（USB）"),Language.tr("启动 iPad 连接（Wi-Fi）"),Language.tr("Termux 启动设置"),"Language / 语言"};
                String[] actions={"AUTH","PAIR","LOC","TRACK","TERM","CONNECT_WIFI","TERM_HELP","LANG"};
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
                            buildPanel(panel.currentPage());
                            if (LocationService.instance != null) LocationService.instance.refreshNotification();
                            BleReceiverService.refreshNotification();
                        }).setNegativeButton(Language.tr("返回"),null).show();
                break;
            case "BLE": startActivity(new Intent(this,BleReceiverActivity.class));break;
            case "TERM": case "CONNECT": requestBridgeCommand(TermuxCommand.USB);break;
            case "CONNECT_WIFI": requestBridgeCommand(TermuxCommand.WIFI);break;
            case "DISCONNECT": requestBridgeCommand(TermuxCommand.STOP);break;
            case "BRIDGE_STATUS": requestBridgeCommand(TermuxCommand.STATUS);break;
            case "TERM_HELP": showTermuxHelp();break;
            case "DR":
                boolean enabled=!getSharedPreferences("native_inertial",MODE_PRIVATE).getBoolean("enabled",true);
                LocationService service=LocationService.instance;
                if(service!=null) service.setInertialEnabled(enabled);
                else getSharedPreferences("native_inertial",MODE_PRIVATE).edit().putBoolean("enabled",enabled).apply();
                Toast.makeText(this,enabled?Language.tr("已启用惯性推算"):Language.tr("已停用惯性推算"),Toast.LENGTH_SHORT).show();
                updateStatus();break;
        }
    }
    private void openTermux() {
        Intent terminal=getPackageManager().getLaunchIntentForPackage("com.termux");
        if(terminal!=null) startActivity(terminal);
        else Toast.makeText(this,Language.tr("请先安装 Termux"),Toast.LENGTH_LONG).show();
    }
    private void showTermuxHelp() {
        new android.app.AlertDialog.Builder(new android.view.ContextThemeWrapper(this,android.R.style.Theme_Material_Dialog_Alert))
                .setTitle(Language.tr("Termux 启动设置"))
                .setMessage(Language.tr("首次使用需允许定位助手在 Termux 中运行命令，并在 Termux 中开启应用调用。此权限允许访问 Termux 环境和文件；本助手只调用固定的定位桥脚本。完成一次性设置后，可直接点击“连接 iPad”，无需桌面小组件。"))
                .setPositiveButton(Language.tr("检查启动接口"),(d,w)->requestBridgeCommand(TermuxCommand.STATUS))
                .setNeutralButton(Language.tr("仅打开 Termux"),(d,w)->openTermux())
                .setNegativeButton(Language.tr("返回"),null).show();
    }
    private void requestBridgeCommand(TermuxCommand command) {
        if(getPackageManager().getLaunchIntentForPackage("com.termux")==null) {
            Toast.makeText(this,Language.tr("请先安装 Termux"),Toast.LENGTH_LONG).show();return;
        }
        if(command.isStart()) {
            if(!AuthProtocol.isHex64(getSharedPreferences(PREFS,MODE_PRIVATE).getString(TOKEN,null))) {
                Toast.makeText(this,Language.tr("请先前往“设置 → 与 Termux 配对”"),Toast.LENGTH_LONG).show();return;
            }
            JSONObject state=BridgeStatusReceiver.snapshot(this);
            String phase=state.optString("phase");
            if(state.optBoolean("fresh") && (phase.equals("connecting") || phase.equals("connected") || phase.equals("reconnecting") || phase.equals("stopping"))) {
                Toast.makeText(this,Language.tr("连接已在运行，请先断开再切换"),Toast.LENGTH_LONG).show();return;
            }
        }
        if(checkSelfPermission(TermuxBridge.PERMISSION)!=PackageManager.PERMISSION_GRANTED) {
            new android.app.AlertDialog.Builder(new android.view.ContextThemeWrapper(this,android.R.style.Theme_Material_Dialog_Alert))
                    .setTitle(Language.tr("允许调用 Termux"))
                    .setMessage(Language.tr("连接按钮需要“在 Termux 中运行命令”权限。该权限允许定位助手访问 Termux 环境和文件；本助手仅运行固定的桥接启动、停止和状态脚本。"))
                    .setPositiveButton(Language.tr("继续授权"),(d,w)->{
                        pendingTermuxCommand=command;
                        requestPermissions(new String[]{TermuxBridge.PERMISSION},TERMUX_PERMISSION_REQUEST);
                    }).setNegativeButton(Language.tr("取消"),null).show();
            return;
        }
        String error=TermuxBridge.launch(this,command);
        if(error!=null) Toast.makeText(this,Language.tr(error.equals("busy")?"启动请求已发送，请稍候":error.equals("permission_required")?"请授予 Termux 命令权限":error.equals("missing_termux")?"请先安装 Termux":"无法调用 Termux，请检查启动设置"),Toast.LENGTH_LONG).show();
        updateStatus();
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
        if(requestCode==TERMUX_PERMISSION_REQUEST) {
            TermuxCommand command=pendingTermuxCommand;pendingTermuxCommand=null;
            if(command!=null && checkSelfPermission(TermuxBridge.PERMISSION)==PackageManager.PERMISSION_GRANTED)
                requestBridgeCommand(command);
            else Toast.makeText(this,Language.tr(checkSelfPermission(TermuxBridge.PERMISSION)==PackageManager.PERMISSION_GRANTED?"授权完成，请再次点击连接按钮":"未获得权限；可在系统应用权限中允许 Termux 命令"),Toast.LENGTH_LONG).show();
            return;
        }
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
        panel.commandStatus(TermuxBridge.snapshot(this));
        try {panel.update(service==null?null:service.snapshot(),fineGranted(),enabled,bridge);}
        catch(Exception error){panel.update(null,fineGranted(),enabled,bridge);panel.fault(Language.tr("定位状态读取失败，请停止后重新启动"));}
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
