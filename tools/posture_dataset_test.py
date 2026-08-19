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
OUT = Path('posture_dataset_output')
OUT.mkdir(parents=True, exist_ok=True)

PAIRS = [
    {'id': 'physiosunit_adult', 'file': 'physiosunit.jpg', 'good': 'right', 'bad': 'left', 'kind': 'adult_real'},
    {'id': 'student_illustration', 'file': 'student_illustration.jpg', 'good': 'left', 'bad': 'right', 'kind': 'student_illustration'},
    {'id': 'rpb_student', 'file': 'rpb_student.jpg', 'good': 'right', 'bad': 'left', 'kind': 'student_real'},
    {'id': 'current_student', 'file': 'current_student.png', 'good': 'right', 'bad': 'left', 'kind': 'student_real'},
    {'id': 'sweclockers_adult', 'file': 'sweclockers.jpg', 'good': 'left', 'bad': 'right', 'kind': 'adult_real'},
]


def midpoint(lms, a, b):
    return ((lms[a].x + lms[b].x) / 2.0, (lms[a].y + lms[b].y) / 2.0)


def norm(v):
    return math.hypot(v[0], v[1])


def angle_between(a, b):
    den = norm(a) * norm(b)
    if den <= 1e-8:
        return 0.0
    c = max(-1.0, min(1.0, (a[0] * b[0] + a[1] * b[1]) / den))
    return math.degrees(math.acos(c))


def measure(lms):
    nose = (lms[0].x, lms[0].y)
    ear = midpoint(lms, 7, 8)
    shoulder = midpoint(lms, 11, 12)
    hip = midpoint(lms, 23, 24)
    trunk = (shoulder[0] - hip[0], shoulder[1] - hip[1])
    torso = norm(trunk)
    if torso < 1e-5:
        raise RuntimeError('torso too small')
    facing = 1.0 if nose[0] >= ear[0] else -1.0
    head = max(-1.0, min(1.0, ((ear[0] - shoulder[0]) * facing) / torso))
    trunk_abs = max(0.0, min(90.0, angle_between(trunk, (0.0, -1.0))))
    old_neck = max(0.0, min(90.0, angle_between((ear[0]-shoulder[0], ear[1]-shoulder[1]), trunk)))
    shoulder_gap = abs(lms[11].x - lms[12].x)
    hip_gap = abs(lms[23].x - lms[24].x)
    body_width = max(shoulder_gap, hip_gap)
    profile_ratio = body_width / max(torso, 1e-5)
    return {
        'head_forward_ratio': head,
        'trunk_absolute_deg': trunk_abs,
        'old_neck_angle_deg': old_neck,
        'shoulder_gap': shoulder_gap,
        'hip_gap': hip_gap,
        'torso_length': torso,
        'profile_ratio': profile_ratio,
        'side_profile_050': profile_ratio < 0.50,
        'pts': {'nose': nose, 'ear': ear, 'shoulder': shoulder, 'hip': hip},
    }


def reading(raw, base, head_thr=.07, trunk_delta_thr=12.0, abs_trunk_thr=20.0):
    hd = max(0.0, raw['head_forward_ratio'] - base['head_forward_ratio'])
    td = max(0.0, raw['trunk_absolute_deg'] - base['trunk_absolute_deg'])
    bad = hd >= head_thr or td >= trunk_delta_thr or raw['trunk_absolute_deg'] >= abs_trunk_thr
    severe = hd >= .12 or td >= 22.0 or raw['trunk_absolute_deg'] >= 45.0
    return {
        'head_delta_pct': hd * 100.0,
        'trunk_delta_deg': td,
        'trunk_absolute_deg': raw['trunk_absolute_deg'],
        'bad': bad,
        'severe': severe,
    }


def detect(landmarker, path):
    img = mp.Image.create_from_file(str(path))
    result = landmarker.detect(img)
    if not result.pose_landmarks:
        return None
    return result.pose_landmarks[0]


def crop_side(src, side, dst):
    im = Image.open(src).convert('RGB')
    w, h = im.size
    if side == 'left':
        crop = im.crop((0, 0, w // 2, h))
    else:
        crop = im.crop((w // 2, 0, w, h))
    crop.save(dst, quality=94)


def annotate(path, raw, r, title, out_path):
    im = Image.open(path).convert('RGB')
    d = ImageDraw.Draw(im)
    w, h = im.size
    coords = {k: (int(v[0]*w), int(v[1]*h)) for k, v in raw['pts'].items()}
    d.line([coords['ear'], coords['shoulder'], coords['hip']], fill='white', width=max(3, w//120))
    for key, p in coords.items():
        rad = max(4, w//90)
        d.ellipse((p[0]-rad, p[1]-rad, p[0]+rad, p[1]+rad), outline='white', width=2)
    text = (f"{title}\nhead +{r['head_delta_pct']:.1f}% | trunk +{r['trunk_delta_deg']:.1f} deg\n"
            f"abs {r['trunk_absolute_deg']:.1f} | old neck {raw['old_neck_angle_deg']:.1f}\n"
            f"profile {raw['profile_ratio']:.2f} | bad={r['bad']}")
    d.rectangle((0, 0, w, min(112, h//3)), fill='black')
    d.multiline_text((7, 7), text, fill='white', spacing=2)
    im.save(out_path, quality=92)
    return im


def candidate_search(rows):
    candidates = []
    for hthr in [0.05, 0.07, 0.09, 0.12]:
        for tthr in [8, 10, 12, 15, 18]:
            for athr in [20, 25, 30, 35, 40, 45, 999]:
                correct = 0
                fp = fn = 0
                margins = []
                for row in rows:
                    base = row['good_raw']
                    for label in ['good', 'bad']:
                        raw = row[f'{label}_raw']
                        pred = reading(raw, base, hthr, tthr, athr)['bad']
                        truth = (label == 'bad')
                        correct += int(pred == truth)
                        fp += int(pred and not truth)
                        fn += int((not pred) and truth)
                    br = reading(row['bad_raw'], base, hthr, tthr, athr)
                    margins.append(max(br['head_delta_pct']/(hthr*100), br['trunk_delta_deg']/tthr, br['trunk_absolute_deg']/athr if athr < 900 else 0))
                # Favor accuracy first, then fewer FP, then conservative/larger thresholds, then proximity to current relative thresholds.
                candidates.append({
                    'head_threshold_pct': hthr*100,
                    'trunk_delta_threshold_deg': tthr,
                    'absolute_trunk_threshold_deg': None if athr > 900 else athr,
                    'correct': correct,
                    'total': 2*len(rows),
                    'fp': fp,
                    'fn': fn,
                    'min_bad_margin': min(margins) if margins else 0,
                })
    candidates.sort(key=lambda x: (-x['correct'], x['fp'], x['fn'], -(x['absolute_trunk_threshold_deg'] or 999), -x['trunk_delta_threshold_deg'], -x['head_threshold_pct']))
    return candidates[:15]


def main():
    options = vision.PoseLandmarkerOptions(
        base_options=python.BaseOptions(model_asset_path=str(MODEL)),
        running_mode=vision.RunningMode.IMAGE,
        num_poses=1,
        min_pose_detection_confidence=0.30,
        min_pose_presence_confidence=0.30,
        min_tracking_confidence=0.30,
    )
    rows, skipped = [], []
    with vision.PoseLandmarker.create_from_options(options) as landmarker:
        for p in PAIRS:
            src = DATA / p['file']
            if not src.exists() or src.stat().st_size < 1000:
                skipped.append({'id': p['id'], 'reason': 'download_missing'})
                continue
            good_path = OUT / f"{p['id']}_good.jpg"
            bad_path = OUT / f"{p['id']}_bad.jpg"
            crop_side(src, p['good'], good_path)
            crop_side(src, p['bad'], bad_path)
            gl = detect(landmarker, good_path)
            bl = detect(landmarker, bad_path)
            if gl is None or bl is None:
                skipped.append({'id': p['id'], 'reason': 'pose_missing', 'good_detected': gl is not None, 'bad_detected': bl is not None})
                continue
            gr, br = measure(gl), measure(bl)
            base = {'head_forward_ratio': gr['head_forward_ratio'], 'trunk_absolute_deg': gr['trunk_absolute_deg']}
            ge, be = reading(gr, base), reading(br, base)
            row = {
                'id': p['id'], 'kind': p['kind'],
                'good_raw': {k:v for k,v in gr.items() if k != 'pts'},
                'bad_raw': {k:v for k,v in br.items() if k != 'pts'},
                'good_current': ge, 'bad_current': be,
            }
            rows.append(row)
            gimg = annotate(good_path, gr, ge, p['id']+' GOOD', OUT / f"{p['id']}_good_annotated.jpg")
            bimg = annotate(bad_path, br, be, p['id']+' BAD', OUT / f"{p['id']}_bad_annotated.jpg")
            canvas = Image.new('RGB', (gimg.width+bimg.width, max(gimg.height,bimg.height)), 'white')
            canvas.paste(gimg, (0,0)); canvas.paste(bimg, (gimg.width,0))
            canvas.thumbnail((1400, 700))
            canvas.save(OUT / f"{p['id']}_comparison.jpg", quality=90)

    if len(rows) < 3:
        print(json.dumps({'usable_pairs': len(rows), 'skipped': skipped}, indent=2))
        sys.exit(4)

    current_correct = 0; current_fp = current_fn = 0
    for row in rows:
        g = row['good_current']['bad']; b = row['bad_current']['bad']
        current_correct += int(not g) + int(b)
        current_fp += int(g); current_fn += int(not b)

    profile_values = [r[f'{lab}_raw']['profile_ratio'] for r in rows for lab in ['good','bad']]
    payload = {
        'usable_pairs': len(rows),
        'usable_poses': 2*len(rows),
        'skipped': skipped,
        'current_thresholds': {'head_pct':7, 'trunk_delta_deg':12, 'absolute_trunk_deg':20, 'side_profile_ratio':0.50},
        'current_performance': {'correct': current_correct, 'total': 2*len(rows), 'fp': current_fp, 'fn': current_fn, 'accuracy': current_correct/(2*len(rows))},
        'profile_ratio_range': {'min': min(profile_values), 'max': max(profile_values)},
        'pairs': rows,
        'top_threshold_candidates': candidate_search(rows),
    }
    (OUT/'dataset_results.json').write_text(json.dumps(payload, indent=2), encoding='utf-8')
    print(json.dumps(payload, indent=2))

    # This expanded suite is diagnostic: fail only if the current model misses more than half of visibly bad poses.
    bad_hits = sum(int(r['bad_current']['bad']) for r in rows)
    if bad_hits < math.ceil(len(rows)/2):
        print('FAIL: current engine misses most visibly slouched poses', file=sys.stderr)
        sys.exit(5)


if __name__ == '__main__':
    main()
