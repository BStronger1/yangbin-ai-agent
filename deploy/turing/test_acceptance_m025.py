"""Offline safety checks; no network or model calls."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock,patch
from types import SimpleNamespace
import contextlib,io,sys
from acceptance_m025 import Acceptance,CheckFailure,exact_write_approval,install_test_limits,reserve_run,usage_difference,operator_accounts

class AcceptanceSafetyTest(unittest.TestCase):
    def test_run_marker_prevents_replay(self):
        with tempfile.TemporaryDirectory() as folder:
            reserve_run(folder)
            with self.assertRaises(FileExistsError):reserve_run(folder)
            self.assertEqual(json.loads((Path(folder)/'STARTED.json').read_text())['policy'],'never replay')
    def test_limits_only_new_owner_and_never_overwrite(self):
        owner='a'*32
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(CheckFailure):install_test_limits(root,owner,[])
            install_test_limits(root,owner,[owner])
            limits=json.loads((Path(root)/'tmp/governance'/owner/'limits.json').read_text())
            self.assertEqual(limits['dailyRequests'],8);self.assertEqual(limits['outputTokens'],512)
            with self.assertRaises(CheckFailure):install_test_limits(root,owner,[owner])
            with self.assertRaises(CheckFailure):install_test_limits(root,'../escape',['../escape'])
    def test_approval_is_exact_not_model_directed(self):
        e={'type':'approval_required','toolName':'write','arguments':{'path':'accepted.txt','content':'ok\n'}}
        self.assertTrue(exact_write_approval(e,'accepted.txt','ok\n'))
        self.assertFalse(exact_write_approval(e,'accepted.txt','other\n'))
        self.assertFalse(exact_write_approval({**e,'toolName':'bash'},'accepted.txt','ok\n'))
        e['arguments']['extra']='unexpected';self.assertFalse(exact_write_approval(e,'accepted.txt','ok\n'))
    def test_usage_delta_and_unknown_cost(self):
        before={'day':'2026-10-21','requests':1,'knownTokens':30,'unknownUsageRequests':0,'budgetChargedTokens':100}
        after={**before,'requests':4,'knownTokens':90,'budgetChargedTokens':400}
        self.assertEqual(usage_difference(before,after),{'requests':3,'knownTokens':60,'unknownUsageRequests':0,'budgetChargedTokens':300,'costStatus':'UNKNOWN'})
        with self.assertRaises(CheckFailure):usage_difference(before,{**after,'day':'2026-10-22'})
        with self.assertRaises(CheckFailure):usage_difference(before,{**after,'requests':0})
    def run_failure(self,stage):
        with tempfile.TemporaryDirectory() as folder:
            args=SimpleNamespace(root=folder,evidence=str(Path(folder)/'evidence'),browser='fake',dns_address=None,base='https://example.invalid')
            runner=Acceptance(args);browser=Mock();runtime=Mock()
            runtime.chromium.launch.return_value=browser
            manager=Mock();manager.__enter__=Mock(return_value=runtime);manager.__exit__=Mock(return_value=False)
            modules={'playwright':Mock(),'playwright.sync_api':SimpleNamespace(sync_playwright=lambda:manager)}
            if stage=='import':modules['playwright.sync_api']=None
            elif stage=='launch':runtime.chromium.launch.side_effect=RuntimeError('sensitive input must never be saved')
            else:runner.accounts=Mock(side_effect=RuntimeError('sensitive input must never be saved'))
            runner.free_checks=Mock();runner.chat=Mock();runner.coding=Mock()
            with patch.dict(sys.modules,modules),contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(runner.run(),1)
            report=(Path(args.evidence)/'report.json').read_text()
            self.assertEqual(json.loads(report)['status'],'FAILED_NO_REPLAY')
            self.assertNotIn('sensitive input',report)
            self.assertTrue((Path(args.evidence)/'STARTED.json').exists())
            runner.chat.assert_not_called();runner.coding.assert_not_called()
            if stage=='accounts':browser.close.assert_called_once()
    def test_import_failure_saved_without_sensitive_text(self):self.run_failure('import')
    def test_launch_failure_saved_without_sensitive_text(self):self.run_failure('launch')
    def test_accounts_failure_saved_and_browser_closed(self):self.run_failure('accounts')
    def test_operator_provision_requires_existing_admin_without_bootstrap(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(CheckFailure):operator_accounts(folder)
            self.assertFalse((Path(folder)/'tmp/auth/accounts.sqlite').exists())
            db=Path(folder)/'tmp/auth/accounts.sqlite';db.parent.mkdir(parents=True)
            import sqlite3
            with contextlib.closing(sqlite3.connect(db)) as connection:
                connection.execute('CREATE TABLE users(id TEXT,role TEXT)')
                connection.execute("INSERT INTO users VALUES('other','user')");connection.commit()
            with self.assertRaises(CheckFailure):operator_accounts(folder)
            with contextlib.closing(sqlite3.connect(db)) as connection:
                self.assertEqual(connection.execute('SELECT * FROM users').fetchall(),[('other','user')])
    def test_operator_cleanup_only_generated_users_and_admin_unchanged(self):
        from accounts import Accounts
        with tempfile.TemporaryDirectory() as folder:
            original=Accounts(Path(folder)/'tmp/auth/accounts.sqlite','operator','test-only-admin-password')
            existing_invite=original.create_invite(1,1)
            _,existing=original.register('existing_user','test-only-existing-password',existing_invite['code'])
            with original.db() as db:before=[tuple(row) for row in db.execute('SELECT * FROM users ORDER BY id')]
            # No secrets.json exists. Operator mode must not read one.
            provisioned=operator_accounts(folder);invite=provisioned.create_invite(2,1);created=[]
            for name in ('m025_generated_a','m025_generated_b'):
                _,user=provisioned.register(name,'test-only-generated-password',invite['code']);created.append(user['id'])
                _,login=provisioned.login(name,'test-only-generated-password');self.assertEqual(login['id'],user['id'])
            args=SimpleNamespace(root=folder,evidence=str(Path(folder)/'evidence'))
            runner=Acceptance(args);runner.out.mkdir();runner.ops_accounts=provisioned
            runner.report['createdOwners']=created;runner.invite_id=invite['id'];runner.cleanup()
            with original.db() as db:
                after=[tuple(row) for row in db.execute('SELECT * FROM users ORDER BY id') if row['id'] not in created]
                self.assertEqual(after,before)
                self.assertEqual([row[0] for row in db.execute('SELECT active FROM users WHERE id IN (?,?)',created)],[0,0])
                self.assertEqual(db.execute('SELECT revoked FROM invites WHERE id=?',(invite['id'],)).fetchone()[0],1)
                self.assertEqual(db.execute('SELECT revoked FROM invites WHERE id=?',(existing_invite['id'],)).fetchone()[0],0)
                self.assertEqual(db.execute('SELECT COUNT(*) FROM sessions WHERE user_id IN (?,?)',created).fetchone()[0],0)
            self.assertTrue(all(item['ok'] for item in runner.report['cleanup']))
if __name__=='__main__':unittest.main()
