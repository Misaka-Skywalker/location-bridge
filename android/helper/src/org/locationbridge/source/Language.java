package org.locationbridge.source;

import android.content.Context;

/** Centralized UI translations; Chinese is the default for existing installations. */
final class Language {
    private static final String PREFS = "location_bridge_language", KEY = "language";
    private static boolean english;
    static void init(Context context) { english = "en".equals(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "zh")); }
    static void set(Context context, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value ? "en" : "zh").apply();
        english = value;
    }
    static boolean isEnglish() { return english; }
    static String tr(String chinese) { return LanguageCatalog.translate(chinese, english); }
}
