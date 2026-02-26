package com.glassnotify.client;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotificationCaptureService extends NotificationListenerService {

    private static final String MAPS_PACKAGE = "com.google.android.apps.maps";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Skip own package
        if (sbn.getPackageName().equals(getPackageName())) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        // Intercept Google Maps ongoing navigation notifications
        if (MAPS_PACKAGE.equals(sbn.getPackageName())
                && (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            handleMapsNavigation(notification);
            return;
        }

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

    private void handleMapsNavigation(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return;

        CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);

        String instruction = textCs != null ? textCs.toString() : "";
        String eta = "";
        String distance = "";

        // Title is typically "3 min (0.8 mi)" — parse ETA and distance
        if (titleCs != null) {
            String titleStr = titleCs.toString();
            int parenIdx = titleStr.indexOf(" (");
            if (parenIdx > 0 && titleStr.endsWith(")")) {
                eta = titleStr.substring(0, parenIdx);
                distance = titleStr.substring(parenIdx + 2, titleStr.length() - 1);
            } else {
                eta = titleStr;
            }
        }

        NavigationData nav = new NavigationData(instruction, distance, eta, System.currentTimeMillis());
        NotificationForwardService.enqueueRaw(nav.toBytes());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (MAPS_PACKAGE.equals(sbn.getPackageName())) {
            Notification notification = sbn.getNotification();
            if (notification != null && (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                NotificationForwardService.enqueueRaw(NavigationData.navEnd());
            }
        }
    }
}
