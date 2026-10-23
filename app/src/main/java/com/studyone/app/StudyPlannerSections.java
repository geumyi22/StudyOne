package com.studyone.app;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Presentation-only views of existing schema-1 task records; never duplicates or changes storage. */
public final class StudyPlannerSections {
    private StudyPlannerSections() {}

    public static boolean isHomework(Models.StudyTask task) {
        if (task == null) return false;
        return "숙제 항목".equals(task.type) || "과제".equals(task.type)
                || (task.title != null && task.title.contains("숙제"));
    }

    /** Tomorrow's tasks including completed records, so toggling completion is reversible. */
    public static List<Models.StudyTask> tomorrow(List<Models.StudyTask> tasks, LocalDate today) {
        if (tasks == null || today == null) throw new IllegalArgumentException("일정·날짜 오류");
        LocalDate tomorrow = today.plusDays(1);
        List<Models.StudyTask> out = new ArrayList<>();
        for (Models.StudyTask task : tasks) {
            if (task == null || task.dueDate == null) throw new IllegalArgumentException("손상된 일정");
            if (tomorrow.equals(task.dueDate)) out.add(task);
        }
        out.sort(Comparator.comparing((Models.StudyTask t) -> t.completed)
                .thenComparing((Models.StudyTask t) -> !isHomework(t))
                .thenComparing((Models.StudyTask t) -> -t.importance)
                .thenComparingLong(t -> t.id));
        return out;
    }

    public static List<Models.StudyTask> tomorrowHomework(List<Models.StudyTask> tasks, LocalDate today) {
        List<Models.StudyTask> out = tomorrow(tasks, today);
        out.removeIf(t -> !isHomework(t));
        return out;
    }

    /** Historical and future tasks share the original ID; sorting a copy preserves store order. */
    public static List<Models.StudyTask> allSorted(List<Models.StudyTask> tasks) {
        if (tasks == null) throw new IllegalArgumentException("일정 오류");
        List<Models.StudyTask> out = new ArrayList<>(tasks);
        for (Models.StudyTask task : out)
            if (task == null || task.dueDate == null) throw new IllegalArgumentException("손상된 일정");
        out.sort(Comparator.comparing((Models.StudyTask t) -> t.dueDate).reversed()
                .thenComparing((Models.StudyTask t) -> t.completed)
                .thenComparingLong(t -> t.id));
        return out;
    }
}
