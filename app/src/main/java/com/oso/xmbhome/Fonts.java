package com.oso.xmbhome;

import android.content.Context;
import android.graphics.Typeface;

/**
 * The UI font: Zen Kaku Gothic New (SIL Open Font License, assets/fonts/ZenKakuGothicNew-OFL.txt),
 * the closest free match to the XMB's lettering, trimmed to Latin. A Rodin copy of your own in
 * assets/fonts/ (Rodin-Regular and Rodin-Medium, .otf or .ttf) takes priority; M PLUS 1p is the
 * last fallback.
 */
final class Fonts {
    private static Typeface regular, medium, boot;

    static Typeface regular(Context c) {
        if (regular == null) regular = load(c, Typeface.NORMAL,
                "fonts/Rodin-Regular.otf", "fonts/Rodin-Regular.ttf",
                "fonts/ZenKakuGothicNew-Medium.ttf", "fonts/MPLUS1p-Regular.ttf");
        return regular;
    }

    static Typeface medium(Context c) {
        if (medium == null) medium = load(c, Typeface.BOLD,
                "fonts/Rodin-Medium.otf", "fonts/Rodin-Medium.ttf",
                "fonts/ZenKakuGothicNew-Bold.ttf", "fonts/MPLUS1p-Medium.ttf");
        return medium;
    }

    /**
     * The startup text's own font: assets/fonts/boot_text.ttf or boot_text.otf. Without one it
     * uses the regular UI font.
     */
    static Typeface boot(Context c) {
        if (boot == null) boot = load(c, Typeface.NORMAL,
                "fonts/boot_text.ttf", "fonts/boot_text.otf",
                "fonts/Rodin-Regular.otf", "fonts/Rodin-Regular.ttf",
                "fonts/ZenKakuGothicNew-Medium.ttf", "fonts/MPLUS1p-Regular.ttf");
        return boot;
    }

    /** The first of these asset paths that loads; a system sans-serif if none do. */
    private static Typeface load(Context c, int fallbackStyle, String... paths) {
        for (String path : paths) {
            try {
                return Typeface.createFromAsset(c.getAssets(), path);
            } catch (RuntimeException e) {
                // not there or unreadable: try the next one
            }
        }
        return Typeface.create(Typeface.SANS_SERIF, fallbackStyle);
    }

    private Fonts() {}
}
