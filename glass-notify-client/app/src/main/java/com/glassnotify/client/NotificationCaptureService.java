package com.glassnotify.client;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotificationCaptureService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Skip own package
        if (sbn.getPackageName().equals(getPackageName())) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        // Skip ongoing notifications (media players, etc.)
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;

        // Skip group summaries
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);

        // Skip if both empty
        if (titleCs == null && textCs == null) return;

        String title = titleCs != null ? titleCs.toString() : "";
        String text = textCs != null ? textCs.toString() : "";

        // Get app label
        String appLabel;
        try {
            appLabel = getPackageManager()
                    .getApplicationLabel(
                            getPackageManager().getApplicationInfo(sbn.getPackageName(), 0))
                    .toString();
        } catch (Exception e) {
            appLabel = sbn.getPackageName();
        }

        NotificationData data = new NotificationData(appLabel, title, text, System.currentTimeMillis());
        NotificationForwardService.enqueue(data);
    }
}
