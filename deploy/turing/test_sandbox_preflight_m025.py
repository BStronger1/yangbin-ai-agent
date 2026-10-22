import contextlib
import io
import json
import unittest
from unittest.mock import patch
import sandbox_preflight_m025 as probe

class SandboxPreflightTests(unittest.TestCase):
    def execute(self, modern, result=None, exercise=False):
        output=io.StringIO()
        args=['--bwrap',str(__import__('pathlib').Path(__file__).resolve())]
        if exercise: args+=['--exercise','--node','/node','--runtime','/runtime']
        with patch.object(probe,'inventory',return_value={'modern_bwrap_flags_present':modern}), patch.object(probe,'exercise',return_value=result) as mocked, contextlib.redirect_stdout(output):
            code=probe.main(args)
        return code,json.loads(output.getvalue()),mocked

    def test_inventory_does_not_run_acceptance(self):
        code,report,mocked=self.execute(True)
        self.assertEqual(code,0)
        mocked.assert_not_called()
        self.assertFalse(report['terminal_enablement_approved'])

    def test_old_backend_never_executes_commands(self):
        code,report,mocked=self.execute(False,exercise=True)
        self.assertEqual(code,2)
        mocked.assert_not_called()
        self.assertFalse(report['exercised'])

    def test_runtime_failure_is_not_accepted(self):
        code,_,_=self.execute(True,{'passed':False},True)
        self.assertEqual(code,2)

    def test_success_does_not_approve_terminal_enablement(self):
        code,report,_=self.execute(True,{'passed':True},True)
        self.assertEqual(code,0)
        self.assertFalse(report['terminal_enablement_approved'])

    def test_relative_executable_is_rejected(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            probe.main(['--bwrap','bwrap'])

if __name__=='__main__': unittest.main()
