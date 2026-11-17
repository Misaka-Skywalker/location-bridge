package org.locationbridge.source;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONObject;
import java.util.Locale;

/** MCDU-inspired native display. Every field comes from a snapshot or an explicit empty state. */
final class McduPanel extends LinearLayout {
    interface Actions { void run(String name); }
    static final int BODY=0xff171c21, SCREEN=0xff080e12, WHITE=0xffe4ecee;
    static final int CYAN=0xff68dbe4, GREEN=0xff8ce3a8, AMBER=0xffffc772, MUTED=0xffa9b7c2;
    private final TextView title, annunciator, scratch, pairing, bridgeHeading, bridgeDetails;
    private final TextView[][] labels=new TextView[6][2], values=new TextView[6][2];
    private String page="POS";
    private JSONObject snapshot;
    private JSONObject commandStatus;
    private boolean permission, enabled, running;
    private final TextView runLabel, inertialLabel;
    private final Button runKey, inertialKey;
    private final Button[] navigation=new Button[5];
    private static final String[] PAGES={"POS","GNSS","INS","SYS","RX"};
    private final String[] PAGE_NAMES={Language.tr("位置"),Language.tr("高度速度"),Language.tr("惯性"),Language.tr("系统"),Language.tr("接收机")};
    final LinearLayout pageBar;
    McduPanel(Context context,Actions actions, Runnable pageChanged) {
        super(context);
        setOrientation(VERTICAL);setPadding(dp(12),dp(12),dp(12),dp(12));setBackgroundColor(BODY);
        TextView brand=text(Language.tr("定位桥  /  LOCATION BRIDGE"),12,MUTED);
        brand.setPadding(dp(8),0,0,dp(8));addView(brand);
        LinearLayout display=new LinearLayout(context);display.setOrientation(VERTICAL);
        display.setPadding(dp(12),dp(12),dp(12),dp(8));display.setBackground(shape(SCREEN,0xff38444d,4));addView(display);
        title=text(Language.tr("位置总览  1/5"),18,WHITE);display.addView(title);
        bridgeHeading=text(Language.tr("iPad：尚未收到状态"),14,AMBER);bridgeHeading.setPadding(0,dp(8),0,0);display.addView(bridgeHeading);
        bridgeDetails=text(Language.tr("点击“连接 iPad”启动 USB 连接"),11,MUTED);bridgeDetails.setMinHeight(dp(28));display.addView(bridgeDetails);
        annunciator=text(Language.tr("● 手机定位已停止"),12,AMBER);annunciator.setPadding(0,dp(4),0,dp(4));display.addView(annunciator);
        for(int row=0;row<6;row++) {
            LinearLayout line=new LinearLayout(context);line.setOrientation(HORIZONTAL);line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight(dp(44));display.addView(line,new LayoutParams(-1,LayoutParams.WRAP_CONTENT));
            for(int col=0;col<2;col++) {
                LinearLayout cell=new LinearLayout(context);cell.setOrientation(VERTICAL);
                if(col==1) cell.setGravity(Gravity.RIGHT);
                labels[row][col]=text("",11,MUTED);values[row][col]=text("---",16,CYAN);
                if(col==1){labels[row][col].setGravity(Gravity.RIGHT);values[row][col].setGravity(Gravity.RIGHT);}
                cell.addView(labels[row][col]);cell.addView(values[row][col]);line.addView(cell,new LayoutParams(0,-2,1));
            }
        }
        scratch=text(Language.tr("点击“启动定位”开始"),12,AMBER);scratch.setMinHeight(dp(40));scratch.setPadding(0,dp(8),0,dp(8));display.addView(scratch);
        LinearLayout operations=new LinearLayout(context);operations.setOrientation(VERTICAL);
        operations.setPadding(0,dp(8),0,0);addView(operations);
        ActionRow connection=new ActionRow(()->actions.run("CONNECT"),()->actions.run("DISCONNECT"));
        connection.bind(Language.tr("连接 iPad"),Language.tr("断开连接"),GREEN,AMBER);operations.addView(connection);
        ActionRow primary=new ActionRow(()->actions.run(running?"STOP":"RUN"),()->actions.run("DR"));
        operations.addView(primary);runLabel=primary.left;inertialLabel=primary.right;runKey=primary.leftKey;inertialKey=primary.rightKey;
        ActionRow secondary=new ActionRow(()->actions.run("BLE"),()->actions.run("SETTINGS"));
        secondary.bind(Language.tr("蓝牙设备"),Language.tr("设置"),CYAN,CYAN);operations.addView(secondary);
        pageBar=new LinearLayout(context);
        pageBar.setOrientation(HORIZONTAL);
        pageBar.setPadding(dp(6),dp(2),dp(6),dp(4));
        for(int index=0;index<PAGES.length;index++) {
            final int selected=index;
            Button button=key(PAGE_NAMES[index],PAGE_NAMES[index]+Language.tr("页面"),()->{page=PAGES[selected];render();pageChanged.run();});
            navigation[index]=button;
            LayoutParams params=new LayoutParams(0,LayoutParams.WRAP_CONTENT,1);button.setMinHeight(dp(48));
            params.setMargins(dp(2),0,dp(2),0);pageBar.addView(button,params);
        }
        pairing=text(Language.tr("尚未配对"),11,MUTED);pairing.setPadding(dp(4),dp(16),dp(4),dp(8));addView(pairing);
        TextView legend=text(Language.tr("连接 iPad 默认使用 USB；Wi-Fi 连接请前往设置"),11,MUTED);
        legend.setPadding(dp(4),0,dp(4),0);addView(legend);
    }
    private final class ActionRow extends LinearLayout {
        final Button leftKey,rightKey;
        final TextView left,right;
        ActionRow(Runnable leftAction,Runnable rightAction) {
            super(McduPanel.this.getContext());setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0,dp(4),0,dp(4));
            leftKey=key("—","",leftAction);rightKey=key("—","",rightAction);
            leftKey.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            rightKey.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(leftKey,new LayoutParams(dp(48),dp(48)));
            LinearLayout line=new LinearLayout(getContext());line.setOrientation(HORIZONTAL);line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(8),0,dp(8),0);line.setBackground(shape(SCREEN,0xff38444d,2));
            LayoutParams screen=new LayoutParams(0,LayoutParams.WRAP_CONTENT,1);screen.setMargins(dp(4),0,dp(4),0);addView(line,screen);
            left=key("","",leftAction);right=key("","",rightAction);
            for(TextView label:new TextView[]{left,right}) {
                label.setTextColor(CYAN);label.setGravity((label==right?Gravity.RIGHT:Gravity.LEFT)|Gravity.CENTER_VERTICAL);
                label.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0xff52636e),null,null));
            }
            left.setMinHeight(dp(48));right.setMinHeight(dp(48));
            line.addView(left,new LayoutParams(0,-2,1));line.addView(right,new LayoutParams(0,-2,1));
            addView(rightKey,new LayoutParams(dp(48),dp(48)));
        }
        void bind(String l,String r,int lc,int rc){
            left.setText("‹ "+l);right.setText(r+" ›");left.setTextColor(lc);right.setTextColor(rc);
            left.setContentDescription(l);right.setContentDescription(r);leftKey.setContentDescription(l);rightKey.setContentDescription(r);
        }
    }
    private Button key(String label,String description,Runnable action){
        Button b=new Button(getContext());b.setText(label);b.setTextSize(13);b.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        b.setAllCaps(false);b.setTextColor(WHITE);b.setContentDescription(description);b.setPadding(0,0,0,0);b.setMinWidth(0);b.setMinimumWidth(0);
        android.content.res.ColorStateList ripple=android.content.res.ColorStateList.valueOf(0xff52636e);
        b.setBackground(new android.graphics.drawable.RippleDrawable(ripple,shape(0xff29333b,0xff4c5b66,4),null));
        b.setOnClickListener(v->action.run());return b;
    }
    private GradientDrawable shape(int color,int border,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));d.setStroke(dp(1),border);return d;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.MONOSPACE);t.setIncludeFontPadding(false);return t;}
    String currentPage(){ return page; }
    void selectPage(String value){ for(String candidate:PAGES) if(candidate.equals(value)) { page=value; break; } }
    void pairing(String text){pairing.setText(text);}
    void commandStatus(JSONObject value){commandStatus=value;}
    void fault(String text){annunciator.setText(Language.tr("● 状态读取失败"));annunciator.setTextColor(AMBER);scratch.setText(text);}
    void update(JSONObject data,boolean permission,boolean enabled,JSONObject bridge){this.snapshot=data;this.permission=permission;this.enabled=enabled;render();renderBridge(bridge);}
    private void renderBridge(JSONObject report){
        String phase=str(report,"phase","unknown"),transport=str(report,"transport","none");
        String via=transport.equals("usb")?" · USB":transport.equals("wifi")?" · Wi-Fi":"";
        boolean fresh=report!=null&&report.optBoolean("fresh");
        bridgeHeading.setTextColor(AMBER);
        String commandState=str(commandStatus,"state","none");
        long issued=commandStatus==null?0:commandStatus.optLong("issued_elapsed_ms");
        boolean history = report != null && !report.isNull("report_age_ms");
        long reportSent = history ? report.optLong("sent_elapsed_ms") : 0;
        boolean commandNewer = TermuxLaunchState.showCommand(issued, reportSent, android.os.SystemClock.elapsedRealtime());
        if(commandNewer && !commandState.equals("none")) {
            boolean stopping="stop".equals(str(commandStatus,"operation","")) && commandState.equals("pending");
            bridgeHeading.setText(Language.tr(commandState.equals("failed")?"iPad：命令执行失败":stopping?"iPad：已发送断开请求":"iPad：等待连接报告"));
            bridgeDetails.setText(Language.tr("terminal_open_failed".equals(str(commandStatus,"error",""))?"请点击 Termux 通知打开终端，让命令继续执行":commandState.equals("failed")?"请检查 Termux 应用调用设置和桥接脚本":commandState.equals("unconfirmed")?"尚未收到状态，请在 Termux 中检查启动结果":commandState.equals("completed")?"命令已结束，连接状态尚未确认":"正在调用 Termux，请处理可能出现的授权提示"));
            return;
        }
        if(!fresh){

            bridgeHeading.setText(history?(phase.equals("stopped")?Language.tr("iPad：上次连接已结束"):Language.tr("iPad：连接状态未知"))+via:Language.tr("iPad：尚未收到状态"));
            if (history && report.optBoolean("clear_pending"))
                bridgeDetails.setText(Language.tr("尚未确认模拟位置已清除，请在 Termux 中查看"));
            else if (history && !phase.equals("stopped"))
                bridgeDetails.setText(Language.tr("连接状态未更新，请在 Termux 中查看"));
            else bridgeDetails.setText(Language.tr("点击“连接 iPad”启动 USB 连接"));
            return;
        }
        String state;
        switch(phase){
            case "not_started":state=Language.tr("尚未启动连接");break;
            case "connecting":state=Language.tr("连接中");break;
            case "connected":state=Language.tr("已连接");break;
            case "reconnecting":state=Language.tr("正在重连");break;
            case "stopping":state=Language.tr("正在停止");break;
            case "stopped":state=Language.tr("连接已结束");break;
            case "error":state=Language.tr("连接异常");break;
            default:state=Language.tr("连接状态未知");
        }
        bridgeHeading.setText((Language.isEnglish()?"iPad LINK: ":"iPad：")+state+via);
        if(phase.equals("connected")) bridgeHeading.setTextColor(GREEN);
        String issue=str(report,"issue","none"),worker=str(report,"worker_state","unknown");
        String detail;
        if(issue.equals("clear_unconfirmed")) detail=Language.tr("尚未确认模拟位置已清除，请在 Termux 中查看");
        else if(issue.equals("waiting_usb")) detail=Language.tr("请在手机弹窗中允许访问 USB 设备");
        else if(issue.equals("usb_missing")) detail=Language.tr("请检查数据线，并在手机上启用 OTG");
        else if(issue.equals("usb_role")) detail=Language.tr("正在检查并纠正 USB 主机角色");
        else if(issue.equals("usb_permission")) detail=Language.tr("USB 自动纠正需要一次性电脑授权");
        else if(issue.equals("usb_role_failed")) detail=Language.tr("请在 USB 设置中将控制权切换到本机");
        else if(issue.equals("usb_busy")) detail=Language.tr("请关闭 USB 文件或照片传输后重试");
        else if(issue.equals("waiting_helper")) detail=Language.tr("正在启动定位助手");
        else if(issue.equals("source_unavailable")) detail=Language.tr("暂无可用位置，请检查手机定位");
        else if(issue.equals("worker_error")) detail=Language.tr("位置发送失败，请在 Termux 中查看详情");
        else if(issue.equals("bridge_error")) detail=Language.tr("连接失败，请在 Termux 中查看详情");
        else if(issue.equals("worker_stale")) detail=Language.tr("Termux 暂无响应，连接状态待确认");
        else if(phase.equals("connected")){
            if(!worker.equals("running")) detail=Language.tr("已连接，等待定位数据");
            else {
                String src=str(report,"source","unknown");
                String label=src.equals("gps")?"GNSS":src.equals("fused")?Language.tr("系统融合"):src.equals("network")?Language.tr("网络定位"):src.equals("inertial")?Language.tr("惯性推算"):src.equals("test")?Language.tr("测试轨迹"):Language.tr("未知来源");
                detail=Language.tr("最近发送 · ")+label;
                if(!report.isNull("accuracy_m")) detail+=" · "+(src.equals("inertial")?Language.tr("模型误差 "):Language.tr("精度 "))+distance(report,"accuracy_m",false);
            }
        }else if(phase.equals("stopped")||phase.equals("error"))
            detail=report.optBoolean("clear_pending")?Language.tr("尚未确认模拟位置已清除，请在 Termux 中查看"):Language.tr("连接已结束；可点击连接按钮重新启动");
        else if(phase.equals("stopping")) detail=Language.tr("正在断开连接并清除模拟位置");
        else detail=Language.tr("正在等待 Termux 更新连接状态");
        bridgeDetails.setText(detail);
    }
    private static JSONObject obj(JSONObject o,String key){return o==null?null:o.optJSONObject(key);}
    private static String str(JSONObject o,String key,String fallback){return o==null?fallback:o.optString(key,fallback);}
    private static String number(JSONObject o,String key,String format){return o==null||o.isNull(key)?"---":String.format(Locale.ROOT,format,o.optDouble(key));}
    private static String declination(JSONObject motion){
        double value=motion==null?Double.NaN:motion.optDouble("magnetic_declination_deg");
        if(!Double.isFinite(value)) return "---";
        if(Math.abs(value)<0.05) return "0.0°";
        return String.format(Locale.ROOT,"%s %.1f°",value>0?Language.tr("东偏"):Language.tr("西偏"),Math.abs(value));
    }
    private static String source(JSONObject sample){String s=str(sample,"source","");return s.equals("gps")?"GNSS":s.equals("fused")?Language.tr("系统融合"):s.equals("network")?Language.tr("网络定位"):s.equals("inertial")?Language.tr("惯性推算"):"---";}
    private static String capability(JSONObject value,String key){
        if(value==null || value.isNull(key) || !value.has(key)) return Language.tr("未知");
        return value.optBoolean(key)?Language.tr("是"):Language.tr("否");
    }
    private static String countedKeys(JSONObject value){
        if(value==null) return "---";
        java.util.TreeSet<String> keys=new java.util.TreeSet<>();
        java.util.Iterator<String> it=value.keys();
        while(it.hasNext()){String key=it.next();if(value.optInt(key)>0 && !key.equals("unknown") && !key.equals("other")) keys.add(key);}
        return String.valueOf(keys.size());
    }
    private String age(JSONObject o){return o==null?"---":String.format(Locale.ROOT,"%.1f s",Math.max(0,(snapshot.optLong("elapsed_realtime_nanos")-o.optLong("elapsed_realtime_nanos"))/1e9));}
    private void row(int r,String l,String lv,String right,String rv){labels[r][0].setText(l);values[r][0].setText(lv);labels[r][1].setText(right);values[r][1].setText(rv);}
    private static String provider(JSONObject providers, String key) {
        JSONObject p = obj(providers, key);
        if (p == null) return "---";
        if (!p.optBoolean("available")) return Language.tr("不可用");
        if (!p.optBoolean("enabled")) return Language.tr("已关闭");
        return p.optBoolean("registered") ? Language.tr("已订阅定位") : Language.tr("尚未启动");
    }
    private static String speed(JSONObject o,String key){return DisplayUnits.speed(o==null?Double.NaN:o.optDouble(key));}
    private static String distance(JSONObject o,String key,boolean signed){return DisplayUnits.distance(o==null?Double.NaN:o.optDouble(key),signed);}
    private String elapsed(JSONObject o,String key){
        return o==null || !o.has(key)?"---":DisplayUnits.duration((snapshot.optLong("elapsed_realtime_nanos")-o.optLong(key))/1e9);
    }
    private void render(){
        JSONObject s=obj(snapshot,"sample"),h=obj(snapshot,"altitude"),g=obj(snapshot,"gnss_motion"),i=obj(snapshot,"inertial");
        JSONObject sensors=obj(i,"sensors"),selection=obj(snapshot,"selection"),providers=obj(snapshot,"providers");
        JSONObject ins=obj(i,"position"),correction=obj(i,"last_correction");
        running=snapshot!=null&&snapshot.optBoolean("running");
        boolean predicted="inertial".equals(str(s,"source",""));
        boolean active=i!=null&&i.optBoolean("active");
        String operation=running?Language.tr("停止定位"):Language.tr("启动定位");
        runLabel.setText("‹ "+operation);runLabel.setContentDescription(operation);runKey.setContentDescription(operation);
        runLabel.setTextColor(running?AMBER:GREEN);
        String inertialOperation=enabled?Language.tr("停用推算"):Language.tr("启用推算");
        inertialLabel.setText(inertialOperation+" ›");inertialLabel.setContentDescription(inertialOperation);inertialKey.setContentDescription(inertialOperation);
        for(int index=0;index<PAGES.length;index++) {
            boolean selected=PAGES[index].equals(page);
            navigation[index].setSelected(selected);navigation[index].setText(PAGE_NAMES[index]+(selected?" ·":""));
            navigation[index].setTextColor(selected?GREEN:WHITE);
            navigation[index].setContentDescription(PAGE_NAMES[index]+(selected?Language.tr("，当前页面"):Language.tr("页面")));
        }
        annunciator.setText(predicted?Language.tr("● 惯性推算中"):s!=null?Language.tr("● 手机定位正常"):running?Language.tr("● 等待手机定位"):Language.tr("● 手机定位已停止"));
        annunciator.setTextColor(s!=null&&!predicted?GREEN:AMBER);
        if(page.equals("POS")){
            title.setText(Language.tr("位置总览  1/5"));
            row(0,Language.tr("定位来源"),source(s),Language.tr("距上次更新"),age(s));
            row(1,Language.tr("纬度"),number(s,"latitude","%+.5f"),Language.tr("经度"),number(s,"longitude","%+.5f"));
            row(2,Language.tr("高度"),number(h,"altitude_m","%.1f m"),Language.tr("高度来源"),source(h));
            row(3,predicted?Language.tr("惯导位置不确定度"):Language.tr("水平精度"),distance(s,"accuracy_m",false),Language.tr("垂直精度"),number(h,"vertical_accuracy_m","%.1f m"));
            row(4,Language.tr("真航迹角"),number(g,"bearing_deg","%.1f°"),Language.tr("磁航迹角"),number(g,"magnetic_bearing_deg","%.1f°"));
            row(5,Language.tr(predicted?"惯导地速":"GNSS 地速"),speed(predicted?s:g,"speed_m_s"),Language.tr("惯性推算"),predicted?DisplayUnits.duration(s.optDouble("prediction_age_ms")/1000):enabled?Language.tr("已启用"):Language.tr("已停用"));
        }else if(page.equals("GNSS")){
            title.setText(Language.tr("高度与速度  2/5"));
            row(0,Language.tr("高度（米）"),number(h,"altitude_m","%.1f m"),Language.tr("高度（英尺）"),h==null?"---":String.format(Locale.ROOT,"%.0f ft",h.optDouble("altitude_m")*3.280839895));
            row(1,Language.tr("高度来源"),source(h),Language.tr("高度基准"),h==null?"---":Language.tr("WGS84 椭球"));
            row(2,Language.tr("垂直精度"),number(h,"vertical_accuracy_m","%.1f m"),Language.tr("距上次更新"),age(h));
            row(3,Language.tr(predicted?"惯导地速":"GNSS 地速"),speed(predicted?s:g,"speed_m_s"),Language.tr("地速精度"),predicted?"---":speed(g,"speed_accuracy_m_s"));
            row(4,Language.tr("真航迹角"),number(g,"bearing_deg","%.1f°"),Language.tr("磁航迹角"),number(g,"magnetic_bearing_deg","%.1f°"));
            row(5,Language.tr("磁差（估算）"),declination(g),Language.tr("真航迹角精度"),number(g,"bearing_accuracy_deg","%.1f°"));
        }else if(page.equals("INS")){
            title.setText(Language.tr("惯性推算  3/5"));
            String insState;
            if (!running) insState = "未运行";
            else if (!enabled) insState = "已停用";
            else if (active) insState = "接替中";
            else insState = ins == null ? "等待校准" : "已校准";
            row(0,Language.tr("推算状态"),Language.tr(insState),Language.tr("距上次 GNSS 校准"),elapsed(ins,"anchor_elapsed_realtime_nanos"));
            row(1,Language.tr("惯导纬度"),number(ins,"latitude","%+.5f"),Language.tr("惯导经度"),number(ins,"longitude","%+.5f"));
            row(2,Language.tr("惯导位置不确定度"),distance(ins,"accuracy_m",false),Language.tr("惯导地速"),speed(ins,"speed_m_s"));
            row(3,Language.tr("上次校准位置差"),distance(correction,"distance_m",false),Language.tr("距上次校正"),elapsed(correction,"elapsed_realtime_nanos"));
            row(4,Language.tr("东西差（+东）"),distance(correction,"east_m",true),Language.tr("南北差（+北）"),distance(correction,"north_m",true));
            String motionStatus = "---";
            if (running && enabled && sensors != null) {
                boolean registered = sensors.optBoolean("rotation_registered") && sensors.optBoolean("acceleration_registered");
                motionStatus = Language.tr(registered ? "已启用" : "不可用");
            }
            row(5,Language.tr("运动传感器"),motionStatus,Language.tr("推算时限"),Language.tr("不限"));
        }else if(page.equals("SYS")){
            title.setText(Language.tr("系统状态  4/5"));
            row(0,Language.tr("手机定位"),running?Language.tr("运行中"):Language.tr("已停止"),Language.tr("位置状态"),s!=null?Language.tr("有效"):Language.tr("等待定位"));
            row(1,"GPS",provider(providers,"gps"),Language.tr("系统融合"),provider(providers,"fused"));
            row(2,Language.tr("网络定位"),provider(providers,"network"),Language.tr("位置权限"),permission?Language.tr("精确位置"):Language.tr("需要授权"));
            row(3,Language.tr("丢弃定位次数"),selection==null?"---":String.valueOf(selection.optLong("rejected_count")),Language.tr("切换来源次数"),selection==null?"---":String.valueOf(selection.optLong("switch_count")));
            row(4,Language.tr("惯性推算"),enabled?Language.tr("已启用"):Language.tr("已停用"),Language.tr("工作模式"),Language.tr("手机定位"));
            row(5,Language.tr("定位请求间隔"),number(obj(snapshot,"adaptive"),"requested_interval_ms","%.0f ms"),Language.tr("发送至 iPad"),Language.tr("仅经纬度"));
        }else{
            JSONObject rx=obj(snapshot,"gnss_diagnostics"),status=obj(rx,"status"),raw=obj(rx,"measurements");
            title.setText(Language.tr("接收机能力  5/5"));
            row(0,Language.tr("参与解算卫星"),number(status,"satellites_used","%.0f"),Language.tr("可见卫星"),number(status,"satellites_visible","%.0f"));
            row(1,Language.tr("已观测星座"),countedKeys(obj(status,"constellations")),Language.tr("已观测信号类型"),countedKeys(obj(status,"bands")));
            row(2,Language.tr("平均载噪比"),number(status,"cn0_mean_dbhz","%.1f dB-Hz"),Language.tr("原始观测条数"),number(raw,"count","%.0f"));
            row(3,Language.tr("连续跟踪请求"),capability(rx,"full_tracking_request_accepted"),Language.tr("硬件确认"),capability(raw,"full_tracking_reported"));
            row(4,Language.tr("姿态路径"),sensors==null?"---":!sensors.optBoolean("rotation_registered")?Language.tr("不可用"):str(sensors,"selected_backend","").startsWith("geomagnetic")?Language.tr("地磁姿态"):Language.tr("系统姿态"),Language.tr("加速度路径"),sensors==null?"---":!sensors.optBoolean("acceleration_registered")?Language.tr("不可用"):str(sensors,"selected_backend","").endsWith("gravity_subtraction")?Language.tr("加速度减重力"):Language.tr("系统线加速度"));
            row(5,Language.tr("环境气压"),number(sensors,"pressure_hpa","%.1f hPa"),Language.tr("定位请求间隔"),number(obj(snapshot,"adaptive"),"requested_interval_ms","%.0f ms"));
        }
        String reason=str(i,"reason","waiting_anchor");
        scratch.setText(!running?Language.tr("点击“启动定位”获取手机位置"):page.equals("INS")?inertialReason(reason)+"\n"+Language.tr("位置差按同一时刻的惯导位置减去 GNSS 位置计算"):predicted?Language.tr("正在推算位置，误差为模型估计值\n收到新定位后自动恢复实测位置"):s==null?Language.tr("正在等待定位\n")+inertialReason(reason):Language.tr("定位正常 · ")+(h==null?Language.tr("暂无高度数据"):Language.tr("高度采用 WGS84 椭球高")));
        if(page.equals("GNSS") && running && g!=null)
            scratch.setText(Language.tr("航迹角表示移动方向，磁航迹角由磁差换算\n")+(h==null?Language.tr("暂无高度数据"):Language.tr("高度采用 WGS84 椭球高")));
        if(page.equals("RX")) scratch.setText(Language.tr("能力按实际数据识别，未知不代表不支持\n气压不参与高度解算"));
    }
    private static String inertialReason(String reason){
        switch(reason){
            case "eligible":return Language.tr("持续推算中，实测位置可用时优先采用");
            case "gnss_fresh":return Language.tr("GNSS 定位正常，推算速度已校准");
            case "disabled":return Language.tr("惯性推算已停用");
            case "gnss_velocity_unavailable":return Language.tr("等待可靠的 GNSS 地速和航迹角");
            case "waiting_anchor":case "waiting_gnss":return Language.tr("等待 GNSS 数据完成初始校准");
            case "reference_expired":return Language.tr("需要新的 GNSS 位置更新磁差");
            case "sensor_stale":case "sensor_timing":return Language.tr("运动数据中断，等待重新校准");
            case "sensor_accuracy_low":case "invalid_sensor_sample":case "heading_accuracy_low":return Language.tr("传感器质量不足，暂停推算");
            case "speed_limit":return Language.tr("推算速度超出范围，等待 GNSS 校准");
            case "latitude_limit":return Language.tr("超出惯导支持的纬度范围");
            case "magnetic_unavailable":return Language.tr("等待有效地磁数据");
            case "invalid_acceleration":return Language.tr("加速度数据异常，等待 GNSS 校准");
            case "direction_unavailable":return Language.tr("等待可靠姿态数据");
            case "sensor_unavailable":case "registration_failed":return Language.tr("运动传感器不可用");
            default:return Language.tr("推算已暂停，等待重新校准");
        }
    }
}
