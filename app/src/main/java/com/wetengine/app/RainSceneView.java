package com.wetengine.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public class RainSceneView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double relativeAngleDeg = 0;
    private double recommendedTiltDeg = 0;
    private double relativeRainSpeed = 0;
    private double rainAmount = 0;
    private int dryScore = 100;
    private final float[] exposure = new float[]{0,0,0,0,0};

    public RainSceneView(Context context) { super(context); init(); }
    public RainSceneView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setData(double relativeAngleDeg, double tiltDeg, double relativeRainSpeed,
                        double rainAmount, int dryScore, double[] segmentExposure) {
        this.relativeAngleDeg = relativeAngleDeg;
        this.recommendedTiltDeg = tiltDeg;
        this.relativeRainSpeed = relativeRainSpeed;
        this.rainAmount = rainAmount;
        this.dryScore = dryScore;
        if (segmentExposure != null) {
            for (int i = 0; i < Math.min(5, segmentExposure.length); i++) {
                exposure[i] = (float) Math.max(0, Math.min(1, segmentExposure[i]));
            }
        }
        invalidate();
    }

    private int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.rgb((int)(ar + (br-ar)*t), (int)(ag + (bg-ag)*t), (int)(ab + (bb-ab)*t));
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(9, 31, 51));
        c.drawRoundRect(new RectF(0,0,w,h), 34,34,paint);

        // Soft halo behind the person.
        paint.setColor(Color.argb(28, 87, 183, 255));
        c.drawCircle(w*0.5f, h*0.50f, Math.min(w,h)*0.33f, paint);

        // Animated rain. Lateral direction reflects which side rain arrives from.
        float phase = (SystemClock.uptimeMillis() % 2600L) / 2600f;
        double relRad = Math.toRadians(relativeAngleDeg);
        float lateral = (float)(-Math.sin(relRad));
        float slant = lateral * (18f + (float)Math.min(42, relativeRainSpeed * 5.5));
        int drops = 18 + (int)Math.min(22, Math.round(rainAmount * 2.2));
        stroke.setColor(Color.argb(115, 121, 205, 255));
        stroke.setStrokeWidth(3.0f);
        for (int i=0; i<drops; i++) {
            float seedX = ((i * 83) % 101) / 101f;
            float seedY = ((i * 47) % 97) / 97f;
            float y = ((seedY + phase * (0.65f + (i%5)*0.07f)) % 1.15f) * h - h*0.08f;
            float x = (seedX * 1.15f - 0.075f) * w + slant * phase * 1.7f;
            float len = 18f + (i%4)*5f;
            c.drawLine(x, y, x + slant*0.34f, y + len, stroke);
        }

        // Direction ring.
        stroke.setStrokeWidth(2f);
        stroke.setColor(Color.argb(65,255,255,255));
        c.drawCircle(w*0.5f, h*0.46f, Math.min(w,h)*0.29f, stroke);

        // Person, colored by estimated exposure.
        float cx = w*0.50f;
        float headY = h*0.47f;
        float scale = Math.min(w/380f, h/300f);
        int safe = Color.rgb(93, 211, 176);
        int wet = Color.rgb(255, 112, 94);

        paint.setColor(mix(safe, wet, exposure[0]));
        c.drawCircle(cx, headY, 14*scale, paint);
        paint.setColor(mix(safe, wet, exposure[1]));
        c.drawRoundRect(new RectF(cx-18*scale, headY+18*scale, cx+18*scale, headY+78*scale), 12*scale,12*scale,paint);
        stroke.setStrokeWidth(10*scale);
        stroke.setColor(mix(safe, wet, exposure[2]));
        c.drawLine(cx-9*scale, headY+77*scale, cx-11*scale, headY+112*scale, stroke);
        c.drawLine(cx+9*scale, headY+77*scale, cx+11*scale, headY+112*scale, stroke);
        stroke.setColor(mix(safe, wet, exposure[3]));
        c.drawLine(cx-11*scale, headY+112*scale, cx-14*scale, headY+145*scale, stroke);
        c.drawLine(cx+11*scale, headY+112*scale, cx+14*scale, headY+145*scale, stroke);
        stroke.setStrokeWidth(8*scale);
        stroke.setColor(mix(safe, wet, exposure[4]));
        c.drawLine(cx-20*scale, headY+147*scale, cx-7*scale, headY+147*scale, stroke);
        c.drawLine(cx+7*scale, headY+147*scale, cx+20*scale, headY+147*scale, stroke);

        // Umbrella. Visual side tilt uses lateral component of relative incoming direction.
        float visualTilt = (float)(recommendedTiltDeg * Math.sin(relRad));
        float pivotX = cx;
        float pivotY = headY + 75*scale;
        c.save();
        c.rotate(visualTilt, pivotX, pivotY);
        stroke.setStrokeWidth(5*scale);
        stroke.setColor(Color.rgb(215, 233, 247));
        c.drawLine(pivotX, pivotY, pivotX, headY-55*scale, stroke);

        Path canopy = new Path();
        canopy.moveTo(cx-78*scale, headY-47*scale);
        canopy.quadTo(cx, headY-108*scale, cx+78*scale, headY-47*scale);
        canopy.quadTo(cx+39*scale, headY-65*scale, cx, headY-47*scale);
        canopy.quadTo(cx-39*scale, headY-65*scale, cx-78*scale, headY-47*scale);
        canopy.close();
        paint.setColor(Color.rgb(62, 159, 235));
        c.drawPath(canopy, paint);
        stroke.setStrokeWidth(2*scale);
        stroke.setColor(Color.rgb(176, 222, 255));
        c.drawPath(canopy, stroke);
        c.restore();

        // Score pill.
        paint.setColor(Color.argb(225, 255,255,255));
        RectF pill = new RectF(18*scale, 17*scale, 116*scale, 54*scale);
        c.drawRoundRect(pill, 19*scale,19*scale,paint);
        paint.setColor(Color.rgb(9,31,51));
        paint.setTextSize(13*scale);
        paint.setFakeBoldText(true);
        c.drawText("DRY  " + dryScore, 32*scale, 41*scale, paint);
        paint.setFakeBoldText(false);

        postInvalidateOnAnimation();
    }
}
