package com.studyone.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The five long-lived StudyOne planner roles. Daily tasks belong to a role;
 * these descriptions are permanent guidance, not daily task notes.
 *
 * Legacy task types remain accepted so pre-existing schema-1 backups and
 * locally saved plans keep their original categories.
 */
public final class StudyCategories {
    private StudyCategories() {}

    public static final String[] ROLE_TITLES = {
            "개별 항목 (전날 못 다)",
            "정리 및 복습 항목",
            "수행 및 암기 항목",
            "숙제 항목",
            "기타 항목"
    };

    public static final String[] ROLE_DESCRIPTIONS = {
            "이전 날짜에 끝내지 못한 공부와 이월 사유를 확인하고 다시 배치합니다. 이미 완료한 과제는 중복 등록하지 않습니다.",
            "학교 학습지·교과서 정독, 시험범위 전체 오답 및 개념 복습을 관리합니다. 새로운 문제집 진도와 새 문제 풀이는 숙제 항목에 넣습니다.",
            "학교 수행평가 자료 조사·작성·제작, 암기형 쓰기 평가 및 실기 연습을 관리합니다. 서로 다른 수행평가는 개별 과제로 유지합니다.",
            "학교·학원 숙제뿐 아니라 스스로 정한 새로운 문제집 진도와 단원평가·문제 풀이를 관리합니다. 채점과 제출 여부도 확인합니다.",
            "시험 일정, 준비물, 학습 계획 및 다음 날 준비를 관리합니다. 실제 공부·복습 내용은 해당 분류에 넣습니다."
    };

    public static final String[] LEGACY_TYPES = {"과제", "수행평가", "시험", "준비물"};

    public static boolean isRole(String type) {
        for (String name : ROLE_TITLES) if (name.equals(type)) return true;
        return false;
    }

    /**
     * Preserve an existing unrecognised type on edit instead of silently
     * changing it to the default. Nothing in this method mutates stored data.
     */
    public static String[] taskTypes(String existingType) {
        List<String> out = new ArrayList<>();
        out.addAll(Arrays.asList(ROLE_TITLES));
        out.addAll(Arrays.asList(LEGACY_TYPES));
        if (existingType != null && !existingType.trim().isEmpty()
                && !out.contains(existingType)) {
            out.add(existingType);
        }
        return out.toArray(new String[0]);
    }

    public static String guide() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < ROLE_TITLES.length; i++) {
            if (i > 0) text.append("\n\n");
            text.append(i + 1).append(". ").append(ROLE_TITLES[i])
                    .append("\n").append(ROLE_DESCRIPTIONS[i]);
        }
        return text.toString();
    }
}
