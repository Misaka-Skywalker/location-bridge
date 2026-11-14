package org.locationbridge.source;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;

/** Bounded, user-selected BLE discovery; the service owns the connection. */
public final class BleReceiverActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final UUID LNS = UUID.fromString("00001819-0000-1000-8000-00805f9b34fb");
    private static final class DeviceRow {
        BluetoothDevice device;
        Button button;
        String name;
        int rssi;
        boolean truncated;
        final Set<UUID> services = new LinkedHashSet<>();
    }
    private final Map<String, DeviceRow> devices = new LinkedHashMap<>();
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private LinearLayout list;
    private TextView status;
    private Button useButton;
    private int generation;
    private boolean scanAfterPermission;
    private final Runnable update = new Runnable() {
        @Override public void run() {
            JSONObject s = BleReceiverService.snapshot(BleReceiverActivity.this);
            JSONObject selection=s.optJSONObject("selection");
            boolean manual=BleReceiverService.manualExcluded(BleReceiverActivity.this);
            useButton.setText(manual ? Language.tr("恢复使用 iPhone 定位") : Language.tr("手动拒选 iPhone 定位（保持连接）"));
            String text = Language.tr("状态：") + stateLabel(s.optString("state", "stopped")) + Language.tr("\n连接：") + (s.optBoolean("connected") ? Language.tr("是") : Language.tr("否"))
                    + Language.tr("\n定位使用：") + useReason(selection == null ? "waiting_position" : selection.optString("reason"))
                    + Language.tr("\n收到 TXT：") + s.optLong("txt_packets",0) + Language.tr(" 次")
                    + Language.tr("\n数据包：") + s.optLong("packets", 0) + Language.tr("\n最近特征：") + s.optString("last_characteristic", Language.tr("无")) + inventory(s);
            if (!text.contentEquals(status.getText())) status.setText(text);
            if (callback != null) sortRows();
            main.postDelayed(this, 1000);
        }
    };
    private static String stateLabel(String state) {
        switch (state) {
            case "stopped": return Language.tr("已停止");
            case "disconnected": return Language.tr("未连接");
            case "connecting": return Language.tr("连接中");
            case "negotiating_mtu": return Language.tr("正在协商 MTU");
            case "discovering": return Language.tr("正在发现服务");
            case "subscribing": return Language.tr("正在订阅");
            case "subscribed": return Language.tr("已订阅，等待数据");
            case "receiving": return Language.tr("正在接收");
            case "bluetooth_unavailable": return Language.tr("蓝牙不可用");
            case "connect_failed": return Language.tr("连接失败");
            case "connect_timeout": return Language.tr("连接超时");
            case "mtu_timeout": return Language.tr("MTU 协商超时");
            case "permission_error": return Language.tr("权限错误");
            case "discovery_failed": return Language.tr("服务发现失败");
            case "discovery_timeout": return Language.tr("服务发现超时");
            case "subscription_failed": return Language.tr("订阅失败");
            case "unsupported": return Language.tr("不支持定位服务");
            case "operation_timeout": return Language.tr("操作超时");
            default: return state;
        }
    }
    private static String useReason(String reason) {
        switch (reason) {
            case "manual_excluded": return Language.tr("已手动拒选，连接继续保留");
            case "static_txt": return Language.tr("收到 TXT，等待新的有效位置");
            case "stale_position": return Language.tr("位置已过期，暂不使用");
            case "eligible": return Language.tr("可作为定位来源");
            case "disconnected": return Language.tr("未连接");
            default: return Language.tr("等待新的有效位置");
        }
    }
    private String inventory(JSONObject snapshot) {
        JSONArray all = snapshot.optJSONArray("services");
        if (all == null || all.length() == 0) return "";
        StringBuilder text = new StringBuilder(Language.tr("\n已发现服务与特征："));
        for (int i=0; i<all.length(); i++) {
            JSONObject service = all.optJSONObject(i); if (service == null) continue;
            text.append(Language.tr("\n服务 ")).append(service.optString("uuid", Language.tr("未知")));
            JSONArray chars = service.optJSONArray("characteristics"); if (chars == null) continue;
            for (int j=0; j<chars.length(); j++) {
                JSONObject ch = chars.optJSONObject(j); if (ch == null) continue;
                text.append("\n  ").append(ch.optString("uuid", Language.tr("未知"))).append(Language.tr(" 属性0x"))
                        .append(Integer.toHexString(ch.optInt("properties"))).append(ch.optBoolean("subscribed") ? Language.tr(" 已订阅") : "");
            }
        }
        if (snapshot.optBoolean("inventory_truncated")) text.append(Language.tr("\n列表已截断"));
        return text.toString();
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); Language.init(this);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density); page.setPadding(pad,pad,pad,pad);
        TextView title = new TextView(this); title.setText(Language.tr("蓝牙定位接收诊断 · 信号由强到弱")); title.setTextSize(22); page.addView(title);
        add(page,Language.tr("扫描 15 秒"),v -> beginScan());
        add(page,Language.tr("停止扫描"),v -> stopScan());
        add(page,Language.tr("断开并停止"),v -> { stopScan(); stopService(new Intent(this, BleReceiverService.class)); });
        useButton = new Button(this); useButton.setAllCaps(false);
        useButton.setOnClickListener(v -> {
            boolean excluded=BleReceiverService.manualExcluded(this);
            if (!BleReceiverService.setManualExcluded(this,!excluded)) Toast.makeText(this,Language.tr("无法保存拒选状态，请重试"),Toast.LENGTH_LONG).show();
        });
        page.addView(useButton);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this); content.addView(status);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); content.addView(list);
        scroll.addView(content); page.addView(scroll);
        setContentView(page);
    }
    private void add(LinearLayout parent, String text, View.OnClickListener click) {
        Button b = new Button(this); b.setText(text); b.setOnClickListener(click); parent.addView(b);
    }
    private boolean granted() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private void beginScan() {
        stopScan();
        if (!granted()) {
            scanAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 42);
            return;
        }
        BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, Language.tr("请先开启蓝牙"), Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { Toast.makeText(this,Language.tr("蓝牙扫描不可用"),Toast.LENGTH_LONG).show(); return; }
        devices.clear(); list.removeAllViews();
        final int scan = ++generation;
        callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) { main.post(() -> accept(scan,result)); }
            @Override public void onBatchScanResults(List<ScanResult> results) { for (ScanResult result : results) main.post(() -> accept(scan,result)); }
            @Override public void onScanFailed(int code) { main.post(() -> { if (generation == scan) { stopScan(); Toast.makeText(BleReceiverActivity.this,Language.tr("扫描失败：")+code,Toast.LENGTH_LONG).show(); } }); }
        };
        try {
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, settings, callback);
            main.postDelayed(() -> { if (generation == scan) { stopScan(); Toast.makeText(this,Language.tr("扫描已结束"),Toast.LENGTH_SHORT).show(); } }, 15000);
            Toast.makeText(this,Language.tr("正在扫描附近蓝牙设备"),Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) { stopScan(); Toast.makeText(this,Language.tr("缺少蓝牙权限"),Toast.LENGTH_LONG).show(); }
    }
    private void accept(int scan, ScanResult result) {
        if (generation != scan || callback == null || result == null) return;
        try {
            BluetoothDevice device = result.getDevice();
            String address = device.getAddress();
            DeviceRow row = devices.get(address);
            if (row == null) {
                if (devices.size() >= 50) return;
                row = new DeviceRow(); row.device = device;
                row.button = new Button(this); row.button.setAllCaps(false);
                final String chosen = address;
                row.button.setOnClickListener(v -> connect(chosen));
                devices.put(address, row); list.addView(row.button);
            }
            ScanRecord record = result.getScanRecord();
            String observedName = record == null ? null : record.getDeviceName();
            if (observedName != null && !observedName.trim().isEmpty()) row.name = observedName;
            row.rssi = result.getRssi();
            List<ParcelUuid> advertised = record == null ? null : record.getServiceUuids();
            if (advertised != null) for (ParcelUuid value : advertised) {
                if (value == null) continue;
                UUID uuid = value.getUuid();
                if (row.services.size() < 32 || LNS.equals(uuid)) row.services.add(uuid);
                else row.truncated = true;
            }
            StringBuilder label = new StringBuilder(row.name == null ? Language.tr("未命名设备") : row.name);
            label.append("\n").append(address).append(Language.tr("  信号：")).append(row.rssi).append(" dBm");
            if (row.services.isEmpty()) label.append(Language.tr("\n广播服务：未提供"));
            else {
                label.append(Language.tr("\n广播服务："));
                for (UUID uuid : row.services) {
                    label.append("\n").append(uuid);
                    if (LNS.equals(uuid)) label.append(Language.tr("（定位服务 1819）"));
                }
                if (row.truncated) label.append(Language.tr("\n其他广播服务未显示"));
            }
            label.append(Language.tr("\n（点击选择连接）"));
            if (!label.toString().contentEquals(row.button.getText())) row.button.setText(label.toString());
        } catch (SecurityException ignored) { stopScan(); }
    }
    private void connect(String address) {
        if (!devices.containsKey(address) || !granted()) return;
        stopScan();
        Intent service = new Intent(this, BleReceiverService.class).setAction(BleReceiverService.CONNECT).putExtra("address",address);
        try { startForegroundService(service); }
        catch (RuntimeException e) { Toast.makeText(this,Language.tr("无法启动蓝牙接收服务"),Toast.LENGTH_LONG).show(); }
    }
    private void sortRows() {
        if (list == null) return;
        ArrayList<DeviceRow> ordered = new ArrayList<>(devices.values());
        ordered.sort(Comparator.comparingInt((DeviceRow row) -> row.rssi).reversed());
        for (int i = 0; i < ordered.size(); i++) {
            Button button = ordered.get(i).button;
            if (list.getChildAt(i) != button) { list.removeView(button); list.addView(button, i); }
        }
    }
    private void stopScan() {
        generation++;
        if (scanner != null && callback != null) try { scanner.stopScan(callback); } catch (SecurityException ignored) { }
        scanner = null; callback = null;
        sortRows();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if (requestCode == 42 && scanAfterPermission) { scanAfterPermission = false; if (granted()) beginScan(); else Toast.makeText(this,Language.tr("需要蓝牙和精确位置权限"),Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onResume() { super.onResume(); main.post(update); }
    @Override protected void onPause() { stopScan(); main.removeCallbacks(update); super.onPause(); }
    @Override protected void onDestroy() { stopScan(); main.removeCallbacks(update); super.onDestroy(); }
}
