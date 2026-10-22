package com.studyone.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Optional local-only study instructions, kept separate from schema-1 tasks and sync. */
public final class StudyTaskDetail {
    public final long taskId;
    public final Integer estimatedMinutes;
    public final List<String> steps;
    public final String completionCriteria;

    public StudyTaskDetail(long taskId, Integer estimatedMinutes,
                           List<String> steps, String completionCriteria) {
        if (taskId <= 0) throw new IllegalArgumentException("일정 ID가 올바르지 않습니다.");
        if (estimatedMinutes != null && (estimatedMinutes < 1 || estimatedMinutes > 600))
            throw new IllegalArgumentException("예상 시간은 1~600분이어야 합니다.");
        if (steps == null || steps.size() > 20)
            throw new IllegalArgumentException("공부 단계는 최대 20개입니다.");
        List<String> normalized = new ArrayList<>();
        for (String step : steps) {
            if (step == null || step.trim().isEmpty() || step.trim().length() > 200)
                throw new IllegalArgumentException("공부 단계는 각각 1~200자로 입력하세요.");
            normalized.add(step.trim());
        }
        String criteria = completionCriteria == null ? "" : completionCriteria.trim();
        if (criteria.length() > 500)
            throw new IllegalArgumentException("완료 기준은 최대 500자입니다.");
        this.taskId = taskId;
        this.estimatedMinutes = estimatedMinutes;
        this.steps = Collections.unmodifiableList(normalized);
        this.completionCriteria = criteria;
    }

    public boolean isEmpty() {
        return estimatedMinutes == null && steps.isEmpty() && completionCriteria.isEmpty();
    }

    public String displayText() {
        if (isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        if (estimatedMinutes != null) out.append("예상 ").append(estimatedMinutes).append("분");
        for (int i = 0; i < steps.size(); i++) {
            if (out.length() > 0) out.append('\n');
            out.append(i + 1).append(". ").append(steps.get(i));
        }
        if (!completionCriteria.isEmpty()) {
            if (out.length() > 0) out.append('\n');
            out.append("완료 기준: ").append(completionCriteria);
        }
        return out.toString();
    }
}
