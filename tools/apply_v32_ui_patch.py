from pathlib import Path

p = Path('ergoapp/src/main/java/com/kimwonyup/ergoangle/MainActivity.kt')
s = p.read_text(encoding='utf-8')

old = '''"상세: 머리 전방 +%d%% · 상체 기준대비 +%.0f° · 현재 %.0f°",
                reading.headForwardDeltaPct,
                reading.trunkDeltaDeg,
                reading.trunkAbsoluteDeg'''
new = '''"상세: 머리 전방 +%d%% · 상체 전방변화 +%.0f° · 현재 전방각 %+.0f°",
                reading.headForwardDeltaPct,
                reading.trunkDeltaDeg,
                reading.trunkForwardDeg'''
if old not in s:
    raise SystemExit('V3.2 detail UI patch target not found')
s = s.replace(old, new, 1)

old2 = '''appendLine("현재 상체 절대 기울기: %.0f°".format(r.trunkAbsoluteDeg))'''
new2 = '''appendLine("현재 상체 전방각: %+.0f°".format(r.trunkForwardDeg))'''
if old2 not in s:
    raise SystemExit('V3.2 report UI patch target not found')
s = s.replace(old2, new2, 1)

old3 = '''"개인 기준 설정 완료 · 기준 상체 %.0f°", update.baseline.trunkAbsoluteDeg'''
new3 = '''"개인 기준 설정 완료 · 기준 상체 전방각 %+.0f°", update.baseline.trunkForwardDeg'''
if old3 in s:
    s = s.replace(old3, new3, 1)

p.write_text(s, encoding='utf-8')
print('Applied V3.2 signed-trunk UI patch')
