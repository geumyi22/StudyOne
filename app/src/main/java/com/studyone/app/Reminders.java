package com.studyone.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Reminders extends BroadcastReceiver {
    private static final String CHANNEL = "studyone_planner_reminders";
    private static final String ACTION = "com.studyone.app.REMIND";
    private static final String EXTRA_SLOT = "slot";

    public static boolean enabled(Context ctx, int slot) {
        return ctx.getSharedPreferences("studyone_reminders", Context.MODE_PRIVATE)
                .getBoolean("enabled_" + slot, false);
    }

    public static void setEnabled(Context ctx, int slot, boolean enabled) {
        ctx.getSharedPreferences("studyone_reminders", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled_" + slot, enabled).apply();
        schedule(ctx);
    }

    public static int hour(Context ctx, int slot) {
        return ctx.getSharedPreferences("studyone_reminders", Context.MODE_PRIVATE)
                .getInt("hour_" + slot, slot == 0 ? 7 : 19);
    }

    public static int minute(Context ctx, int slot) {
        return ctx.getSharedPreferences("studyone_reminders", Context.MODE_PRIVATE)
                .getInt("minute_" + slot, slot == 0 ? 20 : 30);
    }

    public static void setTime(Context ctx, int slot, int hour, int minute) {
        ctx.getSharedPreferences("studyone_reminders", Context.MODE_PRIVATE).edit()
                .putInt("hour_" + slot, hour).putInt("minute_" + slot, minute).apply();
        schedule(ctx);
    }

    public static void schedule(Context ctx) {
        AlarmManager alarm = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        for (int slot = 0; slot < 2; slot++) {
            PendingIntent pi = alarmIntent(ctx, slot);
            if (!enabled(ctx, slot)) {
                alarm.cancel(pi);
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next = now.toLocalDate().atTime(hour(ctx, slot), minute(ctx, slot));
            if (!next.isAfter(now)) next = next.plusDays(1);
            long millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            // Inexact window; no SCHEDULE_EXACT_ALARM permission required.
            alarm.setWindow(AlarmManager.RTC_WAKEUP, millis, 30 * 60 * 1000L, pi);
        }
    }

    private static PendingIntent alarmIntent(Context ctx, int slot) {
        Intent intent = new Intent(ctx, Reminders.class);
        intent.setAction(ACTION);
        intent.putExtra(EXTRA_SLOT, slot);
        return PendingIntent.getBroadcast(ctx, 200 + slot, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void channel(Context ctx) {
        NotificationManager manager = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "StudyOne 학습 알림", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            schedule(ctx);
            return;
        }
        if (!ACTION.equals(action)) return;
        int slot = intent.getIntExtra(EXTRA_SLOT, -1);
        if (slot != 0 && slot != 1) return;
        if (!enabled(ctx, slot)) return;
        show(ctx, slot);
        schedule(ctx);
    }

    private static void show(Context ctx, int slot) {
        if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return;
        channel(ctx);
        Storage storage = new Storage(ctx);
        List<Models.StudyTask> tasks = new ArrayList<>(storage.tasks());
        tasks.removeIf(t -> t.completed);
        tasks.sort(Comparator.comparing(t -> t.dueDate));
        String title = slot == 0 ? "StudyOne · 오늘 확인" : "StudyOne · 학습 점검";
        String body = tasks.isEmpty() ? "등록된 미완료 일정이 없습니다."
                : "남은 일정 " + tasks.size() + "건 · " + tasks.get(0).title
                  + " (" + tasks.get(0).dueDate + ")";
        Intent launch = new Intent(ctx, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(ctx, 90, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification note = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_book_check)
                .setContentTitle(title).setContentText(body)
                .setAutoCancel(true).setContentIntent(open).build();
        NotificationManager manager = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(3100 + slot, note);
    }
}
