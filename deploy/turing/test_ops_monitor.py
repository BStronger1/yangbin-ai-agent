from contextlib import contextmanager
import json
import logging
import os
from pathlib import Path
import signal
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import ops_monitor


class StopAfter:
    def __init__(self, polls):
        self.polls, self.waits, self.stopped = polls, [], False
    def is_set(self):
        return self.stopped
    def wait(self, interval):
        self.waits.append(interval)
        return len(self.waits) >= self.polls
    def set(self):
        self.stopped = True


class MonitorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.logger, self.handler = ops_monitor.event_logger(self.root)
        self.addCleanup(self.handler.close)

    def events(self):
        self.handler.flush()
        return [json.loads(line) for line in (self.root / 'logs/ops-monitor.jsonl').read_text().splitlines()]

    def test_interval_bounds_and_nonfinite(self):
        self.assertEqual(ops_monitor.interval_seconds(30), 30)
        self.assertEqual(ops_monitor.interval_seconds(10), 10)
        for value in (0, 9.99, 3601, float('nan'), float('inf')):
            with self.assertRaises(ValueError):
                ops_monitor.interval_seconds(value)

    def test_healthy_unchanged_does_not_log_every_poll(self):
        probe = Mock(return_value={'healthy': True, 'events': []})
        stop = StopAfter(3)
        result = ops_monitor.poll_loop(self.root, 30, stop, self.logger, probe=probe)
        self.assertEqual(probe.call_count, 3)
        self.assertTrue(result['healthy'])
        self.assertEqual(stop.waits, [30, 30, 30])
        self.assertEqual([item['event'] for item in self.events()], ['monitor_started','monitor_stopped'])

    def test_alert_and_recovery_events_are_projected(self):
        probe = Mock(side_effect=[
            {'healthy':False,'events':[{'event':'firing','component':'backend','private':'secret-marker'}]},
            {'healthy':False,'events':[]},
            {'healthy':True,'events':[{'event':'resolved','component':'backend'}]}])
        ops_monitor.poll_loop(self.root, 10, StopAfter(3), self.logger, probe=probe)
        events = self.events()
        self.assertEqual([item['event'] for item in events], ['monitor_started','firing','resolved','monitor_stopped'])
        self.assertNotIn('secret-marker', json.dumps(events))
        self.assertEqual(events[1]['component'], 'backend')

    def test_errors_are_deduped_and_recovery_logged_without_exception_content(self):
        probe = Mock(side_effect=[RuntimeError('private-key'), RuntimeError('private-key'),
                                 {'healthy':True,'events':[]}])
        ops_monitor.poll_loop(self.root, 30, StopAfter(3), self.logger, probe=probe)
        events = self.events()
        self.assertEqual([item['event'] for item in events], ['monitor_started','probe_failed','probe_recovered','monitor_stopped'])
        self.assertNotIn('private-key', json.dumps(events))

    def test_unknown_event_is_not_logged(self):
        probe = Mock(return_value={'healthy':False,'events':[{'event':'private-key','component':'secret'}]})
        result = ops_monitor.poll_loop(self.root, 10, StopAfter(1), self.logger, once=True, probe=probe)
        self.assertTrue(result['probeFailed'])
        self.assertEqual([item['event'] for item in self.events()], ['monitor_started','probe_failed','monitor_stopped'])

    def test_once_polls_exactly_once_without_waiting(self):
        probe = Mock(return_value={'healthy': True, 'events': []})
        stop = StopAfter(1)
        ops_monitor.poll_loop(self.root, 30, stop, self.logger, once=True, probe=probe)
        probe.assert_called_once_with(self.root)
        self.assertEqual(stop.waits, [])

    def test_preexisting_stop_prevents_poll(self):
        stop = StopAfter(1)
        stop.set()
        probe = Mock()
        ops_monitor.poll_loop(self.root, 30, stop, self.logger, probe=probe)
        probe.assert_not_called()

    def test_rotation_is_bounded_and_each_record_json(self):
        self.handler.close()
        logger, handler = ops_monitor.event_logger(self.root, max_bytes=256, backup_count=2)
        try:
            for index in range(100):
                ops_monitor.emit(logger, 'firing' if index % 2 else 'resolved', 'backend')
        finally:
            handler.close()
        files = list((self.root / 'logs').glob('ops-monitor.jsonl*'))
        self.assertLessEqual(len(files), 3)
        for path in files:
            self.assertLessEqual(path.stat().st_size, 256)
            for line in path.read_text().splitlines():
                self.assertIn(json.loads(line)['event'], ('firing','resolved'))

    def test_exclusive_flock_busy_refuses_second_consumer_and_closes_descriptors(self):
        fake = Mock(LOCK_EX=2, LOCK_NB=4)
        fake.flock.side_effect = [None, BlockingIOError()]
        with patch.dict(sys.modules, {'fcntl':fake}):
            with ops_monitor.instance_lock(self.root):
                with self.assertRaises(ops_monitor.AlreadyRunning):
                    with ops_monitor.instance_lock(self.root):
                        self.fail('Second consumer entered')
        for call in fake.flock.call_args_list:
            self.assertEqual(call.args[1], 6)
            with self.assertRaises(OSError):
                os.fstat(call.args[0])

    def test_signal_handler_sets_stop_and_restores_prior_handlers(self):
        callbacks = {}
        original = object()
        def register(number, handler):
            callbacks[number] = handler
            return original
        @contextmanager
        def acquired(_):
            yield
        def loop(root, interval, stop, logger, once=False):
            callbacks[signal.SIGTERM](signal.SIGTERM, None)
            self.assertTrue(stop.is_set())
            return {'healthy':True,'events':[],'probeFailed':False}
        with patch.object(sys, 'argv', ['ops_monitor.py','--root',str(self.root)]), \
             patch.object(ops_monitor, 'instance_lock', acquired), \
             patch.object(ops_monitor.signal, 'signal', side_effect=register), \
             patch.object(ops_monitor, 'poll_loop', side_effect=loop):
            self.assertEqual(ops_monitor.main(), 0)
        self.assertIs(callbacks[signal.SIGTERM], original)
        self.assertIs(callbacks[signal.SIGINT], original)


if __name__ == '__main__':
    unittest.main()
