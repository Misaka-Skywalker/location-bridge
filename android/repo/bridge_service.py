#!/data/data/com.termux/files/usr/bin/python
"""Local Termux supervisor for the Location Bridge worker."""
import argparse
import asyncio
from collections import deque
import contextlib
import fcntl
import json
import hashlib
import hmac
import math
import os
from pathlib import Path
import re
import signal
import socket
import stat
import subprocess
import sys
import time
import uuid

TASK = Path(os.environ.get('LOCATION_BRIDGE_DIR', str(Path.home() / 'location-bridge')))
APP = TASK / 'service'
def target_udid(explicit=None, config_path=None):
    """Select exactly one explicitly configured device for either transport."""
    if explicit is None:
        path = config_path or TASK / 'bridge-config.json'
        try:
            with open(path, encoding='utf-8') as config_file:
                config = json.load(config_file)
        except (OSError, ValueError) as exc:
            raise ValueError('请通过 --udid 或本地 bridge-config.json 设置目标设备。') from exc
        if not isinstance(config, dict):
            raise ValueError('bridge-config.json 必须是 JSON 对象。')
        explicit = config.get('udid')
    if not isinstance(explicit, str) or not re.fullmatch(r'[A-Za-z0-9-]{1,128}', explicit):
        raise ValueError('无效的设备 UDID。')
    return explicit


def elapsed_time():
    # Same kernel boot clock in native Termux and PRoot; includes device sleep.
    if hasattr(time, 'CLOCK_BOOTTIME'):
        return time.clock_gettime(time.CLOCK_BOOTTIME)
    return time.monotonic()  # Desktop tests; these clocks coincide without suspend.


def fusion_key(config):
    if (not isinstance(config, dict) or config.get('package') != 'org.locationbridge.source'
            or not isinstance(config.get('helper_key'), str) or not re.fullmatch(r'[0-9a-f]{64}', config['helper_key'])):
        raise ValueError('请先安装定位助手并运行配置脚本，不能验证助手身份。')
    return config['helper_key']


def number(value, name, low, high):
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or not low <= value <= high:
        raise ValueError('invalid ' + name)
    return value


def parse_location(raw, run_id, sequence, now, provider, command_elapsed=0):
    if provider not in ('gps', 'network') or not isinstance(raw, dict) or raw.get('provider') != provider:
        raise ValueError('requested provider mismatch')
    return {'run_id': run_id, 'sequence': sequence, 'source': provider,
            'latitude': number(raw.get('latitude'), 'latitude', -90, 90),
            'longitude': number(raw.get('longitude'), 'longitude', -180, 180),
            'accuracy_m': number(raw.get('accuracy'), 'accuracy', 0, 1e9),
            'received_monotonic': now,
            # Count the whole command duration as a conservative upper bound on
            # API-to-CLI delay. Slow initial acquisitions may be rejected.
            'age_at_receive_ms': number(raw.get('elapsedMs'), 'elapsedMs', 0, 1e12) +
                1000 * number(command_elapsed, 'command_elapsed', 0, 600)}


def parse_gps(raw, run_id, sequence, now, command_elapsed=0):
    return parse_location(raw, run_id, sequence, now, 'gps', command_elapsed)


def fix_age_seconds(fix, now):
    return now - fix['received_monotonic'] + fix['age_at_receive_ms'] / 1000


class FixArbiter:
    def __init__(self, run_id):
        self.run_id = run_id
        self.latest = {}
        self.emitted = {}
        self.sequence = 0

    def update(self, provider, fix):
        if provider not in ('gps', 'network') or fix['source'] != provider or fix['run_id'] != self.run_id:
            raise ValueError('invalid provider')
        self.latest[provider] = fix

    def select(self, now):
        for provider in ('gps', 'network'):
            fix = self.latest.get(provider)
            if fix is None: continue
            age = fix_age_seconds(fix, now)
            if not math.isfinite(age) or not 0 <= age <= 10: continue
            token = fix['sequence']
            if self.emitted.get(provider) == token: return None
            self.sequence += 1
            self.emitted[provider] = token
            return dict(fix, sequence=self.sequence)
        return None


def track_index(elapsed, duration):
    return min(duration, max(0, math.floor(elapsed)))


def track_done(elapsed, duration):
    return elapsed >= duration + 5


def track_point(index, duration):
    # Smooth closed ellipse, followed by a five-second hold at the west point.
    theta = 2 * math.pi * min(1, max(0, index / duration))
    return 30.5700 + .0150 * math.sin(theta), 104.0800 - .0200 * math.cos(theta)


def atomic_json(path, value):
    temp = path.with_name('.' + path.name + '.' + uuid.uuid4().hex)
    try:
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as handle:
            json.dump(value, handle, ensure_ascii=False)
            handle.flush()
        os.replace(temp, path)
    finally:
        with contextlib.suppress(FileNotFoundError): temp.unlink()


def read_json(path):
    try:
        with path.open() as f: return json.load(f)
    except (OSError, ValueError): return None


def normalize_final_worker_state(run_id, value):
    return value if isinstance(value, dict) and value.get('run_id') == run_id else {}


def current():
    value = read_json(APP / 'current.json')
    if not isinstance(value, dict): return None
    run_id = value.get('run_id', '')
    if not re.fullmatch(r'run-[0-9a-f]{32}', str(run_id)): return None
    if value.get('session_dir') != str(APP / run_id): return None
    return value


def lock_file():
    APP.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(APP, 0o700)
    return (APP / 'service.lock').open('a+')


def running():
    with lock_file() as lock:
        try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: return True
        return False


def usb_exited_with_error(returncode):
    return returncode is not None and returncode != 0


def describe_worker(state, fix, now, collector=None):
    labels = {'waiting':'等待中', 'running':'运行中', 'stale':'样本过期',
              'reconnecting':'正在重连', 'stopped':'已停止', 'error':'错误'}
    state_label = labels.get(state.get('state'), '未知')
    connected = state.get('connected')
    connection = '已连接' if connected is True else '未连接' if connected is False else '未知'
    pending = state.get('clear_pending')
    clear = '待确认' if pending is True else '无待清除' if pending is False else '未知'
    seq = state.get('last_sequence')
    seq_text = str(seq) if isinstance(seq, int) and not isinstance(seq, bool) else '无'
    age = '未知'
    if isinstance(fix, dict):
        try:
            seconds = fix_age_seconds(fix, now)
            if math.isfinite(seconds) and seconds >= 0: age = f'{seconds:.1f}秒'
        except (KeyError, TypeError, ValueError): pass
    error = state.get('last_error')
    source = state.get('last_source')
    accuracy = state.get('last_accuracy_m')
    if source not in ('gps', 'network', 'fused', 'test', 'inertial'):
        source = fix.get('source') if isinstance(fix, dict) else None
        accuracy = fix.get('accuracy_m') if isinstance(fix, dict) else None
    if source not in ('gps', 'network', 'fused', 'test', 'inertial'): source = '未知'
    if isinstance(accuracy, bool) or not isinstance(accuracy, (int, float)) or not math.isfinite(accuracy) or accuracy < 0:
        accuracy_text = '未知'
    else: accuracy_text = f'{accuracy:.1f}米'
    prediction = ''
    if source == 'inertial':
        source = '惯性推算'
        accuracy_text = '模型误差估计 ' + accuracy_text
        predicted = state.get('last_prediction_age_ms')
        if type(predicted) in (int, float) and math.isfinite(predicted) and 0 <= predicted <= 8000:
            prediction = f'；锚点预测时长：{predicted / 1000:.1f}秒'
    errors = []
    if isinstance(collector, dict) and isinstance(collector.get('providers'), dict):
        for provider in ('gps', 'network'):
            item = collector['providers'].get(provider)
            if isinstance(item, dict) and item.get('error') in ('timeout', 'command_unavailable', 'command_failed', 'invalid_response', 'invalid_sample', 'stale_sample'):
                errors.append(f"{provider}:{item['error']}")
    helper = ''
    if isinstance(collector, dict) and 'helper_error' in collector:
        allowed = {'unavailable','invalid_response','authentication_failed',
                   'timeout','no_fresh_sample','stopped','protocol_error','invalid_sample'}
        issue = collector.get('helper_error')
        helper = '；定位助手：' + (issue if issue in allowed else '正常' if issue is None else '响应异常')
    selection_text = ''
    if isinstance(collector, dict) and isinstance(collector.get('selection'), dict):
        choice = collector['selection']
        reason = choice.get('reason')
        if isinstance(reason, str) and re.fullmatch(r'[a-z_]{1,64}', reason):
            selection_text = f'；选源：{reason}'
            for key, label in (('rejected_count','拒绝'), ('switch_count','切换')):
                value = choice.get(key)
                if type(value) is int and 0 <= value <= 1_000_000_000:
                    selection_text += f' {label}:{value}'
    return f'工作：{state_label}；连接：{connection}；来源：{source}；精度：{accuracy_text}；样本估计年龄：{age}；序号：{seq_text}；清除：{clear}；错误：{str(error)[:100] if error else "无"}；采集错误：{",".join(errors) if errors else "无"}{helper}{selection_text}{prediction}'


async def publish_status(view, key):
    from bridge_status import encode
    try:
        payload,signature=encode(view,key)
        await bounded('am','broadcast','--user','0','--receiver-foreground','-a',
            'org.locationbridge.source.BRIDGE_STATUS','-n','org.locationbridge.source/.BridgeStatusReceiver',
            '--es','payload',payload,'--es','signature',signature,timeout=2)
    except (OSError, ValueError, asyncio.TimeoutError):
        pass


def status():
    from bridge_status import make_status, brief
    info=current()
    now=elapsed_time()
    if info is None:
        info={'run_id':None,'mode':'none','transport':'none'}
        view=make_status(info,{},None,phase='not_started',issue='none',now=now,utc_ms=int(time.time()*1000))
        print('桥接：未启动')
        print(brief(view))
    else:
        alive=running(); session=Path(info['session_dir'])
        state=read_json(session/'worker.state.json') or {}
        fix=read_json(session/'fix.json')
        marker=read_json(session/'final.status.json') if not alive else None
        valid_marker=(isinstance(marker,dict) and marker.get('run_id')==info['run_id']
                      and marker.get('phase') in ('stopped','error')
                      and marker.get('issue') in ('none','worker_error','clear_unconfirmed','bridge_error','usb_missing')
                      and type(marker.get('clear_pending')) is bool
                      and (marker['phase'] != 'stopped' or (marker['clear_pending'] is False and marker['issue']=='none')))
        if alive: phase,issue='connecting','none'
        elif valid_marker: phase,issue=marker['phase'],marker['issue']
        else: phase,issue='unknown','clear_unconfirmed'; state={}; fix=None
        view=make_status(info,state,fix,phase=phase,issue=issue,now=now,utc_ms=int(time.time()*1000))
        if valid_marker: view['clear_pending']=marker['clear_pending']
        mode={'gps':'真实 GPS','hybrid':'混合定位','fusion':'系统融合定位','test':'模拟轨迹'}.get(info['mode'],'未知')
        bridge='运行中' if alive else '已停止' if valid_marker and phase=='stopped' else '异常' if valid_marker else '状态未知'
        print(f"桥接：{bridge}；模式：{mode}；连接：{info['transport'].upper()}")
        print(brief(view))
        print(f"清除：{ '待确认' if view['clear_pending'] is True else '无待清除' if view['clear_pending'] is False else '未知' }；日志：{session / 'worker.log'}")
    try: key=fusion_key(read_json(TASK/'source-config.json'))
    except ValueError: return
    asyncio.run(publish_status(view,key))


def stop():
    info = current()
    if not info or not running():
        print('服务未运行；如上次异常退出，请检查清除状态。')
        return
    atomic_json(Path(info['session_dir']) / 'stop.json', {'run_id':info['run_id']})
    for _ in range(350):
        if not running() or (current() or {}).get('run_id') != info['run_id']:
            print('停止请求已完成。'); status(); return
        time.sleep(.1)
    print('停止仍在清理，请稍后查看状态；未向未知进程发送信号。')


def prepare_socket(path):
    if not os.path.lexists(path): return
    if not stat.S_ISSOCK(os.lstat(path).st_mode): raise RuntimeError('USB socket 路径被非 socket 文件占用')
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(2)
        try: sock.connect(str(path))
        except (ConnectionRefusedError, FileNotFoundError):
            with contextlib.suppress(FileNotFoundError): path.unlink()
        else: raise RuntimeError('已有 USB 服务占用 socket，不能覆盖')


async def bounded(*args, timeout=5):
    proc = await asyncio.create_subprocess_exec(*args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, start_new_session=True)
    try:
        out, err = await asyncio.wait_for(proc.communicate(), timeout)
    except (asyncio.TimeoutError, asyncio.CancelledError):
        with contextlib.suppress(ProcessLookupError): os.killpg(proc.pid, signal.SIGKILL)
        await proc.communicate()
        raise
    return proc.returncode, out, err


async def collect_locations(session, run_id, done, providers):
    arbiter = FixArbiter(run_id)
    records = {p: {'provider':p, 'last_success_monotonic':None, 'accuracy_m':None,
                   'error':None, 'command_elapsed':None, 'attempts':0} for p in providers}
    def write_status():
        atomic_json(session / 'collector.status.json', {'run_id':run_id, 'providers':records})

    async def provider_loop(provider):
        sample_id = 0
        record = records[provider]
        while not done.is_set():
            started = elapsed_time()
            record['attempts'] += 1
            record['error'] = None
            write_status()
            error = None
            try:
                code, out, _ = await bounded('termux-location', '-p', provider, '-r', 'once', timeout=45)
                now = elapsed_time()
                if code: raise RuntimeError('command_failed')
                try: raw = json.loads(out)
                except (ValueError, UnicodeError): raise ValueError('invalid_response') from None
                try: fix = parse_location(raw, run_id, sample_id + 1, now, provider, now - started)
                except ValueError: raise ValueError('invalid_sample') from None
                if fix_age_seconds(fix, now) > 10: raise ValueError('stale_sample')
                sample_id += 1
                arbiter.update(provider, fix)
                record.update(last_success_monotonic=now, accuracy_m=fix['accuracy_m'])
            except asyncio.TimeoutError:
                error = 'timeout'
            except OSError:
                error = 'command_unavailable'
            except (RuntimeError, ValueError) as exc:
                error = str(exc) if str(exc) in ('command_failed', 'invalid_response', 'invalid_sample', 'stale_sample') else 'invalid_sample'
            record['error'] = error
            record['command_elapsed'] = elapsed_time() - started
            write_status()
            try: await asyncio.wait_for(done.wait(), 15 if error else 1)
            except asyncio.TimeoutError: pass

    tasks = [asyncio.create_task(provider_loop(provider)) for provider in providers]
    write_status()
    try:
        while not done.is_set():
            fix = arbiter.select(elapsed_time())
            if fix is not None: atomic_json(session / 'fix.json', fix)
            try: await asyncio.wait_for(done.wait(), .2)
            except asyncio.TimeoutError: pass
    finally:
        for task in tasks: task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


async def generate_track(session, run_id, duration, done):
    while not done.is_set():
        state = read_json(session / 'worker.state.json') or {}
        if state.get('run_id') == run_id and state.get('connected'): break
        try: await asyncio.wait_for(done.wait(), .25)
        except asyncio.TimeoutError: pass
    if done.is_set(): return
    start = elapsed_time(); last = -1; seq = 0
    while not done.is_set():
        now = elapsed_time(); elapsed = now - start
        if track_done(elapsed, duration):
            atomic_json(session / 'stop.json', {'run_id':run_id})
            return
        tick = math.floor(elapsed)
        if tick != last:
            lat, lon = track_point(track_index(elapsed, duration), duration)
            seq += 1
            atomic_json(session / 'fix.json', {'run_id':run_id, 'sequence':seq, 'source':'test', 'latitude':lat, 'longitude':lon, 'accuracy_m':0, 'received_monotonic':now, 'age_at_receive_ms':0})
            last = tick
        try: await asyncio.wait_for(done.wait(), .1)
        except asyncio.TimeoutError: pass


class FusionOrder:
    # elapsedRealtime uses the same boot clock across helper process restarts.
    def __init__(self): self.last_elapsed = 0
    def accept(self, session_id, elapsed):
        if type(elapsed) is not int or elapsed <= self.last_elapsed: return False
        self.last_elapsed = elapsed
        return True


def boottime_ms():
    return int(time.clock_gettime(time.CLOCK_BOOTTIME) * 1000)


def helper_start_deadline():
    return boottime_ms() + 8000


async def collect_fusion(session, run_id, done, helper_key, client=None):
    if client is None:
        import bridge_fusion as client
    seen = set()
    recent = deque()
    sequence = 0
    order = FusionOrder()
    last_revocation = None
    def revoke(reason):
        nonlocal last_revocation
        # Retain one tombstone until a new sample is accepted; never extend its age.
        if last_revocation != reason:
            atomic_json(session / 'fix.json', {'run_id':run_id, 'unavailable':True, 'reason':reason})
            last_revocation = reason
    while not done.is_set():
        record = {'run_id':run_id, 'helper_error':None, 'providers':{}}
        started = elapsed_time()
        poll_interval = .25
        try:
            raw = await asyncio.to_thread(client.request_snapshot, helper_key)
            if isinstance(raw.get('sample'), dict) and raw['sample'].get('source') == 'inertial': poll_interval = .1
            now = elapsed_time()
            fix, token = client.parse_snapshot(raw, run_id, sequence + 1, now, now - started)
            # Only protocol metadata is persisted here; actual positions stay in fix.json.
            record['providers'] = raw.get('providers', {})
            selection = raw.get('selection')
            if isinstance(selection, dict):
                reason = selection.get('reason')
                selected = {}
                if isinstance(reason, str) and re.fullmatch(r'[a-z_]{1,64}', reason): selected['reason'] = reason
                for key in ('rejected_count', 'switch_count'):
                    value = selection.get(key)
                    if type(value) is int and 0 <= value <= 1_000_000_000: selected[key] = value
                if selected: record['selection'] = selected
            if fix is None:
                record['helper_error'] = 'no_fresh_sample' if raw.get('running') else 'stopped'
                revoke(record['helper_error'])
            elif token not in seen and order.accept(raw['session_id'], raw['sample']['elapsed_realtime_nanos']):
                sequence += 1
                seen.add(token)
                recent.append(token)
                if len(recent) > 256: seen.discard(recent.popleft())
                atomic_json(session / 'fix.json', fix)
                last_revocation = None
            # Never refresh received time or sample age when polling the same sample.
        except (OSError, ValueError) as exc:
            issue = str(exc)
            record['helper_error'] = issue if issue in {'authentication_failed','timeout'} else 'unavailable' if isinstance(exc,OSError) or issue == 'socket_error' else 'invalid_response'
            revoke(record['helper_error'])
        atomic_json(session / 'collector.status.json', record)
        try: await asyncio.wait_for(done.wait(), poll_interval)
        except asyncio.TimeoutError: pass


async def supervise(args, info):
    session = Path(info['session_dir']); run_id = info['run_id']; done = asyncio.Event()
    worker = usb = collector = None
    helper_key = None
    helper_deadline = None
    helper_activated = False
    from bridge_status import make_status, encode, brief
    status_phase = 'connecting'
    status_issue = 'none'
    final_pending = None
    status_wakeup = asyncio.Event()
    status_key = None
    try: status_key = fusion_key(read_json(TASK / 'source-config.json'))
    except ValueError: pass
    def status_view():
        state = read_json(session / 'worker.state.json') or {}
        fix = read_json(session / 'fix.json')
        view=make_status(info,state,fix,phase=status_phase,issue=status_issue,
                         now=elapsed_time(),utc_ms=int(time.time()*1000))
        if status_phase in ('stopped','error') and final_pending is not None:
            view['clear_pending']=final_pending
        return view
    async def send_status():
        if status_key is None: return
        await publish_status(status_view(),status_key)
    async def status_loop():
        while not done.is_set():
            status_wakeup.clear()
            await send_status()
            try: await asyncio.wait_for(status_wakeup.wait(),2)
            except asyncio.TimeoutError: pass
    broadcaster = asyncio.create_task(status_loop()) if status_key else None
    final_phase = 'stopped'
    sock = APP / 'usbmuxd.sock'; pidfile = APP / 'usbmuxd.pid'
    def set_state(message):
        atomic_json(session / 'supervisor.state.json', {'run_id':run_id, 'message':message, 'updated_monotonic':elapsed_time()})
        print(message, flush=True)
    loop = asyncio.get_running_loop()
    def request_stop(): atomic_json(session / 'stop.json', {'run_id':run_id})
    for sig in (signal.SIGTERM, signal.SIGINT): loop.add_signal_handler(sig, request_stop)
    try:
        if args.mode == 'fusion':
            helper_key = fusion_key(read_json(TASK / 'source-config.json'))
        set_state('正在连接 iPad。')
        try: await bounded('termux-wake-lock', timeout=5)
        except (OSError, asyncio.TimeoutError): set_state('无法取得唤醒锁，后台运行可能中断。')
        bind = []; env = []
        if args.transport == 'usb':
            prepare_socket(sock)
            code, out, _ = await bounded('termux-usb', '-l', timeout=15)
            devices = json.loads(out)
            if code or not isinstance(devices, list) or len(devices) != 1 or not isinstance(devices[0], str) or not re.fullmatch(r'/dev/bus/usb/\d+/\d+', devices[0]):
                status_issue = 'usb_missing'
                raise RuntimeError('未找到唯一 USB 设备；请连接 iPad，设置 USB 控制权为本机并打开 OTG。')
            # Remove only an old pidfile after confirming there is no live service socket.
            with contextlib.suppress(FileNotFoundError): pidfile.unlink()
            status_issue = 'waiting_usb'; status_wakeup.set()
            set_state('等待 USB 授权；如设备忙，请关闭文件或照片传输。')
            usb = await asyncio.create_subprocess_exec('termux-usb', '-r', '-E', '-e', str(TASK / 'bridge-usb-daemon.sh'), devices[0], stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
            for _ in range(600):
                if (read_json(session / 'stop.json') or {}).get('run_id') == run_id: raise InterruptedError('已停止')
                if sock.is_socket():
                    break
                if usb_exited_with_error(usb.returncode): raise RuntimeError('USB 授权或服务启动失败；如设备忙请关闭文件或照片传输。')
                await asyncio.sleep(.1)
            else: raise RuntimeError('USB 服务等待超时，请检查授权与 USB 控制权。')
            status_issue = 'none'; status_wakeup.set()
            bind = ['--bind', f'{sock}:/mnt/usbmuxd']
            env = ['USBMUXD_SOCKET_ADDRESS=/mnt/usbmuxd']
        if args.mode == 'fusion':
            import bridge_fusion
            helper_deadline = helper_start_deadline()
            code, out, err = await bounded('am', 'start', '-n', 'org.locationbridge.source/.MainActivity',
                                           '--ez', 'bridge_start', 'true', '--el', 'bridge_deadline',
                                           str(helper_deadline), '--es', 'bridge_auth',
                                           hmac.new(bytes.fromhex(helper_key), ('start\n'+str(helper_deadline)).encode(),hashlib.sha256).hexdigest(), timeout=10)
            if code or b'Error' in out or b'Exception' in err:
                raise RuntimeError('无法打开定位助手，请先授权精确位置。')
            status_issue = 'waiting_helper'; status_wakeup.set()
            set_state('等待定位助手就绪。')
            while boottime_ms() < helper_deadline:
                if (read_json(session / 'stop.json') or {}).get('run_id') == run_id:
                    raise InterruptedError('已停止')
                try:
                    ready = await asyncio.to_thread(bridge_fusion.request_snapshot, helper_key)
                    if ready.get('running'):
                        if (read_json(session / 'stop.json') or {}).get('run_id') == run_id:
                            raise InterruptedError('已停止')
                        response = await asyncio.to_thread(bridge_fusion.request_snapshot, helper_key, 'activate')
                        if not response.get('running'): raise RuntimeError('定位助手未启动。')
                        helper_activated = True
                        break
                except ValueError:
                    pass
                await asyncio.sleep(.2)
            else:
                raise RuntimeError('定位助手启动超时，请检查精确位置授权与系统定位。')
            if (read_json(session / 'stop.json') or {}).get('run_id') == run_id:
                raise InterruptedError('已停止')
        cmd = ['proot-distro','login','--isolated','--bind',f'{TASK}:/mnt/lb',*bind,'lb-python','--','env',*env,'/opt/location-bridge/bin/python','/mnt/lb/bridge_worker.py','--identifier',args.udid,'--transport',args.transport,'--session-dir',f'/mnt/lb/service/{run_id}','--run-id',run_id,'--mode',args.mode]
        status_issue = 'none'; status_wakeup.set()
        log_path = session / 'worker.log'
        fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, 'ab') as log:
            worker = await asyncio.create_subprocess_exec(*cmd, stdout=log, stderr=log)
        print(f'工作日志：{log_path}', flush=True)
        if args.mode == 'test':
            collection = generate_track(session, run_id, args.duration, done)
        elif args.mode == 'fusion':
            collection = collect_fusion(session, run_id, done, helper_key)
        else:
            collection = collect_locations(session, run_id, done, ('gps', 'network') if args.mode == 'hybrid' else ('gps',))
        collector = asyncio.create_task(collection)
        set_state('服务已启动；正在等待连接。')
        previous = None
        while worker.returncode is None:
            if collector.done():
                # A crashed collector must not leave an unsupervised injection session.
                failure = collector.exception()
                if failure: raise RuntimeError('定位采集异常，正在停止桥接。')
            if (read_json(session / 'stop.json') or {}).get('run_id') == run_id: break
            state = read_json(session / 'worker.state.json') or {}
            if not isinstance(state, dict) or state.get('run_id') != run_id: state = {}
            fix = read_json(session / 'fix.json')
            if not isinstance(fix, dict) or fix.get('run_id') != run_id: fix = None
            collector_status = read_json(session / 'collector.status.json')
            if not isinstance(collector_status, dict) or collector_status.get('run_id') != run_id: collector_status = None
            view = status_view()
            signature = (view['phase'],view['worker_state'],view['source'],view['issue'],view['clear_pending'])
            if signature != previous:
                print(brief(view), flush=True)
                status_wakeup.set()
                previous = signature
            await asyncio.sleep(.2)
        status_phase = 'stopping'; status_wakeup.set()
        if collector:
            collector.cancel()
            await asyncio.gather(collector, return_exceptions=True)
            collector = None
        if worker.returncode is not None:
            if worker.returncode != 0: final_phase = 'error'; status_issue = 'worker_error'
            set_state(f'工作进程退出，代码 {worker.returncode}；请检查清除状态。')
        else:
            set_state('正在停止并清除模拟位置。')
            try: await asyncio.wait_for(worker.wait(), 35)
            except asyncio.TimeoutError:
                final_phase = 'error'; status_issue = 'clear_unconfirmed'
                set_state('清理超时，清除状态未知；正在结束工作进程。')
                worker.terminate()
                try: await asyncio.wait_for(worker.wait(), 10)
                except asyncio.TimeoutError:
                    worker.kill(); await worker.wait()
                set_state('工作进程被强制结束；清除状态未知，请在 iPad 上检查。')
    except (OSError, ValueError, RuntimeError, asyncio.TimeoutError, InterruptedError) as exc:
        if not isinstance(exc, InterruptedError):
            final_phase = 'error'
            if status_issue != 'usb_missing': status_issue = 'bridge_error'
        status_phase = 'stopping'; status_wakeup.set()
        set_state(str(exc))
    finally:
        status_phase = 'stopping'; status_wakeup.set()
        if collector:
            collector.cancel()
            await asyncio.gather(collector, return_exceptions=True)
        if worker and worker.returncode is None:
            # Covers unexpected supervisor errors, too; never leave an unsupervised injector.
            atomic_json(session / 'stop.json', {'run_id':run_id})
            try: await asyncio.wait_for(worker.wait(), 35)
            except asyncio.TimeoutError:
                final_phase = 'error'; status_issue = 'clear_unconfirmed'
                set_state('清理超时，清除状态未知。')
                worker.terminate()
                try: await asyncio.wait_for(worker.wait(), 10)
                except asyncio.TimeoutError: worker.kill(); await worker.wait()
        # The callback may create its pidfile just as a stop arrives. It is safe to
        # use only this run's pidfile: stale files were removed before launch.
        if usb and usb.returncode is None:
            usb.terminate()
            try: await asyncio.wait_for(usb.wait(), 5)
            except asyncio.TimeoutError: usb.kill(); await usb.wait()
        if usb and pidfile.exists():
            try:
                code, _, _ = await bounded('usbmuxd', '-X', '-P', str(pidfile), timeout=5)
                if code: set_state('USB 服务停止失败，请检查。')
            except (OSError, asyncio.TimeoutError): set_state('USB 服务停止失败，请检查。')
        if helper_deadline is not None:
            # Unactivated launch expires on Android's boot clock, even if its
            # Activity or service receives the request after the bridge stops.
            if not helper_activated:
                while boottime_ms() <= helper_deadline:
                    await asyncio.sleep(.1)
            try:
                import bridge_fusion
                reply = await asyncio.to_thread(bridge_fusion.request_snapshot, helper_key, 'stop')
                if reply.get('running') is not False: raise ValueError('stop_unconfirmed')
            except (OSError, ValueError):
                if helper_activated: set_state('无法确认定位助手停止；可在助手或通知中点停止。')
                else: set_state('启动期限已过；若助手仍运行，请在助手或通知中点停止。')
        try: await bounded('termux-wake-unlock', timeout=5)
        except (OSError, asyncio.TimeoutError): pass
        if worker is not None:
            final_state = normalize_final_worker_state(run_id, read_json(session / 'worker.state.json'))
            if final_state.get('clear_pending') is not False:
                final_phase = 'error'; status_issue = 'clear_unconfirmed'
            elif worker.returncode != 0:
                final_phase = 'error'; status_issue = 'worker_error'
        done.set()
        if broadcaster:
            broadcaster.cancel()
            await asyncio.gather(broadcaster, return_exceptions=True)
        status_phase = final_phase
        final_pending = False if worker is None else final_state.get('clear_pending') is not False
        status_issue = status_issue if status_issue in ('none','worker_error','clear_unconfirmed','bridge_error','usb_missing') else 'none'
        atomic_json(session / 'final.status.json', {'run_id':run_id,'phase':final_phase,
                    'issue':status_issue,'clear_pending':final_pending})
        await send_status()
        for sig in (signal.SIGTERM, signal.SIGINT): loop.remove_signal_handler(sig)


def main():
    parser = argparse.ArgumentParser(); sub = parser.add_subparsers(dest='command', required=True)
    start = sub.add_parser('start'); start.add_argument('--mode', choices=['gps','hybrid','fusion','test'], required=True); start.add_argument('--transport', choices=['usb','wifi'], default='usb'); start.add_argument('--duration', type=int, default=90); start.add_argument('--udid', help='目标设备 UDID；默认读取本地 bridge-config.json')
    sub.add_parser('stop'); sub.add_parser('status'); args = parser.parse_args()
    if args.command == 'stop': stop(); return
    if args.command == 'status': status(); return
    if not 30 <= args.duration <= 180: parser.error('duration 必须在 30 到 180 秒之间')
    try: args.udid = target_udid(args.udid)
    except ValueError as exc: parser.error(str(exc))
    with lock_file() as lock:
        try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            old = current() or {}
            print('相同模式已运行。' if old.get('mode') == args.mode and old.get('transport') == args.transport else '服务已运行，请先停止再切换模式。')
            return
        run_id = 'run-' + uuid.uuid4().hex; session = APP / run_id; session.mkdir(mode=0o700)
        info = {'run_id':run_id,'session_dir':str(session),'pid':os.getpid(),'mode':args.mode,'transport':args.transport}
        atomic_json(APP / 'current.json',info)
        asyncio.run(supervise(args, info))

if __name__ == '__main__': main()
