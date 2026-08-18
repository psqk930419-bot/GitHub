package com.wetengine.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private TextView arrow, guide, details, weatherText, exposureText, status;
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private float deviceHeading = 0f;
    private Location lastLocation;
    private double windFrom = 0, windSpeed = 0, rain = 0;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    private TextView tv(String text, int sp) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextSize(sp); v.setPadding(8, 10, 8, 10);
        return v;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28, 24, 28, 36);
        TextView title = tv("WetEngine v0.3.2", 26); title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);
        arrow = tv("·", 84); arrow.setGravity(Gravity.CENTER); root.addView(arrow);
        guide = tv("위치 권한을 허용하고 시작하세요", 22); guide.setGravity(Gravity.CENTER); root.addView(guide);
        details = tv("상대 빗방향 계산 대기", 15); details.setGravity(Gravity.CENTER); root.addView(details);
        weatherText = tv("날씨: 대기", 16); root.addView(weatherText);
        exposureText = tv("직접강우 노출: 대기", 16); root.addView(exposureText);
        status = tv("GPS: 대기", 13); root.addView(status);
        Button start = new Button(this); start.setText("현재 위치 + 실시간 날씨 시작");
        start.setOnClickListener(v -> startTracking()); root.addView(start);
        Button refresh = new Button(this); refresh.setText("날씨 새로고침");
        refresh.setOnClickListener(v -> { if (lastLocation != null) fetchWeather(lastLocation); }); root.addView(refresh);
        TextView note = tv("보행속도가 낮을 때는 휴대폰 방향센서로 진행방향을 보완합니다. 날씨는 Open-Meteo current conditions를 사용합니다.", 12);
        root.addView(note);
        scroll.addView(root); setContentView(scroll);
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
            status.setText("GPS: 추적 중");
        } catch (SecurityException e) { status.setText("GPS 권한 오류: " + e.getMessage()); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 7 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startTracking();
        else status.setText("위치 권한이 필요합니다.");
    }

    @Override public void onLocationChanged(Location l) {
        lastLocation = l;
        status.setText(String.format(Locale.US, "GPS: %.5f, %.5f  ±%.0fm", l.getLatitude(), l.getLongitude(), l.getAccuracy()));
        if (windSpeed == 0 && rain == 0) fetchWeather(l);
        updateWetEngine();
    }

    private void fetchWeather(Location l) {
        final double lat = l.getLatitude(), lon = l.getLongitude();
        executor.execute(() -> {
            try {
                String u = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f&current=precipitation,wind_speed_10m,wind_direction_10m&wind_speed_unit=ms",
                    lat, lon);
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(10000); c.setRequestProperty("User-Agent", "WetEngine/0.3.2");
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line; while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject current = new JSONObject(sb.toString()).getJSONObject("current");
                double p = current.optDouble("precipitation", 0);
                double ws = current.optDouble("wind_speed_10m", 0);
                double wd = current.optDouble("wind_direction_10m", 0);
                runOnUiThread(() -> {
                    rain = p; windSpeed = ws; windFrom = wd;
                    weatherText.setText(String.format(Locale.US, "현재: 강수 %.1f mm · 풍속 %.1f m/s · 풍향 %.0f°", rain, windSpeed, windFrom));
                    updateWetEngine();
                });
            } catch (Exception e) {
                runOnUiThread(() -> weatherText.setText("날씨 조회 실패: " + e.getMessage()));
            }
        });
    }

    private static double norm360(double d) { d %= 360; return d < 0 ? d + 360 : d; }
    private static double signed(double d) { return (d + 540) % 360 - 180; }
    private static double[] vec(double bearing, double speed) {
        double r = Math.toRadians(norm360(bearing)); return new double[]{speed * Math.sin(r), speed * Math.cos(r)};
    }
    private static double bearing(double east, double north) { return norm360(Math.toDegrees(Math.atan2(east, north))); }

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
        arrow.setText(a); guide.setText(k + "으로 " + level + " 기울이세요");
        details.setText(String.format(Locale.US, "권장 %.0f° · 상대 수평빗속도 %.2f m/s · 보행 %.2f m/s", recTilt, rh, walkSpeed));

        double canopyH = 1.90, radius = 0.525, shift = 0.55 * Math.sin(Math.toRadians(recTilt));
        String[] names = {"머리","몸통","허벅지","종아리","신발"};
        double[] frac = {.94,.72,.50,.26,.04}; double[] half = {.11,.18,.16,.09,.12};
        StringBuilder out = new StringBuilder("직접강우 노출\n");
        for (int i=0;i<names.length;i++) {
            double z=1.75*frac[i], ray=(canopyH-z)*rh/6.0, residual=Math.abs(ray-shift);
            double exposed=Math.max(0,Math.min(1,(residual+half[i]-radius)/(2*half[i])));
            double dose=exposed*rain; String risk=dose<.1?"낮음":dose<1?"보통":dose<5?"높음":"매우 높음";
            out.append(names[i]).append(": ").append(risk).append("\n");
        }
        exposureText.setText(out.toString());
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
