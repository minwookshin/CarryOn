"""Synthetic identifiers and coordinates only. No carrier/customer data."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/work'
ROOT.mkdir(parents=True, exist_ok=True)
TARGETS = [dict(name='42dot', latitude=37.48544722, longitude=127.03636666,
                address='서울시 강남구 남부순환로 2621', poiId='36khp37hh8gqcm5nqhe5', poiSubId='0'),
           dict(name='소프트웨어드림센터', latitude=37.41275833, longitude=127.09481944,
                address='경기도 성남시 수정구 창업로40번길 6', poiId='36khp2hdj2cfcm5nqhe3', poiSubId='0')]

def visit(id, name, round, operation, index, cold=False):
    return dict(id=id, name=name, round=round, operation=operation,
        access='남문 · 지상 출입구' if operation=='배송' else '화물 출입구 · 현장 접수',
        parking='지정 하역 구역 · 현장 표지 확인', target=TARGETS[index % 2], phase='UNKNOWN',
        revision=0, requiredLow=-20.0 if cold else None, requiredHigh=-18.0 if cold else None)

def task(id, number, v, kind, quantity=1, building='', entrance='', unit='개'):
    return dict(id=id, number=number, visitId=v, kind=kind, quantity=quantity, completed=0,
        building=building, entrance=entrance, unit=unit, sync='OPEN', revision=0, updatedAt=0, location=None)

def base(mode, cold, visits, tasks):
    return dict(schema=2, accountId='demo-parcel' if mode=='PARCEL' else 'demo-freight',
        streamId='local-'+('parcel' if mode=='PARCEL' else 'freight'), driverName='김민준' if mode=='PARCEL' else '박지호',
        mode=mode, refrigerated=cold, visits=visits, tasks=tasks, currentVisitId=visits[0]['id'],
        cursor=0, activeRevision=0, dayClosed=False, dayRevision=0, drivingEnded=False, drivingRevision=0,
        lastSyncAt=0, sourceLabel='예시 업무', seen=[], cargoHistory=[], noteRevision=0)

pv = [visit('p-zone-1', '한빛아파트', 1, '배송', 0), visit('p-zone-2', '한빛상가', 1, '배송', 1),
      visit('p-collect', '새봄 거래처', 1, '거래처 집하', 0), visit('p-handoff', '동부 터미널', 1, '집하 인계', 1),
      visit('p-round-2', '한빛아파트', 2, '배송', 0), visit('p-final', '동부 캠프', 2, '회수품 인계', 1)]
pt = [task(f'p-1-{i:03}', f'{i:03}', 'p-zone-1', 'DELIVERY', building='101동' if i<=9 else '연결 건물',
           entrance='1/2라인' if i<=5 else '3/4라인' if i<=9 else '연결 출입구') for i in range(1,13)]
pt += [task(f'p-1-{i:03}', f'{i:03}', 'p-zone-2', 'DELIVERY', building='상가동', entrance='후면 출입구') for i in range(13,69)]
pt += [task(f'p-return-{i}', f'R{i:02}', 'p-zone-1', 'RETURN', building='101동', entrance='3/4라인') for i in range(1,3)]
pt += [task('p-collection', 'C01', 'p-collect', 'COLLECTION', 4, unit='상자'), task('p-transfer', 'T01', 'p-handoff', 'HANDOFF',4,unit='상자')]
pt += [task(f'p-2-{i:03}', f'2-{i:03}', 'p-round-2', 'DELIVERY',building='101동',entrance='1/2라인') for i in range(1,3)]
pt += [task('p-return-handoff','R-T01','p-final','HANDOFF',2,unit='건')]
parcel=base('PARCEL',False,pv,pt)
freight=base('FREIGHT',True,[visit('f-load','중앙저온센터',1,'상차',0,True),visit('f-unload','미래물류센터',1,'하차',1,True),
    visit('f-container','중앙용기센터',1,'용기 회수',0),visit('f-handoff','중앙운송센터',1,'용기 인계',1)],
    [task('f-load-24','F2208-L','f-load','LOAD',24,unit='상자'),task('f-unload-24','F2208-U','f-unload','UNLOAD',24,unit='상자'),
     task('f-receipt','F2208-S','f-unload','RECEIPT',1,unit='건'),task('f-container-4','F2208-C','f-container','CONTAINER',4),
     task('f-handoff-4','F2208-T','f-handoff','HANDOFF',4)])
for name, fixture in [('parcel',parcel),('freight',freight)]:
    (ROOT/(name+'.json')).write_text(json.dumps(fixture,ensure_ascii=False,indent=2)+'\n')
print('Generated two synthetic workflow fixtures')
