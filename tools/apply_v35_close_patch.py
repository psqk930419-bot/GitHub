from pathlib import Path

path = Path('ergoapp/src/main/java/com/kimwonyup/ergoangle/MainActivity.kt')
text = path.read_text(encoding='utf-8')
old = '''        onboardingPrimaryButton.setOnClickListener { finishOnboardingAndPrepare() }\n'''
new = '''        onboardingPrimaryButton.setOnClickListener { finishOnboardingAndPrepare() }\n        findViewById<Button>(R.id.onboardingCloseButton).setOnClickListener { onboardingOverlay.visibility = View.GONE }\n'''
if old not in text:
    raise SystemExit('V3.5 onboarding primary listener anchor not found')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Applied V3.5 onboarding close behavior')
