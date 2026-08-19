import json
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision

MODEL = Path('ergoapp/src/main/assets/pose_landmarker_lite.task')
DATA = Path('testdata/suite')
OUT = Path('posture_v32_output')
OUT.mkdir(parents=True, exist_ok=True)

# Four true side-view comparison pairs + one deliberately front-ish pair used as a gate-negative test.
PAIRS = [
    {'id':'physiosunit_adult','file':'physiosunit.jpg','good':'right','bad':'left','side_expected':True},
    {'id':'student_illustration','file':'student_illustration.jpg','good':'left','bad':'right','side_expected':True},
    {'id':'rpb_student','file':'rpb_student.jpg','good':'right','bad':'left','side_expected':True},
    {'id':'current_student','file':'current_student.png','good':'right','bad':'left','side_expected':False},
    {'id':'sweclockers_adult','file':'sweclockers.jpg','good':'left','bad':'right','side_expected':True},
]

HEAD_BAD = 0.07
TRUNK_BAD_DEG = 12.0
PROFILE_GATE = 0.50


def mid(l,a,b): return ((l[a].x+l[b].x)/2.0,(l[a].y+l[b].y)/2.0)
def crop(src,side,dst):
    im=Image.open(src).convert('RGB'); w,h=im.size
    (im.crop((0,0,w//2,h)) if side=='left' else im.crop((w//2,0,w,h))).save(dst,quality=94)
def detect(lm,path):
    r=lm.detect(mp.Image.create_from_file(str(path)))
    return r.pose_landmarks[0] if r.pose_landmarks else None

def measure(l):
    nose=(l[0].x,l[0].y); ear=mid(l,7,8); sh=mid(l,11,12); hip=mid(l,23,24)
    dx=sh[0]-hip[0]; dy=sh[1]-hip[1]; torso=math.hypot(dx,dy)
    if torso<1e-5: raise RuntimeError('torso too small')
    facing=1.0 if nose[0]>=ear[0] else -1.0
    head=((ear[0]-sh[0])*facing)/torso
    signed=math.degrees(math.atan2(dx*facing,-dy))
    width=max(abs(l[11].x-l[12].x),abs(l[23].x-l[24].x))
    profile=width/torso
    return {'head':head,'trunk_signed_deg':signed,'trunk_abs_deg':abs(signed),'profile_ratio':profile,'side':profile<PROFILE_GATE,'pts':{'ear':ear,'shoulder':sh,'hip':hip}}

def evaluate(raw,base,h=HEAD_BAD,t=TRUNK_BAD_DEG):
    hd=max(0.0,raw['head']-base['head'])
    td=max(0.0,raw['trunk_signed_deg']-base['trunk_signed_deg'])
    return {'head_delta_pct':hd*100.0,'trunk_forward_delta_deg':td,'bad':hd>=h or td>=t,'severe':hd>=0.12 or td>=22.0}

def annotate(path,raw,ev,title,out):
    im=Image.open(path).convert('RGB'); d=ImageDraw.Draw(im); w,h=im.size
    pts={k:(int(v[0]*w),int(v[1]*h)) for k,v in raw['pts'].items()}
    d.line([pts['ear'],pts['shoulder'],pts['hip']],fill='white',width=max(3,w//120))
    for p in pts.values():
        rr=max(4,w//90); d.ellipse((p[0]-rr,p[1]-rr,p[0]+rr,p[1]+rr),outline='white',width=2)
    text=f"{title}\nhead +{ev['head_delta_pct']:.1f}% | trunk forward +{ev['trunk_forward_delta_deg']:.1f} deg\ncurrent signed {raw['trunk_signed_deg']:+.1f} deg | profile {raw['profile_ratio']:.2f}\nbad={ev['bad']} side={raw['side']}"
    d.rectangle((0,0,w,min(110,h//3)),fill='black'); d.multiline_text((7,7),text,fill='white',spacing=2)
    im.save(out,quality=92); return im

def grid(rows):
    result=[]
    for h in [0.05,0.07,0.09,0.12]:
        for t in [8,10,12,15,18]:
            correct=fp=fn=0
            for r in rows:
                for label in ['good','bad']:
                    pred=evaluate(r[label],r['good'],h,t)['bad']; truth=label=='bad'
                    correct+=int(pred==truth); fp+=int(pred and not truth); fn+=int((not pred) and truth)
            result.append({'head_threshold_pct':h*100,'trunk_threshold_deg':t,'correct':correct,'total':2*len(rows),'fp':fp,'fn':fn})
    result.sort(key=lambda x:(-x['correct'],x['fp'],x['fn'],-x['head_threshold_pct'],-x['trunk_threshold_deg']))
    return result[:10]

opts=vision.PoseLandmarkerOptions(base_options=python.BaseOptions(model_asset_path=str(MODEL)),running_mode=vision.RunningMode.IMAGE,num_poses=1,min_pose_detection_confidence=.30,min_pose_presence_confidence=.30,min_tracking_confidence=.30)
rows=[]; skipped=[]; gate_checks=[]
with vision.PoseLandmarker.create_from_options(opts) as lm:
    for p in PAIRS:
        src=DATA/p['file']
        if not src.exists(): skipped.append({'id':p['id'],'reason':'missing'}); continue
        gp=OUT/f"{p['id']}_good.jpg"; bp=OUT/f"{p['id']}_bad.jpg"; crop(src,p['good'],gp); crop(src,p['bad'],bp)
        gl=detect(lm,gp); bl=detect(lm,bp)
        if gl is None or bl is None: skipped.append({'id':p['id'],'reason':'pose_missing'}); continue
        g=measure(gl); b=measure(bl); ge=evaluate(g,g); be=evaluate(b,g)
        if p['side_expected']:
            rows.append({'id':p['id'],'good':g,'bad':b,'good_eval':ge,'bad_eval':be})
        else:
            gate_checks.append({'id':p['id'],'good_side':g['side'],'bad_side':b['side'],'passed':(not g['side'] and not b['side'])})
        gi=annotate(gp,g,ge,p['id']+' GOOD',OUT/f"{p['id']}_good_annotated.jpg")
        bi=annotate(bp,b,be,p['id']+' BAD',OUT/f"{p['id']}_bad_annotated.jpg")
        can=Image.new('RGB',(gi.width+bi.width,max(gi.height,bi.height)),'white'); can.paste(gi,(0,0)); can.paste(bi,(gi.width,0)); can.thumbnail((1400,700)); can.save(OUT/f"{p['id']}_comparison.jpg",quality=90)

correct=fp=fn=0; side_gate_failures=[]
for r in rows:
    if not r['good']['side'] or not r['bad']['side']: side_gate_failures.append(r['id'])
    correct+=int(not r['good_eval']['bad'])+int(r['bad_eval']['bad'])
    fp+=int(r['good_eval']['bad']); fn+=int(not r['bad_eval']['bad'])

classification_total=2*len(rows)
gate_pass=sum(int(g['passed']) for g in gate_checks)
payload={
    'engine':'V3.2 personalized signed-forward trunk + normalized head-forward',
    'thresholds':{'head_delta_pct':7,'trunk_forward_delta_deg':12,'profile_ratio_gate':0.50},
    'side_classification':{'pairs':len(rows),'poses':classification_total,'correct':correct,'accuracy':correct/classification_total if classification_total else 0,'fp':fp,'fn':fn,'side_gate_failures':side_gate_failures},
    'non_side_gate':{'pairs':len(gate_checks),'passed_pairs':gate_pass,'checks':gate_checks},
    'functional_checks':{'passed':correct+2*gate_pass,'total':classification_total+2*len(gate_checks)},
    'pairs':rows,
    'skipped':skipped,
    'threshold_grid_top':grid(rows),
}
(OUT/'v32_results.json').write_text(json.dumps(payload,indent=2),encoding='utf-8')
print(json.dumps(payload,indent=2))

if skipped:
    print('FAIL: expected suite images/poses missing',file=sys.stderr); sys.exit(2)
if side_gate_failures:
    print('FAIL: true side-view pair rejected by side gate',file=sys.stderr); sys.exit(3)
if correct != classification_total:
    print('FAIL: V3.2 side-view classification regression',file=sys.stderr); sys.exit(4)
if gate_pass != len(gate_checks):
    print('FAIL: front-ish negative pair was not rejected',file=sys.stderr); sys.exit(5)
