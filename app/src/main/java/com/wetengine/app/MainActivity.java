package com.wetengine.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements LocationListener, SensorEventListener {
    private static final int NAVY = Color.rgb(9,31,51);
    private static final int BLUE = Color.rgb(62,159,235);
    private static final int BG = Color.rgb(244,247,250);
    private static final int MUTED = Color.rgb(103,118,130);
    private static final int SAFE = Color.rgb(57,174,141);
    private static final int WARN = Color.rgb(237,164,61);
    private static final int DANGER = Color.rgb(236,92,78);

    private RainSceneView scene;
    private TextView guide, details, scoreText, actionText, weatherRain, weatherWind, weatherWalk;
    private TextView status, heightValue, umbrellaValue, sourceText;
    private final TextView[] riskLabels = new TextView[5];
    private final ProgressBar[] riskBars = new ProgressBar[5];
    private LinearLayout nowPanel, profilePanel;
    private TextView tabNow, tabProfile;

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private float deviceHeading = 0f;
    private Location lastLocation;
    private double windFrom = 0, windSpeed = 0, rain = 0;
    private boolean weatherLoaded = false;
    private long lastWeatherFetchMs = 0L;
    private String previousArrow = "";
    private int userHeightCm = 175;
    private int umbrellaDiameterCm = 105;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final String[] bodyNames = {"머리", "몸통", "허벅지", "종아리", "신발"};

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(rounded(Color.WHITE, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0); c.setLayoutParams(lp);
        c.setElevation(dp(1));
        return c;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(32));

        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("WETENGINE", 21, NAVY, true);
        TextView version = text("  v0.4", 12, MUTED, true);
        brand.addView(title); brand.addView(version); root.addView(brand);
        TextView subtitle = text("비가 오는 방향을 행동으로 바꿉니다", 13, MUTED, false);
        subtitle.setPadding(0, dp(2), 0, dp(8)); root.addView(subtitle);

        LinearLayout hero = card();
        hero.setPadding(dp(8), dp(8), dp(8), dp(16));
        hero.setBackground(rounded(NAVY, 24));
        scene = new RainSceneView(this);
        hero.addView(scene, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        guide = text("위치 권한을 허용하고 시작하세요", 23, Color.WHITE, true);
        guide.setGravity(Gravity.CENTER); guide.setPadding(dp(12), 0, dp(12), dp(4)); hero.addView(guide);
        details = text("상대 빗방향 계산 대기", 13, Color.rgb(178,205,225), false);
        details.setGravity(Gravity.CENTER); hero.addView(details);

        LinearLayout scoreRow = new LinearLayout(this); scoreRow.setGravity(Gravity.CENTER); scoreRow.setPadding(0, dp(13), 0, 0);
        TextView scoreLabel = text("DRY SCORE", 12, Color.rgb(178,205,225), true);
        scoreText = text(" --", 27, Color.WHITE, true);
        scoreRow.addView(scoreLabel); scoreRow.addView(scoreText); hero.addView(scoreRow);
        root.addView(hero);

        LinearLayout tabs = new LinearLayout(this); tabs.setPadding(0, dp(14), 0, 0);
        tabNow = makeTab("NOW", true); tabProfile = makeTab("PROFILE", false);
        tabs.addView(tabNow, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, dp(46), 1); second.setMargins(dp(8),0,0,0);
        tabs.addView(tabProfile, second); root.addView(tabs);
        tabNow.setOnClickListener(v -> selectTab(true));
        tabProfile.setOnClickListener(v -> selectTab(false));

        nowPanel = new LinearLayout(this); nowPanel.setOrientation(LinearLayout.VERTICAL);
        profilePanel = new LinearLayout(this); profilePanel.setOrientation(LinearLayout.VERTICAL); profilePanel.setVisibility(View.GONE);
        root.addView(nowPanel); root.addView(profilePanel);

        buildNowPanel();
        buildProfilePanel();

        scroll.addView(root); setContentView(scroll);
        scene.setData(0, 0, 0, 0, 100, new double[]{0,0,0,0,0});
    }

    private TextView makeTab(String label, boolean active) {
        TextView t = text(label, 13, active ? Color.WHITE : NAVY, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(active ? NAVY : Color.WHITE, 15));
        return t;
    }

    private void selectTab(boolean now) {
        nowPanel.setVisibility(now ? View.VISIBLE : View.GONE);
        profilePanel.setVisibility(now ? View.GONE : View.VISIBLE);
        tabNow.setTextColor(now ? Color.WHITE : NAVY); tabNow.setBackground(rounded(now ? NAVY : Color.WHITE, 15));
        tabProfile.setTextColor(now ? NAVY : Color.WHITE); tabProfile.setBackground(rounded(now ? Color.WHITE : NAVY, 15));
    }

    private void buildNowPanel() {
        LinearLayout weatherCard = card();
        TextView h = text("현재 조건", 16, NAVY, true); weatherCard.addView(h);
        LinearLayout stats = new LinearLayout(this); stats.setPadding(0, dp(12), 0, 0);
        weatherRain = statBox(stats, "강수", "--");
        weatherWind = statBox(stats, "바람", "--");
        weatherWalk = statBox(stats, "보행", "--");
        weatherCard.addView(stats);
        sourceText = text("날씨 데이터 대기", 11, MUTED, false); sourceText.setPadding(0,dp(10),0,0); weatherCard.addView(sourceText);
        nowPanel.addView(weatherCard);

        LinearLayout decision = card();
        TextView dh = text("지금의 한 줄 판단", 16, NAVY, true); decision.addView(dh);
        actionText = text("위치와 날씨를 받으면 표시됩니다.", 18, NAVY, true);
        actionText.setPadding(0, dp(10), 0, 0); decision.addView(actionText); nowPanel.addView(decision);

        LinearLayout exposureCard = card();
        exposureCard.addView(text("직접강우 노출", 16, NAVY, true));
        for (int i=0; i<bodyNames.length; i++) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(9), 0, 0);
            TextView name = text(bodyNames[i], 13, NAVY, false);
            row.addView(name, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100); bar.setProgress(0); bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(232,237,241)));
            bar.setProgressTintList(ColorStateList.valueOf(SAFE));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(8), 1); blp.setMargins(dp(8),0,dp(10),0); row.addView(bar, blp);
            TextView risk = text("낮음", 12, SAFE, true); risk.setGravity(Gravity.RIGHT);
            row.addView(risk, new LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT));
            riskBars[i] = bar; riskLabels[i] = risk; exposureCard.addView(row);
        }
        TextView caveat = text("※ 젖을 확률이 아니라 우산 그림자와 상대 빗방향으로 계산한 직접강우 지표입니다.", 11, MUTED, false);
        caveat.setPadding(0, dp(12), 0, 0); exposureCard.addView(caveat); nowPanel.addView(exposureCard);

        LinearLayout control = card();
        Button start = new Button(this); start.setText("실시간 안내 시작"); start.setTextSize(15); start.setTextColor(Color.WHITE); start.setBackgroundTintList(ColorStateList.valueOf(BLUE));
        start.setOnClickListener(v -> startTracking()); control.addView(start);
        Button refresh = new Button(this); refresh.setText("날씨 새로고침"); refresh.setTextSize(14);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); rlp.setMargins(0,dp(8),0,0);
        refresh.setLayoutParams(rlp); refresh.setOnClickListener(v -> { if (lastLocation != null) fetchWeather(lastLocation); }); control.addView(refresh);
        status = text("GPS: 대기", 12, MUTED, false); status.setPadding(0, dp(10), 0, 0); control.addView(status);
        nowPanel.addView(control);
    }

    private TextView statBox(LinearLayout parent, String label, String initial) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER);
        box.setPadding(dp(6), dp(10), dp(6), dp(10)); box.setBackground(rounded(Color.rgb(246,249,251), 14));
        TextView l = text(label, 11, MUTED, false); TextView v = text(initial, 16, NAVY, true); v.setGravity(Gravity.CENTER); l.setGravity(Gravity.CENTER);
        box.addView(l); box.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); lp.setMargins(dp(3),0,dp(3),0); parent.addView(box, lp);
        return v;
    }

    private void buildProfilePanel() {
        LinearLayout settings = card();
        settings.addView(text("내 우산 프로필", 17, NAVY, true));
        TextView desc = text("몸과 우산 크기를 바꾸면 보호 범위를 즉시 다시 계산합니다.", 12, MUTED, false);
        desc.setPadding(0, dp(4), 0, dp(12)); settings.addView(desc);

        LinearLayout hRow = new LinearLayout(this); hRow.setGravity(Gravity.CENTER_VERTICAL);
        hRow.addView(text("키", 14, NAVY, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        heightValue = text(userHeightCm + " cm", 14, BLUE, true); hRow.addView(heightValue); settings.addView(hRow);
        SeekBar hs = new SeekBar(this); hs.setMax(50); hs.setProgress(userHeightCm - 150); settings.addView(hs);
        hs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { userHeightCm = 150+p; heightValue.setText(userHeightCm+" cm"); updateWetEngine(); }
            public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
        });

        LinearLayout uRow = new LinearLayout(this); uRow.setGravity(Gravity.CENTER_VERTICAL); uRow.setPadding(0,dp(10),0,0);
        uRow.addView(text("우산 지름", 14, NAVY, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        umbrellaValue = text(umbrellaDiameterCm + " cm", 14, BLUE, true); uRow.addView(umbrellaValue); settings.addView(uRow);
        SeekBar us = new SeekBar(this); us.setMax(60); us.setProgress(umbrellaDiameterCm - 80); settings.addView(us);
        us.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { umbrellaDiameterCm = 80+p; umbrellaValue.setText(umbrellaDiameterCm+" cm"); updateWetEngine(); }
            public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
        });
        profilePanel.addView(settings);

        LinearLayout explain = card();
        explain.addView(text("Dry Score는 무엇인가요?", 16, NAVY, true));
        TextView t = text("100에 가까울수록 현재 우산 크기와 권장 방향에서 하체의 직접강우 노출이 낮다는 뜻입니다. 실제 젖을 확률이나 방수 성능을 의미하지 않습니다.", 13, MUTED, false);
        t.setLineSpacing(0,1.25f); t.setPadding(0,dp(8),0,0); explain.addView(t);
        profilePanel.addView(explain);
    }

    private void startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
            return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500, 1f, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000, 2f, this);
            if (rotationSensor != null) sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
            status.setText("GPS: 실시간 추적 중");
            Location cached = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (cached == null) cached = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (cached != null) onLocationChanged(cached);
        } catch (SecurityException e) { status.setText("GPS 권한 오류: " + e.getMessage()); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 7 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startTracking();
        else status.setText("위치 권한이 필요합니다.");
    }

    @Override public void onLocationChanged(Location l) {
        lastLocation = l;
        status.setText(String.format(Locale.KOREA, "GPS: %.5f, %.5f · 정확도 ±%.0fm", l.getLatitude(), l.getLongitude(), l.getAccuracy()));
        long now = System.currentTimeMillis();
        if (!weatherLoaded || now - lastWeatherFetchMs > 5*60*1000L) fetchWeather(l);
        updateWetEngine();
    }

    private void fetchWeather(Location l) {
        final double lat = l.getLatitude(), lon = l.getLongitude();
        lastWeatherFetchMs = System.currentTimeMillis();
        sourceText.setText("날씨를 불러오는 중…");
        executor.execute(() -> {
            try {
                String u = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f&current=precipitation,wind_speed_10m,wind_direction_10m&wind_speed_unit=ms",
                    lat, lon);
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(10000); c.setRequestProperty("User-Agent", "WetEngine/0.4");
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line; while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject current = new JSONObject(sb.toString()).getJSONObject("current");
                double p = current.optDouble("precipitation", 0);
                double ws = current.optDouble("wind_speed_10m", 0);
                double wd = current.optDouble("wind_direction_10m", 0);
                String time = current.optString("time", "현재");
                runOnUiThread(() -> {
                    rain = p; windSpeed = ws; windFrom = wd; weatherLoaded = true;
                    sourceText.setText("Open-Meteo · " + time + " · 10 m 바람");
                    updateWetEngine();
                });
            } catch (Exception e) {
                runOnUiThread(() -> sourceText.setText("날씨 조회 실패 · " + e.getClass().getSimpleName()));
            }
        });
    }

    private static double norm360(double d) { d %= 360; return d < 0 ? d + 360 : d; }
    private static double signed(double d) { return (d + 540) % 360 - 180; }
    private static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }
    private static double[] vec(double bearing, double speed) {
        double r = Math.toRadians(norm360(bearing)); return new double[]{speed * Math.sin(r), speed * Math.cos(r)};
    }
    private static double bearing(double east, double north) { return norm360(Math.toDegrees(Math.atan2(east, north))); }

    private int riskColor(double dose) {
        if (dose < .1) return SAFE;
        if (dose < 1) return Color.rgb(68,143,213);
        if (dose < 5) return WARN;
        return DANGER;
    }

    private String riskName(double dose) { return dose < .1 ? "낮음" : dose < 1 ? "보통" : dose < 5 ? "높음" : "매우 높음"; }

    private void updateWetEngine() {
        if (lastLocation == null) return;
        double walkSpeed = lastLocation.hasSpeed() && lastLocation.getSpeed() >= 0.4f ? lastLocation.getSpeed() : 1.4;
        double walkBearing = lastLocation.hasBearing() && lastLocation.getSpeed() >= 0.4f ? lastLocation.getBearing() : deviceHeading;
        double[] w = vec(windFrom + 180, windSpeed);
        double[] u = vec(walkBearing, walkSpeed);
        double rx = w[0] - u[0], ry = w[1] - u[1];
        double rh = Math.hypot(rx, ry);
        double incoming = rh > 1e-9 ? bearing(-rx, -ry) : walkBearing;
        double rel = signed(incoming - walkBearing);
        String a, k;
        if (rel >= -22.5 && rel < 22.5) { a="↑"; k="앞"; }
        else if (rel >= 22.5 && rel < 67.5) { a="↗"; k="오른쪽 앞"; }
        else if (rel >= 67.5 && rel < 112.5) { a="→"; k="오른쪽"; }
        else if (rel >= 112.5 && rel < 157.5) { a="↘"; k="오른쪽 뒤"; }
        else if (rel >= -67.5 && rel < -22.5) { a="↖"; k="왼쪽 앞"; }
        else if (rel >= -112.5 && rel < -67.5) { a="←"; k="왼쪽"; }
        else if (rel >= -157.5 && rel < -112.5) { a="↙"; k="왼쪽 뒤"; }
        else { a="↓"; k="뒤"; }

        double rawTilt = Math.toDegrees(Math.atan2(rh, 6.0));
        double recTilt = Math.min(rawTilt, 35.0);
        String level = recTilt < 10 ? "거의 수직" : recTilt < 20 ? "약간" : recTilt < 30 ? "보통" : "강하게";

        double userH = userHeightCm / 100.0;
        double canopyH = userH + .15;
        double radius = umbrellaDiameterCm / 200.0;
        double shift = .55 * Math.sin(Math.toRadians(recTilt));
        double[] frac = {.94,.72,.50,.26,.04}; double[] half = {.11,.18,.16,.09,.12};
        double[] exposed = new double[5];
        for (int i=0;i<5;i++) {
            double z=userH*frac[i], ray=Math.max(0,canopyH-z)*rh/6.0, residual=Math.abs(ray-shift);
            exposed[i]=clamp((residual+half[i]-radius)/(2*half[i]),0,1);
            double dose = exposed[i] * rain;
            int color = riskColor(dose);
            riskBars[i].setProgress((int)Math.round(exposed[i]*100));
            riskBars[i].setProgressTintList(ColorStateList.valueOf(color));
            riskLabels[i].setText(riskName(dose)); riskLabels[i].setTextColor(color);
        }

        double lower = .15*exposed[2] + .35*exposed[3] + .50*exposed[4];
        double rainFactor = rain <= 0 ? 0 : clamp(.25 + rain/5.0, .25, 1.0);
        double sidePenalty = rain > 0 ? clamp((rawTilt-35)/20.0,0,1)*10 : 0;
        int dryScore = (int)Math.round(clamp(100 - 72*lower*rainFactor - sidePenalty, 0, 100));

        guide.setText(k + "으로 " + level + " 기울이세요  " + a);
        details.setText(String.format(Locale.KOREA, "권장 %.0f° · 상대 빗속도 %.1f m/s", recTilt, rh));
        scoreText.setText(" " + dryScore);
        weatherRain.setText(String.format(Locale.KOREA, "%.1f mm", rain));
        weatherWind.setText(String.format(Locale.KOREA, "%.1f m/s", windSpeed));
        weatherWalk.setText(String.format(Locale.KOREA, "%.1f m/s", walkSpeed));

        if (!weatherLoaded) actionText.setText("날씨를 불러오고 있습니다");
        else if (rain <= 0.01) actionText.setText("현재 직접강우는 거의 없습니다");
        else if (dryScore >= 80) actionText.setText("현재 우산으로 보호가 잘 되는 편입니다");
        else if (dryScore >= 60) actionText.setText("우산 방향을 맞추면 하체 노출을 줄일 수 있어요");
        else if (dryScore >= 40) actionText.setText("종아리·신발 직접강우에 주의하세요");
        else actionText.setText("측면비가 강합니다 · 우산 보호에 한계가 있어요");

        scene.setData(rel, recTilt, rh, rain, dryScore, exposed);
        if (!a.equals(previousArrow) && !previousArrow.isEmpty()) guide.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        previousArrow = a;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] R = new float[9], o = new float[3];
            SensorManager.getRotationMatrixFromVector(R, event.values);
            SensorManager.getOrientation(R, o);
            deviceHeading = (float) norm360(Math.toDegrees(o[0]));
            if (lastLocation != null && (!lastLocation.hasBearing() || lastLocation.getSpeed() < 0.4f)) updateWetEngine();
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onStatusChanged(String provider, int statusCode, Bundle extras) {}

    @Override protected void onPause() {
        super.onPause(); if (sensorManager != null) sensorManager.unregisterListener(this);
    }
    @Override protected void onResume() {
        super.onResume(); if (rotationSensor != null) sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
    }
}
