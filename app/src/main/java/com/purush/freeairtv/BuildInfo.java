package com.purush.freeairtv;

import android.content.Context;
import android.content.pm.PackageInfo;

/** The installed app's own version. */
final class BuildInfo {

    private BuildInfo() {
    }

    static String versionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "0";
        } catch (Exception e) {
            return "0";
        }
    }
}
