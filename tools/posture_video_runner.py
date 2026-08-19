import csv
import json
import sys

from mediapipe.tasks import python
from mediapipe.tasks.python import vision

import posture_video_test as p


def main():
    if not p.MODEL.exists():
        raise FileNotFoundError(p.MODEL)

    available = []
    download_log = []
    for src in p.SOURCES:
        ok, method = p.download_pexels(src) if src['type'] == 'pexels' else p.download_commons(src)
        download_log.append({'id': src['id'], 'ok': ok, 'method': method, 'page': src['page']})
        if ok:
            available.append(src)
    if not available:
        raise RuntimeError('No test videos downloaded')

    summaries = []
    csv_rows = []
    for src in available:
        # MediaPipe VIDEO timestamps are strictly monotonic within a task instance.
        # Create one task per source clip so each real video may begin at t=0.
        options = vision.PoseLandmarkerOptions(
            base_options=python.BaseOptions(model_asset_path=str(p.MODEL)),
            running_mode=vision.RunningMode.VIDEO,
            num_poses=1,
            min_pose_detection_confidence=0.35,
            min_pose_presence_confidence=0.35,
            min_tracking_confidence=0.35,
        )
        with vision.PoseLandmarker.create_from_options(options) as landmarker:
            print(f"processing {src['id']} -> {src['path']}")
            rows, source_fps, duration = p.detect_video(src['path'], landmarker)

        baseline, baseline_rows = p.make_baseline(rows)
        if baseline is None:
            summaries.append({'id': src['id'], 'source_page': src['page'], 'error': 'insufficient valid side-profile frames', 'duration_s': duration})
            continue

        data, raw_flips, hys_flips, max_bad_run, natural_alert_t = p.run_states(rows, baseline)
        valid = [d for d in data if d.get('valid')]
        detected = sum(1 for r in rows if r['metrics'])
        side = sum(1 for r in rows if r['metrics'] and r['metrics']['side'])
        raw_bad = sum(1 for d in valid if d['raw_bad'])
        hys_bad = sum(1 for d in valid if d['hys_bad'])
        stress = p.stress_replay(data)
        minutes = max(duration / 60.0, 1 / 60)

        summary = {
            'id': src['id'], 'source_page': src['page'],
            'duration_s': round(duration, 2), 'source_fps': round(source_fps, 2),
            'sampled_frames': len(rows),
            'pose_detection_pct': round(100 * detected / max(1, len(rows)), 1),
            'side_valid_pct': round(100 * side / max(1, len(rows)), 1),
            'baseline_valid_frames': len(baseline_rows),
            'baseline_head_ratio': round(baseline['head'], 4),
            'baseline_trunk_forward_deg': round(baseline['trunk'], 1),
            'raw_bad_pct': round(100 * raw_bad / max(1, len(valid)), 1),
            'hysteresis_bad_pct': round(100 * hys_bad / max(1, len(valid)), 1),
            'raw_state_flips_per_min': round(raw_flips / minutes, 1),
            'hysteresis_flips_per_min': round(hys_flips / minutes, 1),
            'max_hysteresis_bad_run_s': round(max_bad_run / 1000.0, 1),
            'natural_45s_alert': natural_alert_t is not None,
            'stress_replay': stress,
        }
        summaries.append(summary)

        for d in data:
            if d.get('valid'):
                csv_rows.append({'video': src['id'], **{k: v for k, v in d.items() if k != 'valid'}})

        p.plot_series(src['id'], data)
        valid_rows = [r for r in rows if r['metrics'] and r['metrics']['side']]
        if valid_rows:
            base_item = baseline_rows[len(baseline_rows) // 2]
            scored = []
            for r in valid_rows:
                h, t, _, _ = p.evaluate(r['metrics']['head_ratio'], r['metrics']['trunk_forward'], baseline)
                scored.append((h / 0.07 + t / 12.0, r))
            worst_item = max(scored, key=lambda x: x[0])[1]
            p.annotate_frame(base_item, baseline, 'BASELINE', p.OUT / f'{src["id"]}_baseline.jpg')
            p.annotate_frame(worst_item, baseline, 'WORST', p.OUT / f'{src["id"]}_worst.jpg')

    payload = {'downloads': download_log, 'videos': summaries}
    (p.OUT / 'summary.json').write_text(json.dumps(payload, indent=2), encoding='utf-8')
    if csv_rows:
        keys = ['video', 't_ms', 'head_delta', 'trunk_delta', 'raw_bad', 'smooth_head_delta', 'smooth_trunk_delta', 'smooth_bad', 'hys_bad', 'severe', 'side_ratio']
        with (p.OUT / 'timeseries.csv').open('w', newline='', encoding='utf-8') as f:
            w = csv.DictWriter(f, fieldnames=keys)
            w.writeheader()
            for row in csv_rows:
                w.writerow({k: row.get(k, '') for k in keys})
    print(json.dumps(payload, indent=2))

    processed = [s for s in summaries if 'error' not in s]
    if not processed:
        print('FAIL: no video produced a valid personal baseline', file=sys.stderr)
        sys.exit(2)
    if not any(s['side_valid_pct'] >= 70 for s in processed):
        print('FAIL: none of the videos had >=70% valid side-view coverage', file=sys.stderr)
        sys.exit(3)


if __name__ == '__main__':
    main()
