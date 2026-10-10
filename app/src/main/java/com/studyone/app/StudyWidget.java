package com.studyone.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class StudyWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) render(context, manager, id);
    }

    public static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, StudyWidget.class));
        for (int id : ids) render(context, manager, id);
    }

    private static void render(Context ctx, AppWidgetManager manager, int id) {
        Storage storage = new Storage(ctx);
        List<Models.StudyTask> tasks = storage.tasks();
        int pending = 0;
        Models.StudyTask next = null;
        for (Models.StudyTask t : tasks) {
            if (t.completed) continue;
            pending++;
            if (next == null || t.dueDate.isBefore(next.dueDate)) next = t;
        }
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.study_widget);
        views.setTextViewText(R.id.widget_title, "StudyOne · 할 일 " + pending + "개");
        if (next != null) {
            long days = ChronoUnit.DAYS.between(LocalDate.now(), next.dueDate);
            String d = days == 0 ? "D-day" : (days < 0 ? "D+" + (-days) : "D-" + days);
            views.setTextViewText(R.id.widget_detail, d + "  " + next.subject + " " + next.title);
        } else {
            views.setTextViewText(R.id.widget_detail, "오늘의 공부를 시작해 보세요.");
        }
        Intent launch = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 205, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        manager.updateAppWidget(id, views);
    }
}
