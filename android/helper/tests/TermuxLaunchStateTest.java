package org.locationbridge.source;
public final class TermuxLaunchStateTest {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] args){
        check(TermuxCommand.USB.arguments()[2].equals("fusion"),"fixed mode");
        check(TermuxCommand.WIFI.arguments()[4].equals("wifi"),"fixed transport");
        check(TermuxCommand.STOP.arguments().length==1,"stop fixed");
        TermuxLaunchState state = new TermuxLaunchState("pending",100,"usb","abc",null);
        check(state.view(12100).equals("pending"),"pending deadline inclusive");
        check(state.view(12101).equals("unconfirmed"),"timeout not success");
        check(state.view(99).equals("unconfirmed"),"clock reset");
        check(state.busy(TermuxCommand.WIFI,101),"repeated start blocked");
        check(!state.busy(TermuxCommand.STOP,101),"stop always allowed");
        check(!state.busy(TermuxCommand.STATUS,101),"status allowed");
        check(!state.accepts("other",101),"wrong nonce denied");
        check(state.accepts("abc",101),"same nonce accepted");
        check(!state.accepts("abc",99),"reboot result denied");
        check(TermuxLaunchState.result(null,null).equals("failed"),"missing result failed");
        check(TermuxLaunchState.result(-1,0).equals("completed"),"success recognized");
        check(TermuxLaunchState.result(-1,2).equals("failed"),"nonzero failed");
        check(TermuxLaunchState.result(1,null).equals("failed"),"internal error failed");
        check(!TermuxLaunchState.showCommand(1000,1500,10500),"expired report still supersedes earlier command");
        check(!TermuxLaunchState.showCommand(1500,1500,1600),"report wins a timestamp tie");
        check(TermuxLaunchState.showCommand(2000,1500,2100),"newer command visible while waiting");
        check(TermuxLaunchState.showCommand(2000,0,16000),"show unconfirmed command briefly without a report");
        check(!TermuxLaunchState.showCommand(2000,0,62001),"unanswered command eventually yields to link state");
        check(!TermuxLaunchState.showCommand(2000,0,1500),"future command never wins");
        TermuxLaunchSlots slots = new TermuxLaunchSlots();
        slots.issue(TermuxCommand.USB,100,"start-token");
        slots.issue(TermuxCommand.STATUS,101,"status-token");
        check(slots.busy(TermuxCommand.USB,102),"status must not clear start");
        slots.issue(TermuxCommand.STOP,103,"stop-token");
        check(slots.receive("start-token",104,-1,0),"old start accepted in its slot");
        check(slots.display().operation.equals("stop") && slots.display().state.equals("pending"),"old start cannot override stop display");
        check(!slots.receive("status-token",104,-1,0),"replaced auxiliary callback ignored");
        System.out.println("Termux launch state tests passed");
    }
}
