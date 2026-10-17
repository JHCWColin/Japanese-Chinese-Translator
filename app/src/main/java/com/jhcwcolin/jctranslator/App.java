package com.jhcwcolin.jctranslator;

import android.app.Application;
import android.content.ComponentCallbacks2;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // UI hidden (20), or the system is running low while we are in the foreground (10 / 15):
        // give the ONNX sessions back; they are recreated on the next translation.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == 10 || level == 15) {
            TranslatorController.get(this).releaseModel();
        }
    }
}
