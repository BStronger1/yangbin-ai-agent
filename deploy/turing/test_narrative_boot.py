"""Boot recovery contracts; all services and crontab are disposable fixtures."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import narrative_service as service


class NarrativeBootTest(unittest.TestCase):
    def test_install_preserves_other_jobs_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            old = '17 * * * * /other/job\n@reboot /old/ppt # yangbin-agent-ppt-user-service\n'
            writes = []
            def cron_read(*args, **kwargs):
                return subprocess.CompletedProcess(args, 0, writes[-1] if writes else old, '')
            with patch.object(service, 'ROOT', root), patch.object(service.subprocess, 'run', side_effect=cron_read), patch.object(service, 'run', side_effect=lambda *a, **k: writes.append(k['input'])):
                service.install_autostart()
                service.install_autostart()
            self.assertEqual(len(writes), 1)
            self.assertIn('17 * * * * /other/job\n', writes[0])
            self.assertEqual(writes[0].count('# yangbin-agent-ppt-user-service'), 1)
            self.assertEqual((root / 'crontab.before-aippt').read_text(), old)
            self.assertFalse((root / 'data').exists())

    @unittest.skipUnless(os.name == 'posix', 'real POSIX shell required')
    def test_launcher_retries_then_succeeds_without_inherited_lock(self):
        with tempfile.TemporaryDirectory(prefix='ppt boot ') as folder:
            root = Path(folder)
            (root / 'runtime/bin').mkdir(parents=True)
            (root / 'supervisord.conf').touch()
            fake = root / 'runtime/bin/python'
            fake.write_text('#!/bin/sh\n[ "$2" = start ] || exit 7\n'
                'if [ -e /proc/$$/fd/9 ]; then exit 8; fi\n'
                'if [ ! -f "$1.once" ]; then touch "$1.once"; exit 1; fi\nexit 0\n')
            fake.chmod(0o700)
            script = root / 'start.sh'
            script.write_text(service.boot_launcher(root).replace('sleep 10', 'sleep 0.01'))
            subprocess.run(['/bin/sh', '-n', str(script)], check=True)
            subprocess.run(['/bin/sh', str(script)], check=True, timeout=5)
            self.assertTrue((root / 'narrative_service.py.once').exists())
            # A second invocation must not be blocked by a leaked flock.
            subprocess.run(['/bin/sh', str(script)], check=True, timeout=5)

    @unittest.skipUnless(os.name == 'posix', 'real POSIX shell required')
    def test_cron_waits_for_missing_launcher_and_exhausts_boundedly(self):
        with tempfile.TemporaryDirectory(prefix='ppt cron ') as folder:
            launcher = Path(folder) / 'start.sh'
            args = shlex.split(service.boot_cron(launcher).split(' # ', 1)[0])[1:]
            args[-1] = args[-1].replace('sleep 10', 'sleep 0.01')
            self.assertEqual(subprocess.run(args, timeout=5).returncode, 1)
            launcher.write_text('#!/bin/sh\nexit 0\n')
            self.assertEqual(subprocess.run(args, timeout=5).returncode, 0)


if __name__ == '__main__':
    unittest.main()
