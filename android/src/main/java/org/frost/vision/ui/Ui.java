package org.frost.vision.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Vistas creadas por código (sin layouts XML ni AndroidX), con la paleta de la web. */
final class Ui {
  static final int BG = 0xFF0F1115, PANEL = 0xE6181B22, TEXT = 0xFFE6E8EE, MUTED = 0xFF8B93A3,
      ACCENT = 0xFF2F6FEB, DANGER = 0xFFDA3633, OK = 0xFF3FB950, BTN = 0xFF2A2F3A;

  private Ui() {}

  static int dp(Context c, float v) {
    return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
  }

  static GradientDrawable rounded(int color, float radiusDp, Context c) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(dp(c, radiusDp));
    return g;
  }

  static Button button(Context c, String text, int color, View.OnClickListener l) {
    Button b = new Button(c);
    b.setText(text);
    b.setAllCaps(false);
    b.setTextColor(Color.WHITE);
    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    b.setBackground(rounded(color, 10, c));
    b.setMinHeight(dp(c, 44));
    b.setMinimumHeight(dp(c, 44));
    b.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
    b.setOnClickListener(l);
    return b;
  }

  static TextView label(Context c, String text, float sp, int color, boolean bold) {
    TextView t = new TextView(c);
    t.setText(text);
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    t.setTextColor(color);
    if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
    return t;
  }

  static LinearLayout column(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  static LinearLayout panel(Context c) {
    LinearLayout l = column(c);
    l.setBackground(rounded(PANEL, 12, c));
    int p = dp(c, 12);
    l.setPadding(p, p, p, p);
    return l;
  }

  static LinearLayout.LayoutParams weight(float w) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    return lp;
  }

  static LinearLayout.LayoutParams margins(Context c, int l, int t, int r, int b) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
    return lp;
  }

  /** Fila de botones que reparte el ancho y deja un hueco entre ellos. */
  static LinearLayout buttonRow(Context c, Button... buttons) {
    LinearLayout r = row(c);
    for (int i = 0; i < buttons.length; i++) {
      LinearLayout.LayoutParams lp = weight(1);
      if (i > 0) lp.leftMargin = dp(c, 8);
      r.addView(buttons[i], lp);
    }
    return r;
  }

  static String meters(float m) {
    return String.format(java.util.Locale.US, "%.2f m", m).replace('.', ',');
  }
}
