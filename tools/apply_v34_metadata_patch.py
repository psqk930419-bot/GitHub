from pathlib import Path

manifest = Path('ergoapp/src/main/AndroidManifest.xml')
text = manifest.read_text(encoding='utf-8')
text = text.replace('android:label="ErgoAngle Desk 3.3"', 'android:label="ErgoAngle Desk 3.4"')
manifest.write_text(text, encoding='utf-8')
print('Applied V3.4 app label')
