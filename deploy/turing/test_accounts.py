import concurrent.futures,sqlite3,tempfile,unittest
from pathlib import Path
from accounts import Accounts,password_matches
class AccountsTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.store=Accounts(Path(self.temp.name)/'accounts.sqlite','yangbin','original-password-123')
 def tearDown(self):self.temp.cleanup()
 def register(self,name='alice'):
  return self.store.register(name,'secure-password-123',self.store.create_invite(1,7)['code'])
 def test_bootstrap_keeps_existing_admin_password(self):
  Accounts(self.store.path,'yangbin','replacement-password')
  token,user=self.store.login('YANGBIN','original-password-123');self.assertEqual(user['id'],'admin');self.assertIsNotNone(self.store.session(token))
  with self.store.db() as db:stored=db.execute("SELECT password FROM users WHERE id='admin'").fetchone()[0]
  self.assertNotIn('original-password',stored);self.assertTrue(password_matches('original-password-123',stored))
 def test_registration_requires_invite_and_cannot_choose_admin_role(self):
  with self.assertRaises(ValueError):self.store.register('alice','secure-password-123','invalid-code-123')
  token,user=self.register();self.assertEqual(user['role'],'user');self.assertNotEqual(user['id'],'admin')
 def test_invite_is_consumed_once_even_concurrently(self):
  code=self.store.create_invite(1,7)['code']
  def attempt(name):
   try:self.store.register(name,'secure-password-123',code);return True
   except ValueError:return False
  with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:results=list(executor.map(attempt,['alice','bob']))
  self.assertEqual(sum(results),1)
 def test_duplicate_username_does_not_consume_invite(self):
  self.register();invite=self.store.create_invite(1,7)
  with self.assertRaises(ValueError):self.store.register('ALICE','secure-password-123',invite['code'])
  self.store.register('bob','secure-password-123',invite['code'])
 def test_expired_and_revoked_invites_are_rejected(self):
  invite=self.store.create_invite(1,7);self.store.revoke(invite['id'])
  with self.assertRaises(ValueError):self.store.register('alice','secure-password-123',invite['code'])
  invite=self.store.create_invite(1,7)
  with self.store.db() as db:db.execute('UPDATE invites SET expires=0 WHERE id=?',(invite['id'],))
  with self.assertRaises(ValueError):self.store.register('alice','secure-password-123',invite['code'])
 def test_logout_and_expiration_revoke_sessions(self):
  token,_=self.register();self.store.logout(token);self.assertIsNone(self.store.session(token))
  token,_=self.store.login('alice','secure-password-123')
  with self.store.db() as db:db.execute('UPDATE sessions SET expires=0')
  self.assertIsNone(self.store.session(token))
 def test_password_change_revokes_all_sessions(self):
  token,user=self.register();token2,_=self.store.login('alice','secure-password-123')
  self.store.change_password(user['id'],'secure-password-123','new-password-456')
  self.assertIsNone(self.store.session(token));self.assertIsNone(self.store.session(token2))
  with self.assertRaises(ValueError):self.store.login('alice','secure-password-123')
  self.assertIsNotNone(self.store.login('alice','new-password-456'))
 def test_disabling_account_revokes_access_and_preserves_account(self):
  token,user=self.register();self.store.set_active(user['id'],False);self.assertIsNone(self.store.session(token))
  with self.assertRaises(ValueError):self.store.login('alice','secure-password-123')
  with self.assertRaises(ValueError):self.store.set_active('admin',False)
  self.store.set_active(user['id'],True);self.assertIsNotNone(self.store.login('alice','secure-password-123'))
 def test_invalid_usernames_and_weak_passwords_are_rejected(self):
  for name in ['../admin','a','名字','user name']:
   with self.assertRaises(ValueError):self.store.register(name,'secure-password-123','invalid-invite')
  with self.assertRaises(ValueError):self.store.register('alice','short','invalid-invite')
if __name__=='__main__':unittest.main()
