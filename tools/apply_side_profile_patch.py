from pathlib import Path

path = Path('ergoapp/src/main/java/com/kimwonyup/ergoangle/MainActivity.kt')
text = path.read_text(encoding='utf-8')
old = '''    private fun sideProfileHeuristic(pose: List<NormalizedLandmark>): Boolean {
        val shoulderGap = abs(pose[11].x() - pose[12].x())
        val hipGap = abs(pose[23].x() - pose[24].x())
        return (shoulderGap + hipGap) / 2f < 0.13f
    }
'''
new = '''    private fun sideProfileHeuristic(pose: List<NormalizedLandmark>): Boolean {
        val shoulderGap = abs(pose[11].x() - pose[12].x())
        val hipGap = abs(pose[23].x() - pose[24].x())
        val shoulderMidX = (pose[11].x() + pose[12].x()) / 2f
        val shoulderMidY = (pose[11].y() + pose[12].y()) / 2f
        val hipMidX = (pose[23].x() + pose[24].x()) / 2f
        val hipMidY = (pose[23].y() + pose[24].y()) / 2f
        val torsoDx = shoulderMidX - hipMidX
        val torsoDy = shoulderMidY - hipMidY
        val torsoLength = kotlin.math.sqrt(torsoDx * torsoDx + torsoDy * torsoDy)
        if (torsoLength < 1e-5f) return false

        // Scale-normalized gate: camera distance/zoom should not decide whether a side view is valid.
        val projectedBodyWidth = (shoulderGap + hipGap) / 2f
        return projectedBodyWidth / torsoLength < 0.50f
    }
'''
if old not in text:
    raise SystemExit('Expected sideProfileHeuristic block not found')
path.write_text(text.replace(old, new), encoding='utf-8')
print('Applied scale-normalized side-profile heuristic')
