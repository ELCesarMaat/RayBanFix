package com.elcesarmaat.raybanfix;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

final class Ui {

    private Ui() {
    }

    // targetSdk 36 forces edge-to-edge, so screens have to pad themselves away from the system bars.
    @SuppressWarnings("deprecation")
    static void applySystemBarInsets(View root) {
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            } else {
                v.setPadding(left + insets.getSystemWindowInsetLeft(), top + insets.getSystemWindowInsetTop(),
                        right + insets.getSystemWindowInsetRight(), bottom + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }
}
