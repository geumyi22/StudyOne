"""Reproducible synthetic golden corpus; a pinned digest prevents silently redefining expected output."""
import json, random, copy, sys
from datetime import date,timedelta
from pathlib import Path
root=Path(__file__).resolve().parents[2];sys.path.insert(0,str(root))
from server.studyone_study_schedule import preview
from server.studyone_sync import ApiProblem
cases=[]
def add(name,p):
    try:
        r=preview(p)
        expect={k:r[k] for k in ['totalPapers','workDays','dailyCap','days']}
    except ApiProblem: expect=None
    cases.append(dict(name=name,plan=p,expected=expect))
def plan(start='2026-11-13', end='2026-11-30', cap=2, total=3, done=0, label='단원',title='시험지', skips=None):
    return dict(startDate=start,endDate=end,maxPerDay=cap,firstReview='학습지',skipDates=skips or [],lanes=[dict(label=label,items=[dict(title=title,total=total,completed=done)])])
for a,b,n in [('0001-01-01','0001-01-03',3),('9999-12-31','9999-12-31',1),('1582-10-03','1582-10-18',16),('1900-02-27','1900-03-02',4),('2000-02-27','2000-03-02',5),('2028-02-28','2028-03-01',3)]: add('calendar_'+a,plan(a,b,1,n))
add('all_done_all_days_skipped',plan('2026-11-13','2026-11-13',done=3,skips=['2026-11-13']))
add('rounds_above_20',plan(cap=12,total=60,done=19))
add('unicode_scalar_80',plan(label='🔬'*80,title='수학 e\u0301 ①'))
add('quotes_in_title',plan(title='"개념" \\ 응용'))
for label in ['x\ty','x\x00y','x\u200by','x\u2028y','x\u00a0y','x\u202ey',' x','x ','','🔬'*81]:
    add('invalid_label_'+str(len(cases)),plan(label=label))
for key,val in [('startDate','0000-01-01'),('startDate','2026-02-30'),('startDate','2026-1-01'),('endDate','2026-11-12'),('maxPerDay',0),('maxPerDay',13),('firstReview','말하기'),('skipDates',['2026-11-14','2026-11-14']),('skipDates',['2026-12-01'])]:
    p=plan();p[key]=val;add('invalid_'+key+'_'+str(len(cases)),p)
add('over_capacity',plan('2026-11-13','2026-11-13',1,2))
p=plan();p['lanes'].append(copy.deepcopy(p['lanes'][0]));add('duplicate_lane',p)
p=plan();p['lanes'][0]['items']*=2;add('duplicate_item',p)
p=plan(done=4);add('completed_above_total',p)
p=plan();p['lanes']=[];add('no_lanes',p)
rng=random.Random(20261113)
for index in range(80):
    start=date(2026,11,13)+timedelta(days=rng.randrange(400));span=rng.randrange(1,120)
    p=plan(start.isoformat(),(start+timedelta(days=span-1)).isoformat(),rng.randrange(1,13))
    p['firstReview']=rng.choice(['교재','학습지'])
    p['skipDates']=[(start+timedelta(days=d)).isoformat() for d in range(span) if rng.random()<.13]
    p['lanes']=[]
    for lane in range(rng.randrange(1,5)):
        items=[]
        for paper in range(rng.randrange(1,5)):
            total=rng.randrange(1,15);items.append(dict(title=f'시험지 {paper}',total=total,completed=rng.randrange(total+1)))
        p['lanes'].append(dict(label=f'단원 {lane}',items=items))
    add(f'synthetic_{index:02}',p)

import hashlib
for case in cases:
    x=case['expected']
    if x is None: continue
    text=f"{x['totalPapers']}|{x['workDays']}|{x['dailyCap']}\n"
    for day in x['days']:
        text+=f"{day['date']}|{day['review']}\n"
        for item in day['papers']: text+=item['lane']+'\t'+item['title']+'\n'
    case['expected']={k:x[k] for k in ['totalPapers','workDays','dailyCap']}
    case['expected'].update(dayCount=len(x['days']),sha256=hashlib.sha256(text.encode()).hexdigest())
digest=hashlib.sha256(json.dumps(cases,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
EXPECTED_DIGEST = '7f6a1c10e788bce705d5b2eaf9cb8397d2bcd5ec5c67382fd56326b03c7cc11f'
if digest != EXPECTED_DIGEST:
    raise AssertionError('Golden contract changed: '+digest)
if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: python schedule_cases.py OUTPUT.json')
    Path(sys.argv[1]).write_text(json.dumps({'schema':1,'cases':cases},ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
    print(f'Generated {len(cases)} synthetic cases; pinned SHA-256 {digest}')
