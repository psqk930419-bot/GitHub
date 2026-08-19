import csv
import json
import statistics
import sys
from pathlib import Path

import numpy as np
from mediapipe.tasks import python
from mediapipe.tasks.python import vision

import posture_video_test as p

# Keep the known partial-body clip as a negative control and add two deliberately
# wider side-view desk/study clips for positive validation.
EXTRA_SOURCES = [
    {
        'id': 'pexels_fullbody_woman',
        'page': 'https://www.pexels.com/video/woman-sitting-by-the-table-while-writing-and-reading-a-bible-5199823/',
        'download': 'https://www.pexels.com/download/video/5199823/',
        'path': p.DATA / 'pexels_fullbody_woman.mp4',
        'type': 'pexels',
        'expected': 'positive_side',
    },
    {
        'id': 'pexels_library_student',
        'page': 'https://www.pexels.com/video/young-male-student-sitting-alone-in-library-6281068/',
        'download': 'https://www.pexels.com/download/video/6281068/',
        'path': p.DATA / 'pexels_library_student.mp4',
        'type': 'pexels',
        'expected': 'positive_side',
    },
]

EXPECTED = {
    'pexels_desktop_man': 'positive_side',
    'pexels_laptop_woman': 'negative_partial_body',
    'commons_student': 'multi_person_observation',
    'pexels_fullbody_woman': 'positive_side',
    'pexels_library_student': 'positive_side',
}

TORSO_MIN = 0.10
SIDE_RATIO_MAX = 0.50
VIS_MIN = 0.50
CALIBRATION_ABS_TRUNK_MAX = 45.0


def _vis(lm):
    v = getattr(lm, 'visibility', None)
    return 1.0 if v is None else float(v)


def quality_measure(lms):
    nose = np.array([lms[0].x, lms[0].y], dtype=float)
    ear = p.midpoint(lms, 7, 8)
    shoulder = p.midpoint(lms, 11, 12)
    hip = p.midpoint(lms, 23, 24)
    trunk = shoulder - hip
    torso = float(np.linalg.norm(trunk))
    if torso < 1e-5:
        return None
    facing = 1.0 if nose[0] >= ear[0] else -1.0
    head_ratio = float(np.clip(((ear[0] - shoulder[0]) * facing) / torso, -1, 1))
    forward = (shoulder[0] - hip[0]) * facing
    vertical_up = -(shoulder[1] - hip[1])
    trunk_forward = float(np.clip(np.degrees(np.arctan2(forward, vertical_up)), -90, 90))
    body_width = (abs(lms[11].x - lms[12].x) + abs(lms[23].x - lms[24].x)) / 2.0
    side_ratio = float(body_width / torso)

    group_vis = {
        'nose': _vis(lms[0]),
        'ear': max(_vis(lms[7]), _vis(lms[8])),
        'shoulder': max(_vis(lms[11]), _vis(lms[12])),
        'hip': max(_vis(lms[23]), _vis(lms[24])),
    }
    min_key_vis = min(group_vis.values())
    legacy_side = side_ratio < SIDE_RATIO_MAX
    full_torso_quality = torso >= TORSO_MIN and min_key_vis >= VIS_MIN
    side = legacy_side and full_torso_quality
    calibration_ok = side and abs(trunk_forward) <= CALIBRATION_ABS_TRUNK_MAX

    return {
        'nose': nose, 'ear': ear, 'shoulder': shoulder, 'hip': hip,
        'head_ratio': head_ratio, 'trunk_forward': trunk_forward,
        'side_ratio': side_ratio, 'torso_len': torso,
        'min_key_visibility': min_key_vis,
        'group_visibility': group_vis,
        'legacy_side': legacy_side,
        'full_torso_quality': full_torso_quality,
        'side': side,
        'calibration_ok': calibration_ok,
    }


def quality_baseline(rows):
    valid = [r for r in rows if r['metrics'] and r['metrics']['calibration_ok']]
    if len(valid) < 10:
        return None, []
    first_t = valid[0]['t_ms']
    baseline_rows = [r for r in valid if r['t_ms'] - first_t <= 5000]
    if len(baseline_rows) < 10:
        baseline_rows = valid[:max(10, len(valid)//3)]
    return {
        'head': statistics.median(r['metrics']['head_ratio'] for r in baseline_rows),
        'trunk': statistics.median(r['metrics']['trunk_forward'] for r in baseline_rows),
    }, baseline_rows


def process(src):
    options = vision.PoseLandmarkerOptions(
        base_options=python.BaseOptions(model_asset_path=str(p.MODEL)),
        running_mode=vision.RunningMode.VIDEO,
        num_poses=1,
        min_pose_detection_confidence=0.35,
        min_pose_presence_confidence=0.35,
        min_tracking_confidence=0.35,
    )
    with vision.PoseLandmarker.create_from_options(options) as landmarker:
        rows, source_fps, duration = p.detect_video(src['path'], landmarker)

    detected = [r for r in rows if r['metrics']]
    legacy = [r for r in detected if r['metrics']['legacy_side']]
    quality = [r for r in detected if r['metrics']['side']]
    calibratable = [r for r in detected if r['metrics']['calibration_ok']]
    baseline, baseline_rows = quality_baseline(rows)

    summary = {
        'id': src['id'], 'expected': EXPECTED.get(src['id'], 'unknown'),
        'source_page': src['page'], 'duration_s': round(duration,2),
        'sampled_frames': len(rows),
        'pose_detection_pct': round(100*len(detected)/max(1,len(rows)),1),
        'legacy_side_pct': round(100*len(legacy)/max(1,len(rows)),1),
        'quality_side_pct': round(100*len(quality)/max(1,len(rows)),1),
        'calibratable_pct': round(100*len(calibratable)/max(1,len(rows)),1),
    }
    if detected:
        summary.update({
            'median_torso_len': round(float(statistics.median(r['metrics']['torso_len'] for r in detected)),3),
            'median_min_key_visibility': round(float(statistics.median(r['metrics']['min_key_visibility'] for r in detected)),3),
            'median_abs_trunk_deg': round(float(statistics.median(abs(r['metrics']['trunk_forward']) for r in detected)),1),
        })
    if baseline is None:
        summary['baseline_status'] = 'rejected'
        return summary, rows, None, []

    summary['baseline_status'] = 'accepted'
    summary['baseline_head_ratio'] = round(baseline['head'],4)
    summary['baseline_trunk_forward_deg'] = round(baseline['trunk'],1)
    data, raw_flips, hys_flips, max_bad_run, natural_alert_t = p.run_states(rows, baseline)
    valid = [d for d in data if d.get('valid')]
    duration_min = max(duration/60.0, 1/60)
    summary.update({
        'raw_bad_pct': round(100*sum(d['raw_bad'] for d in valid)/max(1,len(valid)),1),
        'hysteresis_bad_pct': round(100*sum(d['hys_bad'] for d in valid)/max(1,len(valid)),1),
        'raw_flips_per_min': round(raw_flips/duration_min,1),
        'hysteresis_flips_per_min': round(hys_flips/duration_min,1),
        'max_hysteresis_bad_run_s': round(max_bad_run/1000,1),
        'natural_45s_alert': natural_alert_t is not None,
        'stress_replay': p.stress_replay(data),
    })
    p.plot_series(src['id'] + '_quality', data)
    valid_rows = [r for r in rows if r['metrics'] and r['metrics']['side']]
    if valid_rows:
        base_item = baseline_rows[len(baseline_rows)//2]
        scored=[]
        for r in valid_rows:
            h,t,_,_=p.evaluate(r['metrics']['head_ratio'],r['metrics']['trunk_forward'],baseline)
            scored.append((h/0.07+t/12.0,r))
        worst=max(scored,key=lambda x:x[0])[1]
        p.annotate_frame(base_item,baseline,'BASELINE',p.OUT/f'{src["id"]}_quality_baseline.jpg')
        p.annotate_frame(worst,baseline,'WORST',p.OUT/f'{src["id"]}_quality_worst.jpg')
    return summary, rows, data, baseline_rows


def main():
    # Monkey-patch the frame measurement used by the existing VIDEO decoder.
    p.measure = quality_measure
    sources = list(p.SOURCES) + EXTRA_SOURCES
    downloads=[]
    available=[]
    for src in sources:
        ok,method = p.download_pexels(src) if src['type']=='pexels' else p.download_commons(src)
        downloads.append({'id':src['id'],'ok':ok,'method':method,'page':src['page']})
        if ok: available.append(src)
    summaries=[]
    for src in available:
        print('QUALITY PROCESS',src['id'])
        summary,_,_,_=process(src)
        summaries.append(summary)
    payload={'quality_gate':{
        'side_ratio_max':SIDE_RATIO_MAX,'torso_min':TORSO_MIN,'min_key_visibility':VIS_MIN,
        'calibration_abs_trunk_max_deg':CALIBRATION_ABS_TRUNK_MAX,
        'ema_alpha':p.EMA_ALPHA,'hysteresis_ms':p.HYSTERESIS_MS,'alert_ms':p.ALERT_MS,
    },'downloads':downloads,'videos':summaries}
    (p.OUT/'quality_summary.json').write_text(json.dumps(payload,indent=2),encoding='utf-8')
    print(json.dumps(payload,indent=2))

    by_id={s['id']:s for s in summaries}
    # Negative control must no longer be accepted for 5-second calibration.
    neg=by_id.get('pexels_laptop_woman')
    if neg and neg.get('baseline_status')!='rejected':
        print('FAIL: partial-body negative control still accepted',file=sys.stderr);sys.exit(5)
    positives=[by_id.get(x) for x in ['pexels_desktop_man','pexels_fullbody_woman','pexels_library_student']]
    positives=[x for x in positives if x]
    if not positives or not any(x.get('baseline_status')=='accepted' for x in positives):
        print('FAIL: no positive side-view clip accepted',file=sys.stderr);sys.exit(6)

if __name__=='__main__':
    main()
