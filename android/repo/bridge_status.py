"""Local, authenticated, coordinate-free bridge status messages."""
import base64
import hashlib
import hmac
import json
import math


def make_status(info, worker, fix, *, phase, issue, now, utc_ms):
    worker = worker if isinstance(worker,dict) and worker.get('run_id') == info.get('run_id') else {}
    stamp=worker.get('updated_monotonic')
    fresh=(type(stamp) in (int,float) and math.isfinite(stamp) and 0 <= now-stamp <= 5)
    if phase not in ('stopping','stopped','error','not_started'):
        if worker and not fresh: phase,issue='unknown','worker_stale'
        elif fresh:
            if worker.get('connected') is True: phase='connected'
            elif worker.get('state')=='reconnecting': phase='reconnecting'
            elif worker.get('state')=='error': phase,issue='error','worker_error'
    connected=True if phase=='connected' else False if phase in ('stopped','not_started') else None
    state=worker.get('state') if fresh else 'unknown'
    if state not in ('waiting','running','stale','reconnecting','stopped','error'): state='unknown'
    source=worker.get('last_source') if fresh else 'unknown'
    if source not in ('gps','network','fused','test','inertial'): source='unknown'
    accuracy=worker.get('last_accuracy_m') if fresh else None
    if type(accuracy) not in (float,int) or not math.isfinite(accuracy) or not 0<=accuracy<=1e9: accuracy=None
    kind='model_estimate' if source=='inertial' and worker.get('last_accuracy_kind')=='model_estimate' else 'reported' if source in ('gps','network','fused') and accuracy is not None else 'unknown'
    sequence=worker.get('last_sequence') if fresh else None
    if type(sequence) is not int or sequence<0: sequence=None
    pending=worker.get('clear_pending') if fresh else None
    if type(pending) is not bool: pending=None
    if pending and phase in ('stopping','stopped','error'): issue='clear_unconfirmed'
    age=None
    if isinstance(fix,dict) and fix.get('run_id') == info.get('run_id'):
        try:
            age=(now-fix['received_monotonic'])*1000+fix['age_at_receive_ms']
            if not math.isfinite(age) or not 0<=age<=1e12: age=None
        except (TypeError,KeyError): age=None
    return {'protocol':1,'run_id':info.get('run_id') or 'none','sent_elapsed_ms':int(now*1000), 'sent_utc_ms':utc_ms,
            'phase':phase,'transport':info.get('transport','none'),'mode':info.get('mode','none'),
            'connected':connected,'worker_state':state,'source':source,'accuracy_m':accuracy,
            'accuracy_kind':kind,'fix_age_ms':age,'last_sequence':sequence,'clear_pending':pending,'issue':issue}


def encode(status,key):
    data=json.dumps(status,ensure_ascii=True,separators=(',',':'),allow_nan=False).encode('ascii')
    payload=base64.b64encode(data).decode('ascii')
    if len(payload)>4096: raise ValueError('status too large')
    return payload,hmac.new(bytes.fromhex(key),('bridge_status\n'+payload).encode(),hashlib.sha256).hexdigest()


def brief(status):
    phases={'not_started':'未启动','connecting':'连接中','connected':'已连接','reconnecting':'正在重连',
            'stopping':'正在停止','stopped':'已停止','error':'连接异常','unknown':'状态未知'}
    sources={'gps':'GPS','network':'网络','fused':'系统融合','test':'测试轨迹','inertial':'惯性推算','unknown':'未知'}
    issues={'waiting_usb':'等待 USB 授权','usb_missing':'未找到 iPad USB 设备','waiting_helper':'等待定位助手',
            'usb_role':'正在检查 USB 主机角色','usb_permission':'USB 自动纠正尚未授权','usb_role_failed':'USB 主机角色未就绪','usb_busy':'USB 接口被占用',
            'source_unavailable':'位置不可用','worker_error':'工作进程异常','clear_unconfirmed':'清除状态未确认',
            'bridge_error':'桥接异常','worker_stale':'工作进程状态过期'}
    phase=phases.get(status['phase'],'状态未知')
    transport={'usb':'USB','wifi':'Wi-Fi'}.get(status['transport'])
    result=f"iPad {phase}" + (f'（{transport}）' if transport else '')
    source=sources.get(status['source'],'未知')
    if status['phase']=='connected' and status['worker_state']=='running' and status['fix_age_ms'] is not None and status['fix_age_ms'] <= (500 if status['source']=='inertial' else 10000):
        result+=f' · 正在发送{source}位置'
    elif status['worker_state']=='stale': result+=' · 位置已过期'
    elif status['phase']=='connected': result+=f' · 等待定位（来源：{source}）'
    message=issues.get(status['issue'])
    if message: result+=' · '+message
    return result
