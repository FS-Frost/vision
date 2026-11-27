package org.frost.vision.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Vistas superpuestas al AR: mirilla y guía de cobertura por direcciones. */
final class Overlays {
  private Overlays() {}

  /** Mirilla en el centro de la vista: blanca sin profundidad fiable, amarilla en superficie lisa, verde en buen punto para ancla. */
  static final class Crosshair extends View {
    /** Nivel de org.frost.vision.core.AnchorHint (0 sin dato, 1 liso, 2 bueno). */
    volatile int level;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    Crosshair(Context c) {
      super(c);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(Ui.dp(c, 2));
    }

    @Override
    protected void onDraw(Canvas c) {
      float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Ui.dp(getContext(), 18);
      p.setColor(0x99000000);
      p.setStrokeWidth(Ui.dp(getContext(), 4));
      draw(c, cx, cy, r);
      p.setColor(level == 2 ? 0xFF3FB950 : level == 1 ? 0xFFF2CC3C : 0xFFFFFFFF);
      p.setStrokeWidth(Ui.dp(getContext(), 2));
      draw(c, cx, cy, r);
    }

    private void draw(Canvas c, float cx, float cy, float r) {
      c.drawCircle(cx, cy, r, p);
      c.drawLine(cx - r * 1.6f, cy, cx - r * 0.4f, cy, p);
      c.drawLine(cx + r * 0.4f, cy, cx + r * 1.6f, cy, p);
      c.drawLine(cx, cy - r * 1.6f, cx, cy - r * 0.4f, p);
      c.drawLine(cx, cy + r * 0.4f, cx, cy + r * 1.6f, p);
    }
  }

  /** Anillo de 12 sectores (30°): direcciones horizontales ya vistas. */
  static final class Coverage extends View {
    static final int BINS = 12;
    final boolean[] seen = new boolean[BINS];
    volatile float heading;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    Coverage(Context c) { super(c); }

    /** Marca la dirección de la cámara (ángulo en radianes en el plano del suelo). */
    void mark(double yaw) {
      heading = (float) yaw;
      int b = (int) Math.floor(((yaw % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI)) / (2 * Math.PI) * BINS) % BINS;
      seen[b] = true;
      postInvalidate();
    }

    float fraction() {
      int n = 0;
      for (boolean s : seen) if (s) n++;
      return n / (float) BINS;
    }

    void reset() {
      java.util.Arrays.fill(seen, false);
      postInvalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
      float s = Math.min(getWidth(), getHeight()), pad = Ui.dp(getContext(), 6);
      RectF r = new RectF(pad, pad, s - pad, s - pad);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(Ui.dp(getContext(), 7));
      for (int i = 0; i < BINS; i++) {
        p.setColor(seen[i] ? 0xFF3FB950 : 0x55FFFFFF);
        // yaw 0 = mirando a -Z (arriba en el anillo); los ángulos crecen en sentido antihorario.
        float start = -90 - (i + 1) * 30f + 2;
        c.drawArc(r, start, 26, false, p);
      }
      p.setStyle(Paint.Style.FILL);
      p.setColor(0xFFFFFFFF);
      float cx = s / 2, cy = s / 2, len = s / 2 - pad * 3;
      double a = -Math.PI / 2 - heading;
      c.drawCircle(cx + (float) Math.cos(a) * len, cy + (float) Math.sin(a) * len, Ui.dp(getContext(), 4), p);
    }
  }
}
