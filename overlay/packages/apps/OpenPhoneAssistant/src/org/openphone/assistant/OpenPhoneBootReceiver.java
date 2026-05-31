package org.openphone.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class OpenPhoneBootReceiver extends BroadcastReceiver {
    private static final String TAG = "OpenPhoneBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        Intent serviceIntent = new Intent(context, OpenPhoneAssistantService.class);
        context.startService(serviceIntent);
        OpenPhoneNotificationController.showReady(context);
        Log.i(TAG, "Started OpenPhone assistant service for " + action);
    }
}
