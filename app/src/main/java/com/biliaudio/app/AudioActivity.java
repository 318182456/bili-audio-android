package com.biliaudio.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

abstract class AudioActivity extends Activity {
    enum Appearance {
        SYSTEM("跟随系统", 0), LIGHT("手动 · 浅色", Configuration.UI_MODE_NIGHT_NO),
        DARK("手动 · 深色", Configuration.UI_MODE_NIGHT_YES);

        final String label;
        final int nightMode;

        Appearance(String label, int nightMode) {
            this.label = label;
            this.nightMode = nightMode;
        }
    }

    static Appearance appearance(Context context) {
        String value = context.getSharedPreferences("appearance", MODE_PRIVATE)
                .getString("mode", Appearance.SYSTEM.name());
        return Appearance.valueOf(value);
    }

    void changeAppearance(Appearance mode) {
        if (appearance(this) != mode) {
            getSharedPreferences("appearance", MODE_PRIVATE).edit().putString("mode", mode.name()).apply();
            recreate();
        }
    }

    @Override protected void attachBaseContext(Context base) {
        Appearance mode = appearance(base);
        if (mode == Appearance.SYSTEM) {
            super.attachBaseContext(base);
        } else {
            Configuration config = new Configuration();
            config.uiMode = (base.getResources().getConfiguration().uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | mode.nightMode;
            super.attachBaseContext(base);
            applyOverrideConfiguration(config);
        }
    }
}
