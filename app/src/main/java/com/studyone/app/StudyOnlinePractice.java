package com.studyone.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A user-triggered suggestion for common online exam practice progress entries.
 * It never reads private task data, changes existing entries, or writes storage.
 * An existing subject + unit pair is authoritative regardless of material/status.
 */
public final class StudyOnlinePractice {
    public static final String UNIT = "온라인 내신 문제";
    public static final String MATERIAL = "온라인 문제풀이";
    private static final String[] SUBJECTS = {
            "국어", "사회", "역사", "과학", "영어", "수학"
    };

    private StudyOnlinePractice() {}

    /** Stable subject ordering, no duplicates even when the user chose another material. */
    public static List<String> missingSubjects(List<StudyProgressItem> existing) {
        if (existing == null) throw new IllegalArgumentException("진도 목록이 없습니다.");
        Set<String> alreadyAdded = new HashSet<>();
        for (StudyProgressItem item : existing) {
            if (item == null) throw new IllegalArgumentException("진도 항목이 올바르지 않습니다.");
            if (UNIT.equals(item.unit.trim())) alreadyAdded.add(item.subject.trim());
        }
        List<String> missing = new ArrayList<>();
        for (String subject : SUBJECTS) {
            if (!alreadyAdded.contains(subject)) missing.add(subject);
        }
        return missing;
    }

    /** Create only missing items. Caller must show a preview and request approval before saving. */
    public static List<StudyProgressItem> createMissing(List<StudyProgressItem> existing) {
        List<StudyProgressItem> additions = new ArrayList<>();
        for (String subject : missingSubjects(existing)) {
            additions.add(StudyProgressItem.create(subject, MATERIAL, UNIT,
                    StudyProgressItem.TODO, true));
        }
        return additions;
    }
}
