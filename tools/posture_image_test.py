import json
import math
import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision

MODEL = Path("ergoapp/src/main/assets/pose_landmarker_lite.task")
IMAGE = Path("testdata/posture_compare.jpg")
OUT = Path("posture_test_output")
OUT.mkdir(parents=True, exist_ok=True)


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
    torso_len = norm(trunk)
    if torso_len < 1e-5:
        raise RuntimeError("Torso length too small")
    facing_sign = 1.0 if nose[0] >= ear[0] else -1.0
    head_forward_ratio = max(-1.0, min(1.0, ((ear[0] - shoulder[0]) * facing_sign) / torso_len))
    trunk_abs = max(0.0, min(90.0, angle_between(trunk, (0.0, -1.0))))

    neck_up = (ear[0] - shoulder[0], ear[1] - shoulder[1])
    old_neck = max(0.0, min(90.0, angle_between(neck_up, trunk)))
    shoulder_gap = abs(lms[11].x - lms[12].x)
    hip_gap = abs(lms[23].x - lms[24].x)
    side_profile = (shoulder_gap + hip_gap) / 2.0 < 0.13
    return {
        "nose": nose,
        "ear": ear,
        "shoulder": shoulder,
        "hip": hip,
        "head_forward_ratio": head_forward_ratio,
        "trunk_absolute_deg": trunk_abs,
        "old_neck_angle_deg": old_neck,
        "shoulder_gap": shoulder_gap,
        "hip_gap": hip_gap,
        "side_profile": side_profile,
    }


def level(rank):
    return ["LOW", "MILD", "HIGH", "VERY_HIGH"][rank]


def evaluate(raw, baseline):
    head_delta = max(0.0, raw["head_forward_ratio"] - baseline["head_forward_ratio"])
    trunk_delta = max(0.0, raw["trunk_absolute_deg"] - baseline["trunk_absolute_deg"])

    head_rank = 0 if head_delta < .03 else 1 if head_delta < .07 else 2 if head_delta < .12 else 3
    rel_trunk_rank = 0 if trunk_delta < 5 else 1 if trunk_delta < 12 else 2 if trunk_delta < 22 else 3
    abs_trunk = raw["trunk_absolute_deg"]
    abs_trunk_rank = 0 if abs_trunk < 20 else 1 if abs_trunk < 45 else 2 if abs_trunk < 60 else 3
    trunk_rank = max(rel_trunk_rank, abs_trunk_rank)
    overall = max(head_rank, trunk_rank)
    bad = head_delta >= .07 or trunk_delta >= 12 or abs_trunk >= 20
    severe = head_delta >= .12 or trunk_delta >= 22 or abs_trunk >= 45
    return {
        "head_forward_delta_ratio": head_delta,
        "head_forward_delta_pct": int(head_delta * 100),
        "trunk_delta_deg": trunk_delta,
        "trunk_absolute_deg": abs_trunk,
        "head_level": level(head_rank),
        "trunk_level": level(trunk_rank),
        "overall_level": level(overall),
        "bad": bad,
        "severe": severe,
    }


def detect(landmarker, path):
    img = mp.Image.create_from_file(str(path))
    result = landmarker.detect(img)
    if not result.pose_landmarks:
        raise RuntimeError(f"No pose detected in {path}")
    return result.pose_landmarks[0]


def annotate(path, metrics, reading, title):
    im = Image.open(path).convert("RGB")
    d = ImageDraw.Draw(im)
    w, h = im.size
    pts = {
        "N": metrics["nose"],
        "E": metrics["ear"],
        "S": metrics["shoulder"],
        "H": metrics["hip"],
    }
    xy = {k: (int(v[0] * w), int(v[1] * h)) for k, v in pts.items()}
    d.line([xy["E"], xy["S"], xy["H"]], width=max(3, w // 120))
    for k, p in xy.items():
        r = max(5, w // 80)
        d.ellipse((p[0]-r, p[1]-r, p[0]+r, p[1]+r), outline="white", width=3)
        d.text((p[0]+r+2, p[1]-r), k, fill="white")
    text = (
        f"{title}\n"
        f"head +{reading['head_forward_delta_pct']}% | trunk +{reading['trunk_delta_deg']:.1f} deg\n"
        f"abs trunk {reading['trunk_absolute_deg']:.1f} deg | old neck {metrics['old_neck_angle_deg']:.1f} deg\n"
        f"bad={reading['bad']} severe={reading['severe']} side={metrics['side_profile']}"
    )
    box_h = min(h // 3, 105)
    d.rectangle((0, 0, w, box_h), fill="black")
    d.multiline_text((8, 8), text, fill="white", spacing=3)
    im.save(OUT / f"{title.lower()}_annotated.jpg", quality=92)
    return im


def main():
    if not MODEL.exists() or not IMAGE.exists():
        raise FileNotFoundError("Model or test image missing")

    comparison = Image.open(IMAGE).convert("RGB")
    w, h = comparison.size
    # Source image: left = upright/good, right = forward/slouched, same subject.
    good = comparison.crop((0, 0, w // 2, h))
    bad = comparison.crop((w // 2, 0, w, h))
    Path("testdata").mkdir(exist_ok=True)
    good_path = Path("testdata/good_crop.jpg")
    bad_path = Path("testdata/bad_crop.jpg")
    good.save(good_path, quality=95)
    bad.save(bad_path, quality=95)

    base_options = python.BaseOptions(model_asset_path=str(MODEL))
    options = vision.PoseLandmarkerOptions(
        base_options=base_options,
        running_mode=vision.RunningMode.IMAGE,
        num_poses=1,
        min_pose_detection_confidence=0.35,
        min_pose_presence_confidence=0.35,
        min_tracking_confidence=0.35,
    )
    with vision.PoseLandmarker.create_from_options(options) as landmarker:
        good_lms = detect(landmarker, good_path)
        bad_lms = detect(landmarker, bad_path)

    good_raw = measure(good_lms)
    bad_raw = measure(bad_lms)
    baseline = {
        "head_forward_ratio": good_raw["head_forward_ratio"],
        "trunk_absolute_deg": good_raw["trunk_absolute_deg"],
    }
    good_eval = evaluate(good_raw, baseline)
    bad_eval = evaluate(bad_raw, baseline)

    gimg = annotate(good_path, good_raw, good_eval, "GOOD")
    bimg = annotate(bad_path, bad_raw, bad_eval, "BAD")
    canvas = Image.new("RGB", (gimg.width + bimg.width, max(gimg.height, bimg.height)), "white")
    canvas.paste(gimg, (0, 0))
    canvas.paste(bimg, (gimg.width, 0))
    canvas.save(OUT / "comparison_annotated.jpg", quality=92)

    payload = {
        "source": "same-person side-by-side desk posture comparison",
        "baseline_from": "left/good crop",
        "good_raw": {k: v for k, v in good_raw.items() if k not in {"nose", "ear", "shoulder", "hip"}},
        "bad_raw": {k: v for k, v in bad_raw.items() if k not in {"nose", "ear", "shoulder", "hip"}},
        "good_reading": good_eval,
        "bad_reading": bad_eval,
        "checks": {
            "good_not_bad": not good_eval["bad"],
            "bad_detected": bad_eval["bad"],
            "pose_detected_both": True,
        },
    }
    (OUT / "results.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(json.dumps(payload, indent=2))

    # Regression requirements: baseline should remain neutral and the visibly slouched pose should be caught.
    if good_eval["bad"]:
        print("FAIL: baseline good posture classified bad", file=sys.stderr)
        sys.exit(2)
    if not bad_eval["bad"]:
        print("FAIL: visibly slouched posture was not detected", file=sys.stderr)
        sys.exit(3)


if __name__ == "__main__":
    main()
