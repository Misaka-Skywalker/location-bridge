package org.locationbridge.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict parser for the text dump of the real USB port manager. */
final class UsbPortState {
    final String result, id, power, data;
    final boolean sourceHost, sinkHost;
    private UsbPortState(String result, String id, String power, String data, boolean sourceHost, boolean sinkHost) {
        this.result=result; this.id=id; this.power=power; this.data=data; this.sourceHost=sourceHost; this.sinkHost=sinkHost;
    }
    static UsbPortState failure(String result) { return new UsbPortState(result,null,"unknown","unknown",false,false); }
    static UsbPortState enumeratedHost() { return new UsbPortState("host",null,"unknown","host",false,false); }
    static boolean ipadCandidate(int vendor, int product, String productName) {
        if (vendor != 0x05ac || product < 0x1290 || product > 0x12af) return false;
        String name = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        return !name.startsWith("iphone") && !name.startsWith("ipod") && !name.startsWith("apple tv");
    }
    static String field(String text, String name) {
        Matcher m=Pattern.compile("(?<![A-Za-z0-9_])"+Pattern.quote(name)+"=([^\\s,}\\]]+)").matcher(text);
        if (!m.find()) return null;
        String value=m.group(1); return m.find()?null:value;
    }
    static String block(String text, String name) {
        Matcher m=Pattern.compile("(?<![A-Za-z0-9_])"+Pattern.quote(name)+"=\\{").matcher(text);
        if (!m.find()) return null;
        int start=m.end(), depth=1, end=start;
        for (;end<text.length() && depth>0;end++) { char c=text.charAt(end); if(c=='{') depth++; else if(c=='}') depth--; }
        if(depth!=0 || m.find(end)) return null;
        return text.substring(start,end-1);
    }
    static List<String> blocks(String text, String name) {
        List<String> out=new ArrayList<>();
        Matcher m=Pattern.compile("(?<![A-Za-z0-9_])"+Pattern.quote(name)+"=\\{").matcher(text);
        int from=0;
        while(m.find(from)) {
            int start=m.end(), depth=1, end=start;
            for(;end<text.length()&&depth>0;end++){char c=text.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}
            if(depth!=0) return null;
            out.add(text.substring(start,end-1)); from=end;
        }
        return out;
    }
    static UsbPortState parse(String text) {
        String manager=block(text,"port_manager"); if(manager==null) return failure("unavailable");
        if(!"false".equals(field(manager,"is_simulation_active"))) return failure("unavailable");
        List<String> list=blocks(manager,"usb_ports"); if(list==null) return failure("unavailable");
        List<UsbPortState> connected=new ArrayList<>();
        String disconnectedId=null;
        for(String entry:list) {
            String port=block(entry,"port"), status=block(entry,"status");
            if(port==null||status==null) return failure("unavailable");
            String connectedValue=field(status,"connected");
            if(!"true".equals(connectedValue)&&!"false".equals(connectedValue)) return failure("unavailable");
            if(!"true".equals(connectedValue)) {
                String id=field(port,"id");
                if(id==null||!id.matches("[A-Za-z0-9_.-]{1,64}")) return failure("unavailable");
                disconnectedId=id;
                continue;
            }
            int roleIndex=status.indexOf("role_combinations=[");
            if(roleIndex<0) return failure("unavailable");
            String statusHeader=status.substring(0,roleIndex);
            String id=field(port,"id"), power=field(statusHeader,"power_role"), data=field(statusHeader,"data_role");
            if(id==null||!id.matches("[A-Za-z0-9_.-]{1,64}") || !("sink".equals(power)||"source".equals(power)) || !("host".equals(data)||"device".equals(data))) return failure("unavailable");
            Matcher combinations=Pattern.compile("role_combinations=\\[([^\\]]*)\\]").matcher(status);
            if(!combinations.find()) return failure("unavailable");
            String choices=combinations.group(1);
            boolean source=Pattern.compile("\\{\\s*power_role=source\\s+data_role=host\\s*\\}").matcher(choices).find();
            boolean sink=Pattern.compile("\\{\\s*power_role=sink\\s+data_role=host\\s*\\}").matcher(choices).find();
            if(!"true".equals(field(entry,"can_change_data_role"))) {source=false;sink=false;}
            connected.add(new UsbPortState("host".equals(data)?"host":"device",id,power,data,source,sink));
        }
        if(list.size()>1) return failure("ambiguous");
        if(connected.isEmpty()) {
            if(list.size()==1 && disconnectedId!=null) return new UsbPortState("disconnected",disconnectedId,"unknown","unknown",false,false);
            return list.isEmpty()?failure("disconnected"):failure("ambiguous");
        }
        return connected.get(0);
    }
    boolean canContinueNegotiation(String originalId) {
        return originalId != null && originalId.equals(id) && ("device".equals(result) || "disconnected".equals(result));
    }
    boolean supportsHost(String powerRole) { return "source".equals(powerRole)?sourceHost:"sink".equals(powerRole)&&sinkHost; }
}
