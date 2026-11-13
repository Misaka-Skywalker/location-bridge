package org.locationbridge.source;

public final class UsbPortStateTest {
    static final String PORT="usb_ports={port={id=port0 supported_modes=dual,audio_acc} status={connected=true current_mode=ufp power_role=sink data_role=device role_combinations=[{power_role=source data_role=host}{power_role=source data_role=device}{power_role=sink data_role=host}{power_role=sink data_role=device}]} can_change_data_role=true}";
    static String wrap(String ports) { return "USB MANAGER STATE port_manager={ is_simulation_active=false "+ports+" usb_hal_version=20 }"; }
    static void check(boolean b) { if(!b)throw new AssertionError(); }
    public static void main(String[] args) {
        UsbPortState state=UsbPortState.parse(wrap(PORT));
        check(state.result.equals("device")&&state.id.equals("port0")&&state.power.equals("sink")&&state.supportsHost("sink")&&state.supportsHost("source"));
        check(UsbPortState.parse(wrap(PORT.replace("data_role=device role_combinations", "data_role=host role_combinations"))).result.equals("host"));
        check(UsbPortState.parse(wrap(PORT+PORT.replace("port0","port1"))).result.equals("ambiguous"));
        UsbPortState disconnected=UsbPortState.parse(wrap(PORT.replace("connected=true","connected=false")));
        check(disconnected.result.equals("disconnected") && disconnected.canContinueNegotiation("port0"));
        check(!disconnected.canContinueNegotiation("port1"));
        check(state.canContinueNegotiation("port0"));
        check(!UsbPortState.parse(wrap(PORT+PORT.replace("port0","port1"))).canContinueNegotiation("port0"));
        check(!UsbPortState.parse(wrap(PORT).replace("is_simulation_active=false","is_simulation_active=true")).canContinueNegotiation("port0"));
        check(UsbPortState.parse(wrap(PORT).replace("is_simulation_active=false","is_simulation_active=true")).result.equals("unavailable"));
        check(UsbPortState.parse(wrap(PORT).replace("data_role=device role_combinations","data_role=unknown role_combinations")).result.equals("unavailable"));
        check(!UsbPortState.parse(wrap(PORT.replace("can_change_data_role=true","can_change_data_role=false"))).supportsHost("sink"));
        check(UsbPortState.parse(wrap(PORT).replace("usb_ports={","usb_ports={port={")).result.equals("unavailable"));
        check(UsbPortState.parse("Permission Denial").result.equals("unavailable"));
        System.out.println("UsbPortStateTest passed");
    }
}
