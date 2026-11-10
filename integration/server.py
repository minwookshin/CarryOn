#!/usr/bin/env python3
"""Loopback-only carrier/terminal fixture. Not a production identity provider or vehicle controller."""
import argparse
import hashlib
import json
import secrets
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

HERE = Path(__file__).resolve().parent
FIXTURES = HERE.parent / 'app/src/main/assets/work'
def now(): return int(time.time()*1000)
def digest(value): return hashlib.sha256(value.encode()).hexdigest()
def compact(value): return json.dumps(value, ensure_ascii=False, separators=(',', ':'))

class ApiError(Exception):
    def __init__(self, code, status=400): self.code, self.status = code, status

class Store:
    def __init__(self, path):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS state (account TEXT PRIMARY KEY, body TEXT);
        CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, account TEXT, stream TEXT, body TEXT);
        CREATE TABLE IF NOT EXISTS grants (device TEXT PRIMARY KEY, code TEXT UNIQUE, expires INTEGER, poll INTEGER DEFAULT 0,
          interval INTEGER DEFAULT 2000, account TEXT, denied INTEGER DEFAULT 0, used INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS tokens (access TEXT PRIMARY KEY, refresh TEXT UNIQUE, family TEXT, account TEXT,
          expires INTEGER, refresh_expires INTEGER, revoked INTEGER DEFAULT 0, rotated INTEGER DEFAULT 0);
        ''')
        self.csrf = secrets.token_urlsafe(32)
        self.browser_session = secrets.token_urlsafe(32)
        self.sensor_enabled = {}
        self.requests = []
        for mode in ('parcel','freight'):
            if not self.db.execute('SELECT 1 FROM state WHERE account=?', ('demo-'+mode,)).fetchone(): self.reset(mode)

    def reset(self, mode):
        if mode not in ('parcel','freight'): raise ApiError('invalid_mode')
        data = json.loads((FIXTURES/(mode+'.json')).read_text())
        data.update(streamId=secrets.token_hex(16),sourceLabel='Mac 시험 단말', lastSyncAt=now())
        self.sensor_enabled.pop(data['accountId'],None)
        self.save(data)
        return data

    def save(self, data):
        self.db.execute('INSERT OR REPLACE INTO state VALUES (?,?)', (data['accountId'], compact(data)))
        self.db.commit()

    def state(self, account):
        row = self.db.execute('SELECT body FROM state WHERE account=?', (account,)).fetchone()
        if not row: raise ApiError('unknown_account')
        return json.loads(row['body'])

    def event(self, account, kind, **fields):
        data=self.state(account)
        rev=data['cursor']+1
        e=dict(id=secrets.token_hex(16), accountId=account,streamId=data['streamId'],revision=rev,kind=kind,entityId='',occurredAt=now(),**{})
        e.update(fields)
        if kind=='TASK':
            task=next((x for x in data['tasks'] if x['id']==e['entityId']),None)
            if not task or not isinstance(e.get('completed'),int) or not 0<=e['completed']<=task['quantity'] or e.get('sync') not in ('OPEN','LOCAL','PENDING','CONFIRMED'):
                raise ApiError('invalid_task')
            if e['sync']=='OPEN' and e['completed']!=0: raise ApiError('invalid_task')
            task.update(completed=e['completed'],sync=e['sync'],revision=rev,updatedAt=e['occurredAt'])
        elif kind=='CURRENT':
            if not any(x['id']==e['entityId'] for x in data['visits']): raise ApiError('unknown_visit')
            data.update(currentVisitId=e['entityId'],activeRevision=rev)
        elif kind=='FACILITY':
            visit=next((x for x in data['visits'] if x['id']==e['entityId']),None)
            if not visit or e.get('phase') not in ('UNKNOWN','REGISTERED','CALLED','WORKING'): raise ApiError('invalid_facility')
            visit.update(phase=e['phase'],dock=e.get('dock'),revision=rev)
            if e['phase']=='REGISTERED': visit['registeredAt']=e['occurredAt']
        elif kind=='SENSOR':
            if not data['refrigerated']: raise ApiError('no_cargo_sensor')
            data['sensor']=e['sensor']; data['sensor']['revision']=rev
            visit=next((v for v in data['visits'] if v['id']==data['currentVisitId']),{})
            data['cargoHistory']=(data.get('cargoHistory',[])+[dict(sample=dict(data['sensor']),visitId=data['currentVisitId'],
                requiredLow=visit.get('requiredLow'),requiredHigh=visit.get('requiredHigh'))])[-720:]
        elif kind in ('DAY','DRIVING'):
            if not isinstance(e.get('closed'),bool): raise ApiError('invalid_status')
            data.update({'dayClosed' if kind=='DAY' else 'drivingEnded':e['closed'],'dayRevision' if kind=='DAY' else 'drivingRevision':rev})
        elif kind=='NOTE': data.update(note=str(e.get('note',''))[:300],noteRevision=rev)
        else: raise ApiError('invalid_event')
        self.db.execute('INSERT INTO events(account,stream,body) VALUES(?,?,?)',(account,data['streamId'],compact(e)))
        seq=self.db.execute('SELECT last_insert_rowid()').fetchone()[0]
        # Revision is monotonic per entity; transport cursor is monotonic globally.
        data['cursor']=seq; data['lastSyncAt']=now()
        self.save(data)
        return data

    def issue(self, account, family=None):
        access,refresh=secrets.token_urlsafe(32),secrets.token_urlsafe(48)
        self.db.execute('INSERT INTO tokens(access,refresh,family,account,expires,refresh_expires) VALUES(?,?,?,?,?,?)',
            (digest(access),digest(refresh),family or secrets.token_hex(16),account,now()+900000,now()+86400000))
        self.db.commit()
        return dict(access_token=access,refresh_token=refresh,token_type='Bearer',expires_in=900,account_id=account)

    def device(self):
        self.requests=[t for t in self.requests if now()-t<60000]
        if len(self.requests)>=20: raise ApiError('rate_limited',429)
        self.requests.append(now())
        code=secrets.token_hex(4).upper(); device=secrets.token_urlsafe(40)
        self.db.execute('INSERT INTO grants(device,code,expires) VALUES(?,?,?)',(digest(device),code,now()+600000)); self.db.commit()
        return dict(device_code=device,user_code=code,expires_in=600,interval=2)

    def token(self, form):
        if form.get('client_id')!='pleos-work-car': raise ApiError('invalid_client')
        if form.get('grant_type')=='refresh_token':
            row=self.db.execute('SELECT * FROM tokens WHERE refresh=?',(digest(form.get('refresh_token','')),)).fetchone()
            if row and row['rotated']:
                self.db.execute('UPDATE tokens SET revoked=1 WHERE family=?',(row['family'],)); self.db.commit()
            if not row or row['revoked'] or row['rotated'] or row['refresh_expires']<now(): raise ApiError('invalid_grant')
            self.db.execute('UPDATE tokens SET revoked=1,rotated=1 WHERE refresh=?',(row['refresh'],))
            return self.issue(row['account'],row['family'])
        if form.get('grant_type')!='urn:ietf:params:oauth:grant-type:device_code': raise ApiError('unsupported_grant_type')
        row=self.db.execute('SELECT * FROM grants WHERE device=?',(digest(form.get('device_code','')),)).fetchone()
        if not row or row['expires']<now() or row['used']: raise ApiError('expired_token')
        if row['denied']: raise ApiError('access_denied')
        if row['poll'] and now()-row['poll']<row['interval']:
            self.db.execute('UPDATE grants SET interval=interval+5000,poll=? WHERE device=?',(now(),row['device'])); self.db.commit()
            raise ApiError('slow_down')
        self.db.execute('UPDATE grants SET poll=? WHERE device=?',(now(),row['device'])); self.db.commit()
        if not row['account']: raise ApiError('authorization_pending')
        self.db.execute('UPDATE grants SET used=1 WHERE device=?',(row['device'],))
        return self.issue(row['account'])

    def authorize(self, header):
        token=header.removeprefix('Bearer ') if header.startswith('Bearer ') else ''
        row=self.db.execute('SELECT * FROM tokens WHERE access=?',(digest(token),)).fetchone()
        if not row or row['revoked'] or row['expires']<now(): raise ApiError('invalid_token',401)
        return row['account']

    def sensor_tick(self, account):
        config=self.sensor_enabled.get(account)
        if config and now()-config['last']>=5000:
            config['last']=now()
            self.event(account,'SENSOR',sensor=dict(sensorId='fixture-air-1',compartment='단일 화물칸',measuredC=config['value'],
                setpointC=config.get('setpoint',-19.0),doorOpen=config['door'],observedAt=now(),maxAgeMs=15000,available=True,revision=0))

class Handler(BaseHTTPRequestHandler):
    store=None
    def log_message(self,*args): pass
    @property
    def origin(self): return f'http://127.0.0.1:{self.server.server_port}'
    def host_check(self):
        if self.headers.get('Host') not in (f'127.0.0.1:{self.server.server_port}',f'localhost:{self.server.server_port}'):
            raise ApiError('invalid_host',403)
    def send(self,data,status=200,html=False,cookie=False):
        raw=data.encode() if html else compact(data).encode()
        self.send_response(status)
        self.send_header('Content-Type','text/html; charset=utf-8' if html else 'application/json')
        self.send_header('Content-Length',str(len(raw)))
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Content-Security-Policy',"default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
        if cookie: self.send_header('Set-Cookie',f'work_fixture={self.store.browser_session}; HttpOnly; SameSite=Strict; Path=/')
        self.end_headers(); self.wfile.write(raw)
    def control_auth(self):
        if f'work_fixture={self.store.browser_session}' not in self.headers.get('Cookie',''): raise ApiError('browser_session_required',403)
        if self.command=='POST':
            if self.headers.get('Origin') not in (self.origin,self.origin.replace('127.0.0.1','localhost')): raise ApiError('invalid_origin',403)
            if not secrets.compare_digest(self.headers.get('X-CSRF-Token',''),self.store.csrf): raise ApiError('invalid_csrf',403)
    def body(self):
        size=int(self.headers.get('Content-Length','0'))
        if not 0<size<=65536: raise ApiError('invalid_body')
        raw=self.rfile.read(size).decode()
        if self.headers.get('Content-Type','').startswith('application/json'): return json.loads(raw)
        form=parse_qs(raw,keep_blank_values=True)
        if any(len(v)!=1 for v in form.values()): raise ApiError('duplicate_parameter')
        return {k:v[0] for k,v in form.items()}
    def do_GET(self): self.handle_request(False)
    def do_POST(self): self.handle_request(True)
    def handle_request(self,post):
        try:
            self.host_check()
            with self.store.lock:
                p=urlparse(self.path); args=parse_qs(p.query)
                if not post and p.path in ('/','/control'):
                    self.send((HERE/'control.html').read_text().replace('__CSRF__',self.store.csrf),html=True,cookie=True); return
                if not post and p.path=='/health': self.send({'status':'ok','fixture':True}); return
                if p.path.startswith('/control/'):
                    self.control_auth()
                    if not post and p.path=='/control/state':
                        states=[self.store.state(a) for a in ('demo-parcel','demo-freight')]
                        grants=[dict(r) for r in self.store.db.execute('SELECT code,expires FROM grants WHERE account IS NULL AND denied=0 AND used=0 AND expires>?',(now(),))]
                        self.send(dict(states=states,grants=grants)); return
                    if post:
                        form=self.body(); account=form.get('account','demo-parcel')
                        if account not in ('demo-parcel','demo-freight'): raise ApiError('unknown_account')
                        if p.path=='/control/approve':
                            r=self.store.db.execute('SELECT * FROM grants WHERE code=?',(str(form.get('code','')).upper(),)).fetchone()
                            if not r or r['expires']<now() or r['used'] or r['account']: raise ApiError('invalid_user_code')
                            self.store.db.execute('UPDATE grants SET account=?,denied=? WHERE code=?',
                                (None if form.get('deny') else account,1 if form.get('deny') else 0,r['code'])); self.store.db.commit()
                            self.send({'approved':not form.get('deny',False)}); return
                        if p.path=='/control/action': self.send(self.action(account,form)); return
                    raise ApiError('not_found',404)
                if post and p.path=='/oauth/device_authorization':
                    form=self.body()
                    if form.get('client_id')!='pleos-work-car' or form.get('scope')!='work:read cargo:read': raise ApiError('invalid_scope')
                    grant=self.store.device(); grant.update(verification_uri=self.origin+'/control',verification_uri_complete=self.origin+'/control?code='+grant['user_code'])
                    self.send(grant); return
                if post and p.path=='/oauth/token': self.send(self.store.token(self.body())); return
                if post and p.path=='/oauth/revoke':
                    token=digest(self.body().get('token',''))
                    row=self.store.db.execute('SELECT family FROM tokens WHERE refresh=? OR access=?',(token,token)).fetchone()
                    if row: self.store.db.execute('UPDATE tokens SET revoked=1 WHERE family=?',(row['family'],)); self.store.db.commit()
                    self.send({}); return
                if not post and p.path.startswith('/v1/work/'):
                    account=self.store.authorize(self.headers.get('Authorization','')); self.store.sensor_tick(account)
                    data=self.store.state(account)
                    if p.path=='/v1/work/snapshot': self.send(data); return
                    if p.path=='/v1/work/events':
                        after=int(args.get('after',['0'])[0])
                        rows=self.store.db.execute('SELECT seq,body FROM events WHERE account=? AND stream=? AND seq>? ORDER BY seq LIMIT 500',(account,data['streamId'],after)).fetchall()
                        self.send(dict(accountId=account,streamId=data['streamId'],cursor=rows[-1]['seq'] if rows else data['cursor'],events=[json.loads(r['body']) for r in rows])); return
                raise ApiError('not_found',404)
        except ApiError as e: self.send({'error':e.code},e.status)
        except (ValueError,KeyError,TypeError): self.send({'error':'invalid_request'},400)

    def action(self,account,f):
        s=self.store.state(account); action=f.get('action')
        if action=='reset': return self.store.reset(account.removeprefix('demo-'))
        if action=='current': return self.store.event(account,'CURRENT',entityId=f.get('visit'))
        if action=='task': return self.store.event(account,'TASK',entityId=f.get('id'),completed=f.get('completed'),sync=f.get('sync','CONFIRMED'))
        if action in ('partial8','deliver','return','complete','pending','sync'):
            tasks=[t for t in s['tasks'] if t['visitId']==s['currentVisitId']]
            if action in ('partial8','deliver','pending'): tasks=[t for t in tasks if t['kind']=='DELIVERY']
            if action=='partial8': tasks=tasks[:8]
            if action=='return': tasks=[t for t in tasks if t['kind']=='RETURN']
            if action=='complete': tasks=[t for t in tasks if t['kind']!='RECEIPT']
            if action=='sync': tasks=[t for t in s['tasks'] if t['sync'] in ('LOCAL','PENDING')]
            for t in tasks: s=self.store.event(account,'TASK',entityId=t['id'],completed=t['completed'] if action=='sync' else t['quantity'],sync='PENDING' if action=='pending' else 'CONFIRMED')
            return s
        if action=='receipt':
            for t in s['tasks']:
                if t['visitId']==s['currentVisitId'] and t['kind']=='RECEIPT': s=self.store.event(account,'TASK',entityId=t['id'],completed=t['quantity'],sync='CONFIRMED')
            return s
        if action=='facility':
            visit=next(v for v in s['visits'] if v['id']==s['currentVisitId'])
            dock=visit.get('dock') if f.get('phase')=='WORKING' and f.get('dock') is None else f.get('dock')
            return self.store.event(account,'FACILITY',entityId=s['currentVisitId'],phase=f.get('phase'),dock=dock)
        if action in ('day','driving'): return self.store.event(account,'DAY' if action=='day' else 'DRIVING',closed=bool(f.get('closed')))
        if action=='cold':
            s['refrigerated']=bool(f.get('enabled'))
            if not s['refrigerated']: self.store.sensor_enabled.pop(account,None)
            for v in s['visits']:
                v.update(requiredLow=-20.0 if s['refrigerated'] else None, requiredHigh=-18.0 if s['refrigerated'] else None)
            s['streamId']=secrets.token_hex(16)
            s['cursor']=self.store.db.execute('SELECT COALESCE(MAX(seq),0) FROM events').fetchone()[0]
            self.store.save(s); return s
        if action=='sensor':
            mode=f.get('mode'); last=s.get('sensor') or {}
            if mode in ('normal','deviation','door','setpoint'):
                self.store.sensor_enabled[account]=dict(value=-15.0 if mode in ('deviation','door') else -19.0,
                    setpoint=-15.0 if mode=='setpoint' else -19.0,door=mode=='door',last=0)
                self.store.sensor_tick(account)
            else:
                self.store.sensor_enabled.pop(account,None)
                if mode=='offline': self.store.event(account,'SENSOR',sensor=dict(sensorId='fixture-air-1',compartment='단일 화물칸',measuredC=last.get('measuredC'),setpointC=-19.0,doorOpen=last.get('doorOpen'),observedAt=last.get('observedAt',now()),maxAgeMs=15000,available=False,revision=0))
            return self.store.state(account)
        raise ApiError('invalid_action')

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--port',type=int,default=8797); parser.add_argument('--db',default=str(HERE/'local-state.sqlite3'))
    args=parser.parse_args(); Handler.store=Store(args.db)
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler)
    print(f'Local fictional terminal: http://127.0.0.1:{args.port}/control',flush=True)
    server.serve_forever()
if __name__=='__main__': main()
