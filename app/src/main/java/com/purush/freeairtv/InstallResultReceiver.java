package com.purush.freeairtv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/** Receives the system installer's result; shows its confirmation screen when it asks. */
public class InstallResultReceiver extends BroadcastReceiver {

    private static final String TAG = "FreeAirTv";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "Install result " + status + ": " + message);
        if (status != PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, context.getString(R.string.update_failed, message),
                    Toast.LENGTH_LONG).show();
        }
    }
}
