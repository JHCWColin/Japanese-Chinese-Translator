package com.jhcwcolin.jctranslator;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

/**
 * Flashes the whole screen black and white a few times to clear e-ink ghosting.
 * Deliberately has no animation of its own: each colour change is one full-screen redraw.
 */
public class RefreshActivity extends Activity {

    private static final int[] SEQUENCE = {Color.BLACK, Color.WHITE, Color.BLACK, Color.WHITE, Color.BLACK, Color.WHITE};
    private static final long STEP_MS = 450;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private View view;
    private int step;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new View(this);
        view.setBackgroundColor(SEQUENCE[0]);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        setContentView(view);
        handler.postDelayed(tick, STEP_MS);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            step++;
            if (step >= SEQUENCE.length) {
                finish();
                return;
            }
            view.setBackgroundColor(SEQUENCE[step]);
            handler.postDelayed(this, STEP_MS);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
