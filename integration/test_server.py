import json
import threading
import unittest
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from http.server import ThreadingHTTPServer
from server import Handler, Store, ApiError, digest, now

class IntegrationTest(unittest.TestCase):
    def setUp(self):
        self.store=Store(':memory:')
        self.handler=type('TestHandler',(Handler,),{'store':self.store})
        self.server=ThreadingHTTPServer(('127.0.0.1',0),self.handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.origin=f'http://127.0.0.1:{self.server.server_port}'
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.store.db.close()
    def request(self,path,body=None,control=False,token=None,origin=None):
        headers={}
        if body is not None: headers['Content-Type']='application/json' if control else 'application/x-www-form-urlencoded'
        if control:
            headers.update({'Cookie':'work_fixture='+self.store.browser_session,'X-CSRF-Token':self.store.csrf,'Origin':origin or self.origin})
        if token: headers['Authorization']='Bearer '+token
        raw=(json.dumps(body) if control else urlencode(body)).encode() if body is not None else None
        try:
            with urlopen(Request(self.origin+path,data=raw,headers=headers)) as r:return r.status,json.load(r)
        except HTTPError as e:return e.code,json.load(e)
    def grant(self,account='demo-parcel'):
        status,g=self.request('/oauth/device_authorization',{'client_id':'pleos-work-car','scope':'work:read cargo:read'})
        self.assertEqual(status,200)
        status,_=self.request('/control/approve',{'code':g['user_code'],'account':account},True)
        self.assertEqual(status,200)
        status,t=self.request('/oauth/token',{'client_id':'pleos-work-car','grant_type':'urn:ietf:params:oauth:grant-type:device_code','device_code':g['device_code']})
        self.assertEqual(status,200);return g,t
    def test_real_http_approval_and_scoped_snapshot(self):
        _,t=self.grant();status,s=self.request('/v1/work/snapshot',token=t['access_token'])
        self.assertEqual(status,200);self.assertEqual(s['accountId'],'demo-parcel')
        self.assertEqual(self.request('/v1/work/snapshot')[0],401)
    def test_pending_denial_expiry_and_poll_rate(self):
        g=self.store.device();form={'client_id':'pleos-work-car','grant_type':'urn:ietf:params:oauth:grant-type:device_code','device_code':g['device_code']}
        with self.assertRaisesRegex(ApiError,''): self.store.token(form)
        with self.assertRaises(ApiError) as e:self.store.token(form)
        self.assertEqual(e.exception.code,'slow_down')
        self.store.db.execute('UPDATE grants SET denied=1 WHERE device=?',(digest(g['device_code']),))
        with self.assertRaises(ApiError) as e:self.store.token(form)
        self.assertEqual(e.exception.code,'access_denied')
        self.store.db.execute('UPDATE grants SET expires=1 WHERE device=?',(digest(g['device_code']),))
        with self.assertRaises(ApiError) as e:self.store.token(form)
        self.assertEqual(e.exception.code,'expired_token')
    def test_code_is_single_use_and_refresh_rotation_detects_reuse(self):
        g,t=self.grant()
        code,_=self.request('/oauth/token',{'client_id':'pleos-work-car','grant_type':'urn:ietf:params:oauth:grant-type:device_code','device_code':g['device_code']});self.assertEqual(code,400)
        form={'client_id':'pleos-work-car','grant_type':'refresh_token','refresh_token':t['refresh_token']}
        code,new=self.request('/oauth/token',form);self.assertEqual(code,200)
        self.assertEqual(self.request('/v1/work/snapshot',token=t['access_token'])[0],401)
        self.assertEqual(self.request('/oauth/token',form)[0],400)
        self.assertEqual(self.request('/v1/work/snapshot',token=new['access_token'])[0],401)
    def test_terminal_requires_browser_session_and_same_origin(self):
        self.assertEqual(self.request('/control/action',{'account':'demo-parcel','action':'deliver'})[0],403)
        self.assertEqual(self.request('/control/action',{'account':'demo-parcel','action':'deliver'},True,origin='https://attacker.example')[0],403)
    def test_partial_delivery_collection_handoff_resume(self):
        _,t=self.grant()
        self.request('/control/action',{'account':'demo-parcel','action':'partial8'},True)
        _,s=self.request('/control/action',{'account':'demo-parcel','action':'current','visit':'p-collect'},True)
        self.request('/control/action',{'account':'demo-parcel','action':'complete'},True)
        self.request('/control/action',{'account':'demo-parcel','action':'current','visit':'p-handoff'},True)
        self.request('/control/action',{'account':'demo-parcel','action':'complete'},True)
        _,s=self.request('/control/action',{'account':'demo-parcel','action':'current','visit':'p-zone-1'},True)
        self.assertEqual(sum(x['quantity']-x['completed'] for x in s['tasks'] if x['visitId']=='p-zone-1' and x['kind']=='DELIVERY'),4)
        self.assertFalse(s['dayClosed'])
        _,batch=self.request('/v1/work/events?after=0',token=t['access_token']);self.assertGreater(batch['cursor'],0);self.assertGreater(len(batch['events']),8)
    def test_late_result_and_second_round_are_independent(self):
        self.store.event('demo-parcel','CURRENT',entityId='p-round-2')
        s=self.store.event('demo-parcel','TASK',entityId='p-1-001',completed=1,sync='CONFIRMED')
        self.assertEqual(s['currentVisitId'],'p-round-2')
        self.assertTrue(all(t['completed']==0 for t in s['tasks'] if t['visitId']=='p-round-2'))
    def test_unload_and_receipt_are_separate_and_day_end_preserves(self):
        self.store.event('demo-freight','CURRENT',entityId='f-unload')
        s=self.store.event('demo-freight','TASK',entityId='f-unload-24',completed=24,sync='CONFIRMED')
        self.assertEqual(next(t for t in s['tasks'] if t['id']=='f-receipt')['completed'],0)
        before=s['tasks'];s=self.store.event('demo-freight','DRIVING',closed=True);self.assertEqual(before,s['tasks']);self.assertFalse(s['dayClosed'])
    def test_revocation_leaves_work_and_requires_reconnect(self):
        _,t=self.grant();before=self.store.state('demo-parcel')
        self.assertEqual(self.request('/oauth/revoke',{'token':t['refresh_token']})[0],200)
        self.assertEqual(self.request('/v1/work/snapshot',token=t['access_token'])[0],401)
        self.assertEqual(before,self.store.state('demo-parcel'))
    def test_expired_token_cannot_read_other_fixture(self):
        _,t=self.grant('demo-freight');self.store.db.execute('UPDATE tokens SET expires=1')
        self.assertEqual(self.request('/v1/work/snapshot',token=t['access_token'])[0],401)
    def test_no_plain_tokens_are_stored(self):
        _,t=self.grant();row=self.store.db.execute('SELECT * FROM tokens').fetchone()
        self.assertNotIn(t['access_token'],list(row));self.assertNotIn(t['refresh_token'],list(row))
    def test_cargo_history_keeps_old_requirement_and_survives_reset_safely(self):
        self.request('/control/action',{'account':'demo-freight','action':'sensor','mode':'door'},True)
        s=self.store.state('demo-freight')
        record=s['cargoHistory'][-1]
        self.assertEqual(record['requiredHigh'],-18.0)
        self.assertEqual(record['sample']['measuredC'],-15.0)
        self.assertTrue(record['sample']['doorOpen'])
        self.assertEqual(record['visitId'],'f-load')
        self.store.reset('freight')
        self.assertNotIn('demo-freight',self.store.sensor_enabled)
        self.assertEqual(self.store.state('demo-freight')['cargoHistory'],[])
    def test_cold_option_changes_stream_without_erasing_work(self):
        self.store.event('demo-parcel','TASK',entityId='p-1-001',completed=1,sync='CONFIRMED')
        before=self.store.state('demo-parcel')
        _,s=self.request('/control/action',{'account':'demo-parcel','action':'cold','enabled':True},True)
        self.assertEqual(s['tasks'],before['tasks']);self.assertNotEqual(s['streamId'],before['streamId'])
        self.request('/control/action',{'account':'demo-parcel','action':'sensor','mode':'normal'},True)
        self.request('/control/action',{'account':'demo-parcel','action':'cold','enabled':False},True)
        self.assertNotIn('demo-parcel',self.store.sensor_enabled)
    def test_starting_work_does_not_erase_assigned_dock(self):
        self.request('/control/action',{'account':'demo-freight','action':'facility','phase':'REGISTERED'},True)
        self.request('/control/action',{'account':'demo-freight','action':'facility','phase':'CALLED','dock':'03번 도크'},True)
        _,s=self.request('/control/action',{'account':'demo-freight','action':'facility','phase':'WORKING','dock':None},True)
        v=next(v for v in s['visits'] if v['id']==s['currentVisitId'])
        self.assertEqual(v['dock'],'03번 도크');self.assertGreater(v['registeredAt'],0)

if __name__=='__main__':unittest.main()
