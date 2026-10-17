package com.jhcwcolin.jctranslator.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;

import com.jhcwcolin.jctranslator.Prefs;

/** Shared window setup: orientation setting, colours, no transition animations. */
public abstract class BaseActivity extends Activity {

    private int builtStamp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        applyWindow();
        builtStamp = Prefs.stamp();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onResume() {
        super.onResume();
        if (getRequestedOrientation() != Prefs.requestedOrientation()) {
            setRequestedOrientation(Prefs.requestedOrientation());
        }
        if (builtStamp != Prefs.stamp()) {
            builtStamp = Prefs.stamp();
            recreate();
        }
    }

    /** Subclasses that rebuild their own UI after a setting change call this to skip {@code recreate()}. */
    protected void markBuilt() {
        builtStamp = Prefs.stamp();
    }

    /** Applies orientation, window colours and system bar style from the current settings. */
    @SuppressWarnings("deprecation")
    protected void applyWindow() {
        setRequestedOrientation(Prefs.requestedOrientation());
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Ui.bg()));
        w.setStatusBarColor(Ui.bg());
        w.setNavigationBarColor(Ui.bg());
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(Prefs.dark() ? (flags & ~light) : (flags | light));
    }

    protected void go(Class<?> cls) {
        startActivity(new Intent(this, cls));
        overridePendingTransition(0, 0);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    protected void hideKeyboard() {
        View v = getCurrentFocus();
        if (v == null) {
            v = getWindow().getDecorView();
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }
}
