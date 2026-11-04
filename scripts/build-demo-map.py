#!/usr/bin/env python3
"""Build a small offline OSM extract and illustrative road paths. Not a driving router.

Input: OSM API XML downloaded once for 127.088,37.408,127.100,37.417.
The extracted database remains ODbL; see design/map/README.md.
"""
from pathlib import Path
import sys, json, math, heapq, xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
data = ET.parse(sys.argv[1]).getroot()
nodes = {n.attrib['id']: (float(n.attrib['lon']), float(n.attrib['lat'])) for n in data.findall('node')}
def distance(a, b):
    return math.hypot((a[0]-b[0])*88400, (a[1]-b[1])*111200)
graph, features = {}, []
for way in data.findall('way'):
    tags = {t.attrib['k']:t.attrib['v'] for t in way.findall('tag')}
    ids = [n.attrib['ref'] for n in way.findall('nd')]
    if any(n not in nodes for n in ids): continue
    points = [nodes[n] for n in ids]
    h = tags.get('highway')
    kind = ('road' if h else 'building' if 'building' in tags else
            'park' if tags.get('landuse') in ['forest','grass','meadow'] or tags.get('leisure') == 'park' else None)
    if kind:
        features.append(dict(id=way.attrib['id'], kind=kind, roadType=h or '', name=tags.get('name',''), points=points))
    if h not in ['primary','secondary','tertiary','residential','unclassified','service','tertiary_link','primary_link']: continue
    if tags.get('access') in ['private','no']: continue
    for a,b in zip(ids,ids[1:]):
        d = distance(nodes[a],nodes[b])
        if tags.get('oneway') != '-1': graph.setdefault(a,[]).append((b,d))
        if tags.get('oneway') not in ['yes','1','true']: graph.setdefault(b,[]).append((a,d))
def nearest(point): return min(graph, key=lambda n: distance(point,nodes[n]))
anchors = [nearest(p) for p in [(127.0901,37.4101),(127.0942,37.4128),(127.0982,37.4125),(127.0948,37.4153)]]
def route(start,end):
    queue=[(0,start,[])]; seen=set()
    while queue:
        cost,n,path=heapq.heappop(queue)
        if n in seen: continue
        if n==end: return [nodes[v] for v in path+[n]]
        seen.add(n)
        for dest,dist in graph.get(n,[]): heapq.heappush(queue,(cost+dist,dest,path+[n]))
    raise ValueError('Unconnected demo anchors')
routes={f'{a}-{b}':route(anchors[a],anchors[b]) for a,b in [(0,1),(1,2),(2,3),(3,0),(2,0)]}
out = dict(attribution='© OpenStreetMap contributors', license='ODbL 1.0',
    source='https://api.openstreetmap.org/api/0.6/map?bbox=127.088,37.408,127.100,37.417',
    bounds=[127.088,37.408,127.100,37.417], features=features, routes=routes)
dest=root/'app/src/main/res/raw/pangyo_map.json';dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text(json.dumps(out,ensure_ascii=False,separators=(',',':'))+'\n')
print(len(features),'features;',len(routes),'connected paths;',dest.stat().st_size,'bytes')
