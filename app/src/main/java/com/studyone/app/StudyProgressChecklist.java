package com.studyone.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Human-readable progress checklist for a deliberate, local clipboard copy.
 * Does not transmit data, touch storage, or modify progress statuses.
 */
public final class StudyProgressChecklist {
    private StudyProgressChecklist() {}

    public static String format(List<StudyProgressItem> entries) {
        if (entries == null) throw new IllegalArgumentException("진도 목록이 없습니다.");
        List<StudyProgressItem> sorted = new ArrayList<>(entries);
        if (sorted.contains(null)) throw new IllegalArgumentException("잘못된 진도 항목이 있습니다.");
        sorted.sort(Comparator.comparing((StudyProgressItem x) -> x.subject)
                .thenComparing(x -> x.material).thenComparing(x -> x.unit));
        StringBuilder out = new StringBuilder("StudyOne 교재·시험범위 진도 체크리스트\n");
        if (sorted.isEmpty()) return out.append("등록된 진도가 없습니다.").toString();
        for (StudyProgressItem item : sorted) {
            out.append(StudyProgressItem.DONE.equals(item.status) ? "☑ " : "☐ ")
                    .append('[').append(item.subject).append("] ")
                    .append(item.material).append(" · ").append(item.unit)
                    .append(" — ").append(item.status)
                    .append(item.inExamScope ? " (시험범위 포함)" : " (시험범위 제외)")
                    .append('\n');
        }
        return out.toString().trim();
    }
}
