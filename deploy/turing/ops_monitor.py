"""Single-instance local health monitor. No notifications or service restarts.
Default: one ops_probe.monitor poll every 30 seconds, minimum interval 10 seconds.
Only alert transitions, probe-error transitions and lifecycle events enter logs.
"""
import argparse
from contextlib import contextmanager
import json
import logging
from logging.handlers import RotatingFileHandler
import math
import os
from pathlib import Path
import signal
import stat
import threading
import time

from ops_probe import TARGETS, monitor


class AlreadyRunning(Exception):
    pass


def interval_seconds(value):
    number = float(value)
    if not math.isfinite(number) or not 10 <= number <= 3600:
        raise ValueError('Interval must be between 10 and 3600 seconds')
    return number


def private_folder(root, name):
    root = Path(root).resolve(strict=True)
    folder = root / name
    if folder.is_symlink():
        raise ValueError('Invalid monitor directory')
    folder.mkdir(mode=0o700, exist_ok=True)
    if folder.resolve().parent != root:
        raise ValueError('Invalid monitor directory')
    return folder


@contextmanager
def instance_lock(root):
    # Advisory lock lifetime is the process lifetime; no stale PID deletion or
    # probing/termination of other processes. Other platforms fail closed.
    import fcntl
    path = private_folder(root, 'run') / 'ops-monitor.lock'
    if path.is_symlink():
        raise ValueError('Invalid monitor lock')
    descriptor = os.open(path, os.O_RDWR | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise ValueError('Invalid monitor lock')
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise AlreadyRunning() from None
        os.chmod(path, 0o600)
        yield
    finally:
        os.close(descriptor)  # Releases only our own kernel lock.


class PrivateRotatingHandler(RotatingFileHandler):
    def _open(self):
        descriptor = os.open(self.baseFilename, os.O_WRONLY | os.O_CREAT | os.O_APPEND |
                             getattr(os, 'O_NOFOLLOW', 0), 0o600)
        return os.fdopen(descriptor, 'a', encoding='utf-8')

    def handleError(self, record):
        raise RuntimeError('Monitor log unavailable') from None


def event_logger(root, max_bytes=1024 * 1024, backup_count=3):
    if not 256 <= max_bytes <= 1024 * 1024 or not 1 <= backup_count <= 3:
        raise ValueError('Invalid log bounds')
    path = private_folder(root, 'logs') / 'ops-monitor.jsonl'
    for candidate in [path, *(Path(str(path) + '.' + str(i)) for i in range(1, backup_count + 1))]:
        if candidate.is_symlink() or candidate.exists() and not candidate.is_file():
            raise ValueError('Invalid monitor log')
    handler = PrivateRotatingHandler(path, maxBytes=max_bytes, backupCount=backup_count, encoding='utf-8')
    path.chmod(0o600)
    handler.setFormatter(logging.Formatter('%(message)s'))
    logger = logging.Logger('yangbin.ops-monitor')
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    logger.propagate = False
    return logger, handler


def emit(logger, event, component=None):
    allowed = {'monitor_started', 'monitor_stopped', 'firing', 'resolved', 'probe_failed', 'probe_recovered'}
    if event not in allowed or component is not None and component not in TARGETS:
        raise ValueError('Invalid monitor event')
    item = {'time': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'event': event}
    if component is not None:
        item['component'] = component
    logger.info(json.dumps(item, separators=(',', ':')))


def poll_loop(root, interval, stop, logger, once=False, probe=monitor):
    interval = interval_seconds(interval)
    failed = False
    latest = {'healthy': False, 'events': [], 'probeFailed': True}
    emit(logger, 'monitor_started')
    try:
        while not stop.is_set():
            try:
                result = probe(root)
                # Validate and project before recording anything. Never log the
                # supplied result/exception wholesale or user-controlled fields.
                if type(result.get('healthy')) is not bool or not isinstance(result.get('events'), list):
                    raise ValueError('Invalid probe result')
                events = []
                for event in result['events']:
                    if event.get('event') not in ('firing', 'resolved') or event.get('component') not in TARGETS:
                        raise ValueError('Invalid probe event')
                    events.append({'event': event['event'], 'component': event['component']})
                if len(events) > len(TARGETS):
                    raise ValueError('Too many probe events')
                latest = {'healthy': result['healthy'], 'events': events, 'probeFailed': False}
            except Exception:
                if not failed:
                    emit(logger, 'probe_failed')
                failed = True
                latest = {'healthy': False, 'events': [], 'probeFailed': True}
            else:
                if failed:
                    emit(logger, 'probe_recovered')
                failed = False
                for event in events:
                    emit(logger, event['event'], event['component'])
            if once:
                break
            if stop.wait(interval):
                break
    finally:
        emit(logger, 'monitor_stopped')
    return latest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.home() / 'apps/yangbin-ai-agent')
    parser.add_argument('--interval', type=float, default=30)
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    stop = threading.Event()
    handlers = {}
    try:
        interval = interval_seconds(args.interval)
        with instance_lock(args.root):
            logger, handler = event_logger(args.root)
            try:
                for number in (signal.SIGTERM, signal.SIGINT):
                    handlers[number] = signal.signal(number, lambda *_: stop.set())
                result = poll_loop(args.root, interval, stop, logger, once=args.once)
            finally:
                for number, original in handlers.items():
                    signal.signal(number, original)
                handler.close()
        if args.once:
            print(json.dumps(result, separators=(',', ':')))
            return 0 if result['healthy'] else 1
        return 0
    except AlreadyRunning:
        print('{"started":false,"alreadyRunning":true}')
        return 3
    except Exception:
        print('{"started":false,"failed":true,"detailsSuppressed":true}')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
