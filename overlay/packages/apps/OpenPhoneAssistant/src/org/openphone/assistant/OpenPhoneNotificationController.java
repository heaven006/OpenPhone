package org.openphone.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class OpenPhoneNotificationController {
    static final String CHANNEL_ID = "openphone_agent";
    static final String ACTION_START = "org.openphone.assistant.action.START";
    static final String ACTION_STOP = "org.openphone.assistant.action.STOP";
    static final String ACTION_OPEN = "org.openphone.assistant.action.OPEN";
    static final int NOTIFICATION_ID = 1001;

    private OpenPhoneNotificationController() {}

    static void showReady(Context context) {
        show(context, false, context.getString(R.string.notification_agent_ready));
    }

    static void showActive(Context context, String taskId) {
        String detail = taskId == null ? context.getString(R.string.notification_agent_active)
                : context.getString(R.string.notification_agent_active) + " " + taskId;
        show(context, true, detail);
    }

    static void cancel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    private static void show(Context context, boolean active, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_agent),
                    NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }

        Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(R.drawable.ic_openphone_tile)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(active)
                .setShowWhen(false)
                .setContentIntent(pendingBroadcast(context, ACTION_OPEN, 1));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID);
        }

        if (active) {
            builder.addAction(new Notification.Action.Builder(
                    R.drawable.ic_openphone_tile,
                    context.getString(R.string.action_stop_task),
                    pendingBroadcast(context, ACTION_STOP, 2)).build());
        } else {
            builder.addAction(new Notification.Action.Builder(
                    R.drawable.ic_openphone_tile,
                    context.getString(R.string.action_start_task),
                    pendingBroadcast(context, ACTION_START, 3)).build());
        }
        builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_openphone_tile,
                context.getString(R.string.action_open_assistant),
                pendingBroadcast(context, ACTION_OPEN, 4)).build());
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private static PendingIntent pendingBroadcast(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, OpenPhoneTriggerReceiver.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }
}
