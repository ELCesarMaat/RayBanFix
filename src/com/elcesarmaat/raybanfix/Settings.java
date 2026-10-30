package com.elcesarmaat.raybanfix;

import android.content.Context;
import android.content.SharedPreferences;

final class Settings {

    private static final String PREFS = "settings";
    private static final String KEY_SAVE_FIXED = "save_fixed";
    private static final String KEY_ASKED_MEDIA_ACCESS = "asked_media_access";

    private Settings() {
    }

    static boolean askedMediaAccess(Context context) {
        return prefs(context).getBoolean(KEY_ASKED_MEDIA_ACCESS, false);
    }

    static void setAskedMediaAccess(Context context) {
        prefs(context).edit().putBoolean(KEY_ASKED_MEDIA_ACCESS, true).apply();
    }

    static boolean saveFixed(Context context) {
        return prefs(context).getBoolean(KEY_SAVE_FIXED, false);
    }

    static void setSaveFixed(Context context, boolean save) {
        prefs(context).edit().putBoolean(KEY_SAVE_FIXED, save).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
