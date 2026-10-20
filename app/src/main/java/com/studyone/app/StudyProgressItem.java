package com.studyone.app;

import java.util.Arrays;
import java.util.UUID;

/**
 * Independently tracked study progress. This is not a daily task; a record can
 * represent a textbook subunit, mock test, worksheet, or whole-range review.
 */
public final class StudyProgressItem {
    public static final String UNKNOWN = "확인 필요";
    public static final String TODO = "미완료";
    public static final String IN_PROGRESS = "진행 중";
    public static final String DONE = "완료";
    public static final String[] STATUSES = {UNKNOWN, TODO, IN_PROGRESS, DONE};

    public final String id, subject, material, unit, status;
    public final boolean inExamScope;
    public final long revision, updatedAt;

    public StudyProgressItem(String id, String subject, String material, String unit,
                             String status, boolean inExamScope, long revision, long updatedAt) {
        if (id == null) throw new IllegalArgumentException("진도 ID가 없습니다.");
        try { UUID.fromString(id); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("진도 ID 형식이 올바르지 않습니다."); }
        this.id = id;
        this.subject = required(subject, 60, "과목");
        this.material = required(material, 120, "교재");
        this.unit = required(unit, 200, "진도 항목");
        if (!Arrays.asList(STATUSES).contains(status)) {
            throw new IllegalArgumentException("진도 상태가 올바르지 않습니다.");
        }
        if (revision < 1 || updatedAt < 0) throw new IllegalArgumentException("진도 수정 기록이 올바르지 않습니다.");
        this.status = status;
        this.inExamScope = inExamScope;
        this.revision = revision;
        this.updatedAt = updatedAt;
    }

    private static String required(String value, int max, String label) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > max) {
            throw new IllegalArgumentException(label + "을(를) 1~" + max + "자로 입력하세요.");
        }
        return trimmed;
    }

    public static StudyProgressItem create(String subject, String material,
                                           String unit, String status, boolean inExamScope) {
        return new StudyProgressItem(UUID.randomUUID().toString(),
                subject, material, unit, status, inExamScope, 1, System.currentTimeMillis());
    }

    public StudyProgressItem edit(String subject, String material,
                                  String unit, String status, boolean inExamScope) {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("진도 버전 한도를 초과했습니다.");
        return new StudyProgressItem(id, subject, material, unit, status, inExamScope,
                revision + 1, Math.max(System.currentTimeMillis(), updatedAt + 1));
    }
}
