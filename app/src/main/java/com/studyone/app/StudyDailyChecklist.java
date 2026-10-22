package com.studyone.app;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Read-only local Samsung Reminder-style daily checklist; never changes stored dates or categories. */
public final class StudyDailyChecklist {
    private StudyDailyChecklist() {}

    /** Sorted snapshot of today's tasks and unfinished past tasks. */
    public static List<Models.StudyTask> visible(List<Models.StudyTask> all, LocalDate date) {
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
        return selected;
    }

    /** 0–4: permanent study roles; 5: legacy today; -1: not on today's checklist. */
    public static int groupIndex(Models.StudyTask task, LocalDate date) {
        if (task == null || date == null || task.dueDate == null)
            throw new IllegalArgumentException("날짜 또는 일정 항목이 없습니다.");
        if (task.dueDate.isAfter(date) || (task.dueDate.isBefore(date) && task.completed))
            return -1;
        if (task.dueDate.isBefore(date)) return 0; // carry over, without changing the stored role
        for (int i = 0; i < StudyCategories.ROLE_TITLES.length; i++)
            if (StudyCategories.ROLE_TITLES[i].equals(task.type)) return i;
        return 5; // preserve legacy / unfamiliar classifications
    }

    public static String format(List<Models.StudyTask> all, LocalDate date) {
        return format(all, date, Collections.emptyMap());
    }

    /** Portable copy optionally includes local-only duration, steps and completion criteria. */
    public static String format(List<Models.StudyTask> all, LocalDate date,
                                Map<Long, StudyTaskDetail> details) {
        if (details == null) throw new IllegalArgumentException("상세 정보가 없습니다.");
        List<Models.StudyTask> selected = visible(all, date);
        StringBuilder out = new StringBuilder("StudyOne 할꺼 · ").append(date).append("\n");
        for (int index = 0; index < StudyCategories.ROLE_TITLES.length; index++) {
            out.append("\n").append(index + 1).append(". ")
                    .append(StudyCategories.ROLE_TITLES[index]).append("\n");
            int count = 0;
            for (Models.StudyTask item : selected) {
                if (groupIndex(item, date) != index) continue;
                appendItem(out, item, item.dueDate.isBefore(date), details.get(item.id));
                count++;
            }
            if (count == 0) out.append("(없음)\n");
        }
        int legacyCount = 0;
        for (Models.StudyTask item : selected) {
            if (groupIndex(item, date) != 5) continue;
            if (legacyCount++ == 0) out.append("\n기존 분류 일정\n");
            appendItem(out, item, false, details.get(item.id));
        }
        return out.toString().trim();
    }

    private static void appendItem(StringBuilder out, Models.StudyTask item,
                                   boolean carried, StudyTaskDetail detail) {
        String subject = item.subject == null || item.subject.trim().isEmpty()
                ? "기타" : item.subject.trim();
        out.append(item.completed ? "☑ " : "☐ ")
                .append('[').append(subject).append("] ")
                .append(item.title == null ? "" : item.title.trim());
        if (carried) out.append(" (전날 못 다 · 원래 마감 ").append(item.dueDate).append(')');
        out.append('\n');
        if (detail != null && !detail.isEmpty()) {
            if (detail.taskId != item.id)
                throw new IllegalArgumentException("상세 정보와 일정 ID가 다릅니다.");
            String[] detailLines = detail.displayText().split("\\n");
            for (String line : detailLines) out.append("    ").append(line).append('\n');
        }
    }
}
