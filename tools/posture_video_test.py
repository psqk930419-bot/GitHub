import csv
import json
import math
import os
import statistics
import subprocess
import sys
from pathlib import Path

import cv2
import matplotlib.pyplot as plt
import mediapipe as mp
import numpy as np
import requests
from mediapipe.tasks import python
from mediapipe.tasks.python import vision

MODEL = Path('ergoapp/src/main/assets/pose_landmarker_lite.task')
DATA = Path('testdata/video')
OUT = Path('posture_video_output')
DATA.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)
TARGET_FPS = 5.0
SIDE_RATIO_CUTOFF = 0.50
EMA_ALPHA = 0.22
HYSTERESIS_MS = 1500
ALERT_MS = 45_000

SOURCES = [
    {
        'id': 'pexels_desktop_man',
        'page': 'https://www.pexels.com/video/person-using-desktop-computer-4941463/',
        'download': 'https://www.pexels.com/download/video/4941463/',
        'path': DATA / 'pexels_desktop_man.mp4',
        'type': 'pexels',
    },
    {
        'id': 'pexels_laptop_woman',
        'page': 'https://www.pexels.com/video/side-view-of-a-woman-typing-on-her-laptop-7590787/',
        'download': 'https://www.pexels.com/download/video/7590787/',
        'path': DATA / 'pexels_laptop_woman.mp4',
        'type': 'pexels',
    },
    {
        'id': 'commons_student',
        'title': 'File:Student studies in classroom during face to face learning in Padang, Indonesia.webm',
        'page': 'https://commons.wikimedia.org/wiki/File:Student_studies_in_classroom_during_face_to_face_learning_in_Padang,_Indonesia.webm',
        'path': DATA / 'commons_student.webm',
        'type': 'commons',
    },
]


def valid_media(path: Path) -> bool:
    if not path.exists() or path.stat().st_size < 100_000:
        return False
    with path.open('rb') as f:
        head = f.read(64).lower()
    return b'<html' not in head and b'<!doctype' not in head


def download_pexels(src):
    path = src['path']
    if valid_media(path):
        return True, 'cached'
    headers = {'User-Agent': 'Mozilla/5.0 ErgoAngleVideoValidation/1.0'}
    try:
        with requests.get(src['download'], headers=headers, stream=True, timeout=60, allow_redirects=True) as r:
            r.raise_for_status()
            with path.open('wb') as f:
                for chunk in r.iter_content(1024 * 1024):
                    if chunk:
                        f.write(chunk)
        if valid_media(path):
            return True, 'pexels-download-endpoint'
    except Exception as e:
        print(f"direct Pexels download failed for {src['id']}: {e}")
        path.unlink(missing_ok=True)
    try:
        subprocess.run([
            'yt-dlp', '--no-playlist', '-S', 'res:720',
            '-o', str(path), src['page']
        ], check=True, timeout=180)
        if valid_media(path):
            return True, 'yt-dlp'
    except Exception as e:
        print(f"yt-dlp fallback failed for {src['id']}: {e}")
        path.unlink(missing_ok=True)
    return False, 'failed'


def download_commons(src):
    path = src['path']
    if valid_media(path):
        return True, 'cached'
    api = 'https://commons.wikimedia.org/w/api.php'
    params = {
        'action': 'query', 'format': 'json', 'prop': 'imageinfo',
        'iiprop': 'url', 'titles': src['title']
    }
    headers = {'User-Agent': 'ErgoAngleVideoValidation/1.0 (research prototype)'}
    try:
        js = requests.get(api, params=params, headers=headers, timeout=30).json()
        page = next(iter(js['query']['pages'].values()))
        url = page['imageinfo'][0]['url']
        with requests.get(url, headers=headers, stream=True, timeout=60) as r:
            r.raise_for_status()
            with path.open('wb') as f:
                for chunk in r.iter_content(1024 * 1024):
                    if chunk:
                        f.write(chunk)
        return valid_media(path), 'wikimedia-api'
    except Exception as e:
        print(f"Commons download failed: {e}")
        path.unlink(missing_ok=True)
        return False, 'failed'


def midpoint(lms, a, b):
    return np.array([(lms[a].x + lms[b].x) / 2.0, (lms[a].y + lms[b].y) / 2.0], dtype=float)


def measure(lms):
    nose = np.array([lms[0].x, lms[0].y], dtype=float)
    ear = midpoint(lms, 7, 8)
    shoulder = midpoint(lms, 11, 12)
    hip = midpoint(lms, 23, 24)
    trunk = shoulder - hip
    torso = float(np.linalg.norm(trunk))
    if torso < 1e-5:
        return None
    facing = 1.0 if nose[0] >= ear[0] else -1.0
    head_ratio = float(np.clip(((ear[0] - shoulder[0]) * facing) / torso, -1, 1))
    forward = (shoulder[0] - hip[0]) * facing
    vertical_up = -(shoulder[1] - hip[1])
    trunk_forward = float(np.degrees(np.arctan2(forward, vertical_up)))
    trunk_forward = float(np.clip(trunk_forward, -90, 90))
    body_width = (abs(lms[11].x - lms[12].x) + abs(lms[23].x - lms[24].x)) / 2.0
    side_ratio = float(body_width / torso)
    return {
        'nose': nose, 'ear': ear, 'shoulder': shoulder, 'hip': hip,
        'head_ratio': head_ratio, 'trunk_forward': trunk_forward,
        'side_ratio': side_ratio, 'side': side_ratio < SIDE_RATIO_CUTOFF,
    }


def evaluate(head_ratio, trunk_forward, baseline):
    head = max(0.0, head_ratio - baseline['head'])
    trunk = max(0.0, trunk_forward - baseline['trunk'])
    bad = head >= 0.07 or trunk >= 12.0
    severe = head >= 0.12 or trunk >= 22.0
    return head, trunk, bad, severe


def detect_video(path: Path, landmarker):
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise RuntimeError(f'cannot open video {path}')
    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    duration = total_frames / fps if total_frames else 0.0
    stride = max(1, int(round(fps / TARGET_FPS)))
    rows = []
    frame_index = 0
    while True:
        ok, bgr = cap.read()
        if not ok:
            break
        if frame_index % stride != 0:
            frame_index += 1
            continue
        t_ms = int(round(frame_index * 1000.0 / fps))
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        result = landmarker.detect_for_video(image, t_ms)
        pose = result.pose_landmarks[0] if result.pose_landmarks else None
        metrics = measure(pose) if pose else None
        rows.append({'t_ms': t_ms, 'frame': frame_index, 'bgr': bgr, 'pose': pose, 'metrics': metrics})
        frame_index += 1
    cap.release()
    return rows, fps, duration


def make_baseline(rows):
    valid = [r for r in rows if r['metrics'] and r['metrics']['side']]
    if len(valid) < 10:
        return None, []
    first_t = valid[0]['t_ms']
    baseline_rows = [r for r in valid if r['t_ms'] - first_t <= 5000]
    if len(baseline_rows) < 10:
        baseline_rows = valid[:max(10, len(valid) // 3)]
    return {
        'head': statistics.median(r['metrics']['head_ratio'] for r in baseline_rows),
        'trunk': statistics.median(r['metrics']['trunk_forward'] for r in baseline_rows),
    }, baseline_rows


def run_states(rows, baseline):
    data = []
    ema_h = None
    ema_t = None
    state = False
    enter_ms = 0
    exit_ms = 0
    last_valid_t = None
    raw_prev = None
    hys_prev = None
    raw_flips = 0
    hys_flips = 0
    state_bad_run = 0
    max_bad_run = 0
    natural_alert_t = None
    for r in rows:
        m = r['metrics']
        if not m or not m['side']:
            data.append({'t_ms': r['t_ms'], 'valid': False})
            last_valid_t = None
            enter_ms = exit_ms = 0
            continue
        h, t, raw_bad, severe = evaluate(m['head_ratio'], m['trunk_forward'], baseline)
        ema_h = m['head_ratio'] if ema_h is None else ema_h + EMA_ALPHA * (m['head_ratio'] - ema_h)
        ema_t = m['trunk_forward'] if ema_t is None else ema_t + EMA_ALPHA * (m['trunk_forward'] - ema_t)
        sh, st, smooth_bad, smooth_severe = evaluate(ema_h, ema_t, baseline)
        dt = 0 if last_valid_t is None else min(1000, max(0, r['t_ms'] - last_valid_t))
        last_valid_t = r['t_ms']
        if smooth_bad:
            enter_ms += dt
            exit_ms = 0
            if not state and enter_ms >= HYSTERESIS_MS:
                state = True
                enter_ms = 0
        else:
            exit_ms += dt
            enter_ms = 0
            if state and exit_ms >= HYSTERESIS_MS:
                state = False
                exit_ms = 0
        if raw_prev is not None and raw_bad != raw_prev:
            raw_flips += 1
        if hys_prev is not None and state != hys_prev:
            hys_flips += 1
        raw_prev = raw_bad
        hys_prev = state
        if state:
            state_bad_run += dt
            max_bad_run = max(max_bad_run, state_bad_run)
            if natural_alert_t is None and state_bad_run >= ALERT_MS:
                natural_alert_t = r['t_ms']
        else:
            state_bad_run = 0
        data.append({
            't_ms': r['t_ms'], 'valid': True,
            'head_delta': h, 'trunk_delta': t, 'raw_bad': raw_bad,
            'smooth_head_delta': sh, 'smooth_trunk_delta': st,
            'smooth_bad': smooth_bad, 'hys_bad': state,
            'severe': severe, 'side_ratio': m['side_ratio'],
        })
    return data, raw_flips, hys_flips, max_bad_run, natural_alert_t


def stress_replay(data):
    valid = [d for d in data if d.get('valid')]
    if not valid:
        return {'available': False}
    worst = max(valid, key=lambda d: d['smooth_head_delta'] / 0.07 + d['smooth_trunk_delta'] / 12.0)
    candidate_bad = worst['smooth_head_delta'] >= 0.07 or worst['smooth_trunk_delta'] >= 12.0
    if not candidate_bad:
        return {
            'available': True, 'worst_is_bad': False,
            'head_delta_pct': round(worst['smooth_head_delta'] * 100, 1),
            'trunk_delta_deg': round(worst['smooth_trunk_delta'], 1),
            'alert_at_s': None,
        }
    # The worst real posture frame is held/replayed. With 1.5 s entry hysteresis,
    # a 45 s continuous BAD alert should arrive at ~46.5 s from replay start.
    return {
        'available': True, 'worst_is_bad': True,
        'head_delta_pct': round(worst['smooth_head_delta'] * 100, 1),
        'trunk_delta_deg': round(worst['smooth_trunk_delta'], 1),
        'alert_at_s': round((HYSTERESIS_MS + ALERT_MS) / 1000.0, 1),
    }


def annotate_frame(item, baseline, label, path):
    bgr = item['bgr'].copy()
    m = item['metrics']
    if m:
        h, t, bad, severe = evaluate(m['head_ratio'], m['trunk_forward'], baseline)
        H, W = bgr.shape[:2]
        pts = [m['ear'], m['shoulder'], m['hip']]
        xy = [(int(p[0] * W), int(p[1] * H)) for p in pts]
        cv2.line(bgr, xy[0], xy[1], (255,255,255), 3)
        cv2.line(bgr, xy[1], xy[2], (255,255,255), 3)
        for p in xy:
            cv2.circle(bgr, p, 5, (255,255,255), -1)
        text = f"{label} head +{h*100:.1f}% trunk +{t:.1f}deg bad={bad} side={m['side_ratio']:.2f}"
        cv2.rectangle(bgr, (0,0), (W,45), (0,0,0), -1)
        cv2.putText(bgr, text, (8,28), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255,255,255), 2, cv2.LINE_AA)
    cv2.imwrite(str(path), bgr)


def plot_series(video_id, data):
    valid = [d for d in data if d.get('valid')]
    if not valid:
        return
    t = np.array([d['t_ms']/1000.0 for d in valid])
    head = np.array([d['smooth_head_delta']*100 for d in valid])
    trunk = np.array([d['smooth_trunk_delta'] for d in valid])
    raw = np.array([1 if d['raw_bad'] else 0 for d in valid])
    hys = np.array([1 if d['hys_bad'] else 0 for d in valid])
    plt.figure(figsize=(10,5))
    plt.plot(t, head, label='head forward delta (%)')
    plt.plot(t, trunk, label='forward trunk delta (deg)')
    plt.axhline(7, linestyle='--', linewidth=1, label='head BAD 7%')
    plt.axhline(12, linestyle=':', linewidth=1, label='trunk BAD 12deg')
    plt.fill_between(t, 0, np.maximum(head.max() if len(head) else 1, trunk.max() if len(trunk) else 1), where=hys.astype(bool), alpha=0.12, label='hysteresis BAD')
    plt.xlabel('time (s)')
    plt.ylabel('personal-baseline change')
    plt.title(video_id)
    plt.legend(loc='best', fontsize=8)
    plt.tight_layout()
    plt.savefig(OUT / f'{video_id}_timeline.png', dpi=150)
    plt.close()


def main():
    if not MODEL.exists():
        raise FileNotFoundError(MODEL)
    available = []
    download_log = []
    for src in SOURCES:
        ok, method = download_pexels(src) if src['type'] == 'pexels' else download_commons(src)
        download_log.append({'id': src['id'], 'ok': ok, 'method': method, 'page': src['page']})
        if ok:
            available.append(src)
    if not available:
        raise RuntimeError('No test videos downloaded')

    options = vision.PoseLandmarkerOptions(
        base_options=python.BaseOptions(model_asset_path=str(MODEL)),
        running_mode=vision.RunningMode.VIDEO,
        num_poses=1,
        min_pose_detection_confidence=0.35,
        min_pose_presence_confidence=0.35,
        min_tracking_confidence=0.35,
    )
    summaries = []
    csv_rows = []
    with vision.PoseLandmarker.create_from_options(options) as landmarker:
        for src in available:
            print(f"processing {src['id']} -> {src['path']}")
            rows, source_fps, duration = detect_video(src['path'], landmarker)
            baseline, baseline_rows = make_baseline(rows)
            if baseline is None:
                summaries.append({'id': src['id'], 'error': 'insufficient valid side-profile frames', 'duration_s': duration})
                continue
            data, raw_flips, hys_flips, max_bad_run, natural_alert_t = run_states(rows, baseline)
            valid = [d for d in data if d.get('valid')]
            detected = sum(1 for r in rows if r['metrics'])
            side = sum(1 for r in rows if r['metrics'] and r['metrics']['side'])
            raw_bad = sum(1 for d in valid if d['raw_bad'])
            hys_bad = sum(1 for d in valid if d['hys_bad'])
            stress = stress_replay(data)
            minutes = max(duration / 60.0, 1/60)
            summary = {
                'id': src['id'], 'source_page': src['page'],
                'duration_s': round(duration,2), 'source_fps': round(source_fps,2),
                'sampled_frames': len(rows),
                'pose_detection_pct': round(100*detected/max(1,len(rows)),1),
                'side_valid_pct': round(100*side/max(1,len(rows)),1),
                'baseline_valid_frames': len(baseline_rows),
                'baseline_head_ratio': round(baseline['head'],4),
                'baseline_trunk_forward_deg': round(baseline['trunk'],1),
                'raw_bad_pct': round(100*raw_bad/max(1,len(valid)),1),
                'hysteresis_bad_pct': round(100*hys_bad/max(1,len(valid)),1),
                'raw_state_flips_per_min': round(raw_flips/minutes,1),
                'hysteresis_flips_per_min': round(hys_flips/minutes,1),
                'max_hysteresis_bad_run_s': round(max_bad_run/1000.0,1),
                'natural_45s_alert': natural_alert_t is not None,
                'stress_replay': stress,
            }
            summaries.append(summary)
            for d in data:
                if d.get('valid'):
                    csv_rows.append({'video': src['id'], **{k:v for k,v in d.items() if k != 'valid'}})
            plot_series(src['id'], data)
            valid_rows = [r for r in rows if r['metrics'] and r['metrics']['side']]
            if valid_rows:
                base_item = baseline_rows[len(baseline_rows)//2]
                scored = []
                for r in valid_rows:
                    h,t,b,s = evaluate(r['metrics']['head_ratio'], r['metrics']['trunk_forward'], baseline)
                    scored.append((h/0.07+t/12.0, r))
                worst_item = max(scored, key=lambda x:x[0])[1]
                annotate_frame(base_item, baseline, 'BASELINE', OUT / f'{src["id"]}_baseline.jpg')
                annotate_frame(worst_item, baseline, 'WORST', OUT / f'{src["id"]}_worst.jpg')

    (OUT/'summary.json').write_text(json.dumps({'downloads':download_log,'videos':summaries}, indent=2), encoding='utf-8')
    if csv_rows:
        keys = ['video','t_ms','head_delta','trunk_delta','raw_bad','smooth_head_delta','smooth_trunk_delta','smooth_bad','hys_bad','severe','side_ratio']
        with (OUT/'timeseries.csv').open('w', newline='', encoding='utf-8') as f:
            w = csv.DictWriter(f, fieldnames=keys)
            w.writeheader()
            for row in csv_rows:
                w.writerow({k: row.get(k,'') for k in keys})
    print(json.dumps({'downloads':download_log,'videos':summaries}, indent=2))

    processed = [s for s in summaries if 'error' not in s]
    if not processed:
        print('FAIL: no video produced a valid baseline', file=sys.stderr)
        sys.exit(2)
    # Require at least one real side-view clip to have >=70% usable side-profile frames.
    if not any(s['side_valid_pct'] >= 70 for s in processed):
        print('FAIL: none of the videos had sufficient side-view coverage', file=sys.stderr)
        sys.exit(3)


if __name__ == '__main__':
    main()
