import json, math
from pathlib import Path
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
from PIL import Image

MODEL=Path('ergoapp/src/main/assets/pose_landmarker_lite.task')
DATA=Path('testdata/suite')
OUT=Path('posture_dataset_output')
OUT.mkdir(exist_ok=True)
PAIRS=[
 ('physiosunit_adult','physiosunit.jpg','right','left'),
 ('student_illustration','student_illustration.jpg','left','right'),
 ('rpb_student','rpb_student.jpg','right','left'),
 ('current_student','current_student.png','right','left'),
 ('sweclockers_adult','sweclockers.jpg','left','right'),
]

def mid(l,a,b): return ((l[a].x+l[b].x)/2,(l[a].y+l[b].y)/2)
def crop(src,side,dst):
 im=Image.open(src).convert('RGB'); w,h=im.size
 (im.crop((0,0,w//2,h)) if side=='left' else im.crop((w//2,0,w,h))).save(dst,quality=94)
def detect(lm,path):
 r=lm.detect(mp.Image.create_from_file(str(path)))
 return r.pose_landmarks[0] if r.pose_landmarks else None
def measure(l):
 nose=(l[0].x,l[0].y); ear=mid(l,7,8); sh=mid(l,11,12); hip=mid(l,23,24)
 dx=sh[0]-hip[0]; dy=sh[1]-hip[1]; torso=math.hypot(dx,dy)
 facing=1.0 if nose[0]>=ear[0] else -1.0
 head=((ear[0]-sh[0])*facing)/torso
 forward=dx*facing
 vertical=-dy
 signed=math.degrees(math.atan2(forward,vertical))
 absang=abs(signed)
 width=max(abs(l[11].x-l[12].x),abs(l[23].x-l[24].x))
 return {'head':head,'trunk_signed':signed,'trunk_abs':absang,'profile_ratio':width/torso}
def pred(raw,base,h=.07,t=12):
 hd=max(0,raw['head']-base['head']); td=max(0,raw['trunk_signed']-base['trunk_signed'])
 return {'head_delta_pct':hd*100,'trunk_forward_delta_deg':td,'bad':hd>=h or td>=t}

opts=vision.PoseLandmarkerOptions(base_options=python.BaseOptions(model_asset_path=str(MODEL)),running_mode=vision.RunningMode.IMAGE,num_poses=1,min_pose_detection_confidence=.30,min_pose_presence_confidence=.30,min_tracking_confidence=.30)
rows=[]
with vision.PoseLandmarker.create_from_options(opts) as lm:
 for pid,file,goodside,badside in PAIRS:
  src=DATA/file
  if not src.exists(): continue
  gp=OUT/f'{pid}_signed_good.jpg'; bp=OUT/f'{pid}_signed_bad.jpg'; crop(src,goodside,gp); crop(src,badside,bp)
  gl=detect(lm,gp); bl=detect(lm,bp)
  if gl is None or bl is None: continue
  g=measure(gl); b=measure(bl); ge=pred(g,g); be=pred(b,g)
  rows.append({'id':pid,'good':g,'bad':b,'good_eval':ge,'bad_eval':be})
valid_side=[r for r in rows if r['good']['profile_ratio']<.50 and r['bad']['profile_ratio']<.50]
summary={'all_pairs':rows,'valid_side_pairs':[r['id'] for r in valid_side], 'valid_side_accuracy':None}
if valid_side:
 correct=sum((not r['good_eval']['bad'])+r['bad_eval']['bad'] for r in valid_side)
 summary['valid_side_accuracy']={'correct':correct,'total':2*len(valid_side),'accuracy':correct/(2*len(valid_side)), 'false_positive':sum(r['good_eval']['bad'] for r in valid_side), 'false_negative':sum(not r['bad_eval']['bad'] for r in valid_side)}
Path(OUT/'signed_probe.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
print(json.dumps(summary,indent=2))
