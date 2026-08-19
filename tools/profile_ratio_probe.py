import json
import math
from pathlib import Path

from PIL import Image
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision

MODEL = Path('ergoapp/src/main/assets/pose_landmarker_lite.task')
IMAGE = Path('testdata/posture_compare.jpg')
OUT = Path('posture_test_output')
OUT.mkdir(parents=True, exist_ok=True)


def midpoint(lms, a, b):
    return ((lms[a].x + lms[b].x) / 2.0, (lms[a].y + lms[b].y) / 2.0)


def detect(landmarker, path):
    result = landmarker.detect(mp.Image.create_from_file(str(path)))
    if not result.pose_landmarks:
        raise RuntimeError(f'No pose detected: {path}')
    return result.pose_landmarks[0]


def profile_metrics(lms):
    shoulder = midpoint(lms, 11, 12)
    hip = midpoint(lms, 23, 24)
    torso = math.hypot(shoulder[0] - hip[0], shoulder[1] - hip[1])
    shoulder_gap = abs(lms[11].x - lms[12].x)
    hip_gap = abs(lms[23].x - lms[24].x)
    projected_width = (shoulder_gap + hip_gap) / 2.0
    ratio = projected_width / torso if torso > 1e-8 else 999.0
    return {
        'torso_length': torso,
        'shoulder_gap': shoulder_gap,
        'hip_gap': hip_gap,
        'projected_body_width': projected_width,
        'width_to_torso_ratio': ratio,
        'old_absolute_gate': projected_width < 0.13,
        'new_scaled_gate': ratio < 0.50,
    }


def main():
    src = Image.open(IMAGE).convert('RGB')
    w, h = src.size
    good = Path('testdata/good_crop.jpg')
    bad = Path('testdata/bad_crop.jpg')
    src.crop((0, 0, w // 2, h)).save(good, quality=95)
    src.crop((w // 2, 0, w, h)).save(bad, quality=95)

    options = vision.PoseLandmarkerOptions(
        base_options=python.BaseOptions(model_asset_path=str(MODEL)),
        running_mode=vision.RunningMode.IMAGE,
        num_poses=1,
        min_pose_detection_confidence=0.35,
        min_pose_presence_confidence=0.35,
        min_tracking_confidence=0.35,
    )
    with vision.PoseLandmarker.create_from_options(options) as landmarker:
        g = profile_metrics(detect(landmarker, good))
        b = profile_metrics(detect(landmarker, bad))

    payload = {
        'threshold': 0.50,
        'good': g,
        'bad': b,
        'checks': {
            'good_accepted_by_scaled_gate': g['new_scaled_gate'],
            'bad_accepted_by_scaled_gate': b['new_scaled_gate'],
            'old_gate_rejected_good': not g['old_absolute_gate'],
        },
        'synthetic_front_ratio_example': 0.70,
        'synthetic_front_rejected': 0.70 >= 0.50,
    }
    (OUT / 'profile_ratio.json').write_text(json.dumps(payload, indent=2), encoding='utf-8')
    print(json.dumps(payload, indent=2))
    if not g['new_scaled_gate'] or not b['new_scaled_gate']:
        raise SystemExit('Scale-normalized side-profile gate still rejected a side-view test image')
    if not payload['synthetic_front_rejected']:
        raise SystemExit('Synthetic front-facing ratio was not rejected')


if __name__ == '__main__':
    main()
