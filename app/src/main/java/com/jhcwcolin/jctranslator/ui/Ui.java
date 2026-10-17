package com.jhcwcolin.jctranslator.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jhcwcolin.jctranslator.Prefs;

/**
 * Tiny widget toolkit for an e-ink friendly look: pure black on white (or inverted), square
 * boxes with thick borders, no shadows, no gray fills, no animations. Pressed / selected states
 * simply invert the colours, disabled controls get a dashed border.
 */
public final class Ui {

    private Ui() {
    }

    public static int fg() {
        return Prefs.dark() ? Color.WHITE : Color.BLACK;
    }

    public static int bg() {
        return Prefs.dark() ? Color.BLACK : Color.WHITE;
    }

    public static int hint() {
        return Prefs.dark() ? 0xFFAAAAAA : 0xFF666666;
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable box(Context c, int fill, int stroke, float strokeDp, boolean dashed) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, Prefs.cornerDp()));
        int w = Math.max(1, dp(c, strokeDp));
        if (dashed) {
            d.setStroke(w, stroke, dp(c, 6), dp(c, 4));
        } else {
            d.setStroke(w, stroke);
        }
        return d;
    }

    /** Plain bordered box (used for panels and text areas). */
    public static GradientDrawable panel(Context c, float strokeDp) {
        return box(c, bg(), fg(), strokeDp, false);
    }

    private static StateListDrawable buttonBackground(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{-android.R.attr.state_enabled}, box(c, bg(), fg(), Prefs.borderDp(), true));
        s.addState(new int[]{android.R.attr.state_pressed}, box(c, fg(), fg(), Prefs.borderDp(), false));
        s.addState(new int[]{android.R.attr.state_selected}, box(c, fg(), fg(), Prefs.borderDp(), false));
        s.addState(new int[0], box(c, bg(), fg(), Prefs.borderDp(), false));
        return s;
    }

    private static ColorStateList buttonTextColors() {
        return new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_pressed},
                        new int[]{android.R.attr.state_selected},
                        new int[0]},
                new int[]{fg(), bg(), bg(), fg()});
    }

    public static TextView button(Context c, String text, int sp, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(buttonTextColors());
        t.setBackground(buttonBackground(c));
        t.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        t.setMinHeight(dp(c, Prefs.buttonHeightDp()));
        t.setMinWidth(dp(c, 64));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(l);
        return t;
    }

    /** Lucide icon (vector drawable, black strokes) tinted with the current foreground colour. */
    public static ImageView icon(Context c, int res, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(fg()));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** Square icon-only button; looks and behaves like {@link #button} (inverts when pressed). */
    public static ImageView iconButton(Context c, int res, String description, int iconDp, View.OnClickListener l) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(buttonTextColors());
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setBackground(buttonBackground(c));
        int side = Math.max(dp(c, 48), dp(c, Prefs.buttonHeightDp()));
        int pad = Math.max(dp(c, 6), (side - dp(c, iconDp)) / 2);
        v.setPadding(pad, pad, pad, pad);
        v.setMinimumWidth(side);
        v.setMinimumHeight(side);
        v.setContentDescription(description);
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(l);
        return v;
    }

    public static TextView label(Context c, String text, int sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(fg());
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    public static void styleEdit(Context c, EditText e, int sp) {
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        e.setTextColor(fg());
        e.setHintTextColor(hint());
        e.setBackground(panel(c, Prefs.borderDp()));
        e.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setLineSpacing(0, Prefs.lineSpacing());
        if (Prefs.bold()) {
            e.setTypeface(Typeface.DEFAULT_BOLD);
        }
    }

    public static View divider(Context c, float heightDp) {
        View v = new View(c);
        v.setBackgroundColor(fg());
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, heightDp))));
        return v;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams lpMargins(int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(l, t, r, b);
        return p;
    }
}
