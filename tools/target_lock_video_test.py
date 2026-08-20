import csv
import json
import math
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
import requests
from mediapipe.tasks import python
from mediapipe.tasks.python import vision

MODEL = Path('ergoapp/src/main/assets/pose_landmarker_lite.task')
DATA = Path('testdata/target_lock')
OUT = Path('target_lock_output')
DATA.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)
VIDEO = DATA / 'two_students_library.mp4'
PAGE = 'https://www.pexels.com/video/people-studying-together-7778558/'
DOWNLOAD = 'https://www.pexels.com/download/video/7778558/'
TARGET_FPS = 5.0


def download_video():
    if VIDEO.exists() and VIDEO.stat().st_size > 100_000:
        return
    headers = {'User-Agent': 'Mozilla/5.0 ErgoAngleTargetLockTest/1.0'}
    with requests.get(DOWNLOAD, headers=headers, stream=True, timeout=90, allow_redirects=True) as r:
        r.raise_for_status()
        with VIDEO.open('wb') as f:
            for chunk in r.iter_content(1024 * 1024):
                if chunk:
                    f.write(chunk)
    if VIDEO.stat().st_size < 100_000:
        raise RuntimeError('Downloaded video is too small')


def midpoint(lms, a, b):
    return np.array([(lms[a].x + lms[b].x) / 2.0, (lms[a].y + lms[b].y) / 2.0], dtype=float)


def pose_feature(lms, index, calibration):
    if len(lms) < 33:
        return None
    nose = np.array([lms[0].x, lms[0].y], dtype=float)
    ear = midpoint(lms, 7, 8)
    shoulder = midpoint(lms, 11, 12)
    hip = midpoint(lms, 23, 24)
    trunk = shoulder - hip
    torso = float(np.linalg.norm(trunk))
    if torso < 0.10:
        return None
    facing = 1.0 if nose[0] >= ear[0] else -1.0
    forward_component = trunk[0] * facing
    vertical_up_component = -trunk[1]
    trunk_forward = math.degrees(math.atan2(forward_component, vertical_up_component))
    shoulder_gap = abs(lms[11].x - lms[12].x)
    hip_gap = abs(lms[23].x - lms[24].x)
    width_ratio = ((shoulder_gap + hip_gap) / 2.0) / torso
    valid = width_ratio < 0.50 and abs(trunk_forward) <= 75.0
    if calibration:
        valid = valid and abs(trunk_forward) <= 45.0
    return {
        'index': index,
        'center_x': float((shoulder[0] + hip[0]) / 2.0),
        'center_y': float((shoulder[1] + hip[1]) / 2.0),
        'torso': torso,
        'width_ratio': float(width_ratio),
        'valid': bool(valid),
        'landmarks': lms,
    }


def dist(a, b):
    return math.hypot(a['center_x'] - b['center_x'], a['center_y'] - b['center_y'])


def log_ratio(a, b):
    if a <= 1e-5 or b <= 1e-5:
        return 10.0
    return abs(math.log(a / b))


class Selector:
    def __init__(self):
        self.calibration = True
        self.provisional = None
        self.samples = []
        self.locked = None
        self.last = None
        self.last_seen = 0

    def initial_score(self, c):
        center = math.hypot(c['center_x'] - 0.5, c['center_y'] - 0.52)
        return center * 2.2 - min(c['torso'], 0.6) * 0.55

    def continuity(self, c, p):
        return dist(c, p) / 0.20 + log_ratio(c['torso'], p['torso']) * 0.65 + abs(c['width_ratio'] - p['width_ratio']) * 0.25

    def locked_score(self, c, now_ms):
        recent = self.last is not None and now_ms - self.last_seen <= 3000
        ref = self.last if recent else self.locked
        move = dist(c, ref)
        size = log_ratio(c['torso'], ref['torso'])
        home = dist(c, self.locked)
        width = abs(c['width_ratio'] - self.locked['width_ratio'])
        return move / 0.24 + size * 0.75 + home / 0.70 * 0.25 + width * 0.20

    def select(self, candidates, now_ms):
        valid = [c for c in candidates if c and c['valid'] and c['torso'] >= 0.08]
        if not valid:
            return None
        if self.locked is None:
            if self.calibration:
                chosen = min(valid, key=self.initial_score) if self.provisional is None else min(valid, key=lambda c: self.continuity(c, self.provisional))
                self.provisional = chosen
                self.samples.append(chosen.copy())
                self.last = chosen.copy()
                self.last_seen = now_ms
                return chosen
            return min(valid, key=self.initial_score)
        best = min(valid, key=lambda c: self.locked_score(c, now_ms))
        if self.locked_score(best, now_ms) > 1.55:
            return None
        self.last = best.copy()
        self.last_seen = now_ms
        return best

    def lock(self, now_ms):
        if len(self.samples) < 5:
            return False
        def med(key):
            return float(np.median([s[key] for s in self.samples]))
        self.locked = {
            'center_x': med('center_x'), 'center_y': med('center_y'),
            'torso': med('torso'), 'width_ratio': med('width_ratio')
        }
        self.last = self.provisional.copy()
        self.last_seen = now_ms
        self.samples = []
        self.calibration = False
        return True


def draw_frame(frame, candidates, selected):
    h, w = frame.shape[:2]
    for c in candidates:
        if c is None:
            continue
        x = int(c['center_x'] * w)
        y = int(c['center_y'] * h)
        radius = max(10, int(c['torso'] * min(w, h) * 0.35))
        is_sel = selected is c
        color = (0, 255, 0) if is_sel else (0, 0, 255)
        cv2.circle(frame, (x, y), radius, color, 3)
        cv2.putText(frame, 'TARGET' if is_sel else 'OTHER', (max(0, x-radius), max(25, y-radius-8)), cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)
    return frame


def main():
    download_video()
    cap = cv2.VideoCapture(str(VIDEO))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    duration = frame_count / fps if frame_count else 0.0
    step = max(1, round(fps / TARGET_FPS))

    options = vision.PoseLandmarkerOptions(
        base_options=python.BaseOptions(model_asset_path=str(MODEL)),
        running_mode=vision.RunningMode.VIDEO,
        num_poses=3,
        min_pose_detection_confidence=0.35,
        min_pose_presence_confidence=0.35,
        min_tracking_confidence=0.35,
    )
    selector = Selector()
    rows = []
    montage = []
    selected_prev = None
    max_step = 0.0
    multi_frames = 0
    locked_frames = 0
    lost_frames = 0
    lock_done = False
    frame_i = 0

    with vision.PoseLandmarker.create_from_options(options) as landmarker:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            if frame_i % step != 0:
                frame_i += 1
                continue
            t_ms = int(round(frame_i * 1000.0 / fps))
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            result = landmarker.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), t_ms)
            raw_poses = result.pose_landmarks
            calibration = t_ms < 5000
            candidates = []
            for idx, lms in enumerate(raw_poses):
                f = pose_feature(lms, idx, calibration=calibration)
                if f is not None:
                    candidates.append(f)
            # Deliberately scramble list ordering every other sampled frame. The selector must
            # follow geometry/continuity, never MediaPipe's array position.
            if (len(rows) % 2) == 1:
                candidates = list(reversed(candidates))
            for new_index, c in enumerate(candidates):
                c['index'] = new_index

            valid_count = sum(1 for c in candidates if c['valid'])
            if valid_count >= 2:
                multi_frames += 1
            selected = selector.select(candidates, t_ms)

            if not lock_done and t_ms >= 5000:
                lock_done = selector.lock(t_ms)
                # Re-select under locked rules on the same frame.
                selected = selector.select(candidates, t_ms) if lock_done else None

            if lock_done:
                locked_frames += 1
                if selected is None:
                    lost_frames += 1
                elif selected_prev is not None:
                    step_dist = math.hypot(selected['center_x'] - selected_prev['center_x'], selected['center_y'] - selected_prev['center_y'])
                    max_step = max(max_step, step_dist)
                if selected is not None:
                    selected_prev = selected.copy()

            rows.append({
                't_ms': t_ms,
                'pose_count': len(raw_poses),
                'valid_count': valid_count,
                'locked': lock_done,
                'selected': selected is not None,
                'selected_index': '' if selected is None else selected['index'],
                'selected_x': '' if selected is None else round(selected['center_x'], 4),
                'selected_y': '' if selected is None else round(selected['center_y'], 4),
                'selected_torso': '' if selected is None else round(selected['torso'], 4),
            })

            if selected is not None and (len(montage) < 1 or (valid_count >= 2 and len(montage) < 4)):
                montage.append(draw_frame(frame.copy(), candidates, selected))
            frame_i += 1
    cap.release()

    if not lock_done:
        raise RuntimeError('Could not lock a target during first 5 seconds')
    if multi_frames < 3:
        raise RuntimeError(f'Insufficient real multi-person frames: {multi_frames}')

    lost_pct = 100.0 * lost_frames / max(1, locked_frames)
    summary = {
        'source_page': PAGE,
        'duration_s': round(duration, 2),
        'sampled_frames': len(rows),
        'multi_person_valid_frames': multi_frames,
        'locked_frames': locked_frames,
        'lost_after_lock_pct': round(lost_pct, 1),
        'max_selected_center_step': round(max_step, 4),
        'simulated_pose_order_reversal_every_other_frame': True,
        'target_lock_pass': bool(lost_pct < 50.0 and max_step < 0.25),
    }
    (OUT / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    with (OUT / 'timeseries.csv').open('w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)

    if montage:
        height = min(img.shape[0] for img in montage)
        resized = [cv2.resize(img, (int(img.shape[1] * height / img.shape[0]), height)) for img in montage]
        sheet = np.hstack(resized)
        cv2.imwrite(str(OUT / 'target_lock_montage.jpg'), sheet)

    print(json.dumps(summary, indent=2))
    if not summary['target_lock_pass']:
        raise RuntimeError('Target lock continuity thresholds failed')


if __name__ == '__main__':
    main()
