package org.openphone.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class OpenPhoneTriggerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (OpenPhoneNotificationController.ACTION_OPEN.equals(action)) {
            Intent activity = new Intent(context, MainActivity.class);
            activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(activity);
            return;
        }
        Intent service = new Intent(context, OpenPhoneAssistantService.class);
        service.setAction(action);
        context.startService(service);
    }
}
