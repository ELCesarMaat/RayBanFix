package com.elcesarmaat.raybanfix;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

// Read access to the user's videos, needed to reopen originals behind shortcuts: the grant an app gives when it
// shares a video with us is temporary, so without this a shortcut would stop working once that grant expires.
final class MediaAccess {

    enum Level { FULL, PARTIAL, NONE }

    // Written out because the constant only exists from API 34.
    private static final String USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";

    private MediaAccess() {
    }

    static Level level(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (granted(context, Manifest.permission.READ_MEDIA_VIDEO)) {
                return Level.FULL;
            }
            if (Build.VERSION.SDK_INT >= 34 && granted(context, USER_SELECTED)) {
                return Level.PARTIAL;
            }
            return Level.NONE;
        }
        return granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) ? Level.FULL : Level.NONE;
    }

    static String[] permissions() {
        if (Build.VERSION.SDK_INT >= 34) {
            return new String[]{Manifest.permission.READ_MEDIA_VIDEO, USER_SELECTED};
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    // Once the user has said "don't ask again" the system silently refuses, so send them to the app's settings instead.
    static void request(Activity activity, int requestCode) {
        boolean blocked = level(activity) == Level.NONE && Settings.askedMediaAccess(activity)
                && !activity.shouldShowRequestPermissionRationale(permissions()[0]);
        if (blocked) {
            activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.getPackageName(), null)));
            return;
        }
        Settings.setAskedMediaAccess(activity);
        activity.requestPermissions(permissions(), requestCode);
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
