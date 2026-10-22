package com.studyone.app;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only, local Samsung Reminder-style daily checklist. */
public final class StudyDailyChecklist {
    private StudyDailyChecklist() {}

    public static String format(List<Models.StudyTask> all, LocalDate date) {
        if (all == null || date == null) throw new IllegalArgumentException("날짜 또는 일정이 없습니다.");
        List<Models.StudyTask> selected = new ArrayList<>();
        for (Models.StudyTask task : all) {
            if (task == null || task.dueDate == null) throw new IllegalArgumentException("손상된 일정 항목");
            if (task.dueDate.equals(date) || (task.dueDate.isBefore(date) && !task.completed)) {
                selected.add(task);
            }
        }
        selected.sort(Comparator.comparing((Models.StudyTask x) -> x.dueDate)
                .thenComparing((x) -> -x.importance)
                .thenComparingLong(x -> x.id));
        StringBuilder out = new StringBuilder("StudyOne 할꺼 · ").append(date).append("\n");
        for (int index = 0; index < StudyCategories.ROLE_TITLES.length; index++) {
            String role = StudyCategories.ROLE_TITLES[index];
            out.append("\n").append(index + 1).append(". ").append(role).append("\n");
            int count = 0;
            for (Models.StudyTask item : selected) {
                // Unfinished past tasks are temporarily shown as carried over,
                // without changing their stored category or due date.
                boolean carried = item.dueDate.isBefore(date) && !item.completed;
                if (index == 0 ? carried || role.equals(item.type)
                        : !carried && role.equals(item.type)) {
                    appendItem(out, item, carried);
                    count++;
                }
            }
            if (count == 0) out.append("(없음)\n");
        }
        int legacyCount = 0;
        for (Models.StudyTask item : selected) {
            if (!item.dueDate.isBefore(date) && !StudyCategories.isRole(item.type)) {
                if (legacyCount++ == 0) out.append("\n기존 분류 일정\n");
                appendItem(out, item, false);
            }
        }
        return out.toString().trim();
    }

    private static void appendItem(StringBuilder out, Models.StudyTask item, boolean carried) {
        String subject = item.subject == null || item.subject.trim().isEmpty()
                ? "기타" : item.subject.trim();
        out.append(item.completed ? "☑ " : "☐ ")
                .append('[').append(subject).append("] ")
                .append(item.title == null ? "" : item.title.trim());
        if (carried) out.append(" (전날 못 다 · 원래 마감 ").append(item.dueDate).append(')');
        out.append('\n');
    }
}
