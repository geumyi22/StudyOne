package com.studyone.app;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline READ-ONLY learning-paper scheduling. Does not read/write Storage,
 * StudyProgressStorage, tasks, accounts, preferences, or network services.
 *
 * The proposal is never silently saved as a real task. Callers must obtain
 * separate user consent to create any corresponding real study tasks.
 */
public final class StudyPaperSchedule {
    private StudyPaperSchedule() {}
    private static final String[] NUMBERS = {
        "①","②","③","④","⑤","⑥","⑦","⑧","⑨","⑩",
        "⑪","⑫","⑬","⑭","⑮","⑯","⑰","⑱","⑲","⑳"
    };

    public static final class Paper {
        public final String title;
        public final int total;
        public final int completed;

        public Paper(String title, int total, int completed) {
            this.title = label(title);
            if (total < 1 || total > 60 || completed < 0 || completed > total) {
                throw new IllegalArgumentException("회차는 1~60회, 완료는 0~전체 회차 이하여야 합니다.");
            }
            this.total = total;
            this.completed = completed;
        }
    }

    public static final class Lane {
        public final String name;
        public final List<Paper> papers;

        public Lane(String name, List<Paper> papers) {
            this.name = label(name);
            if (papers == null || papers.isEmpty() || papers.size() > 20) {
                throw new IllegalArgumentException("단원마다 시험지 종류 1~20개를 지정하세요.");
            }
            Set<String> seen = new HashSet<>();
            for (Paper item : papers) {
                if (item == null || !seen.add(item.title)) {
                    throw new IllegalArgumentException("시험지 종류가 비어 있거나 중복됩니다.");
                }
            }
            this.papers = Collections.unmodifiableList(new ArrayList<>(papers));
        }
    }

    public static final class Slot {
        public final String lane;
        public final String title;

        Slot(String lane, String title) {
            this.lane = lane;
            this.title = title;
        }
    }

    public static final class Day {
        public final LocalDate date;
        public final String review;
        public final List<Slot> papers;

        Day(LocalDate date, String review, List<Slot> papers) {
            this.date = date;
            this.review = review;
            this.papers = Collections.unmodifiableList(new ArrayList<>(papers));
        }
    }

    public static final class Preview {
        public final List<Day> days;
        public final int remainingPapers;
        public final int workDays;
        public final int dailyLimit;

        Preview(List<Day> days, int remainingPapers, int workDays, int dailyLimit) {
            this.days = Collections.unmodifiableList(new ArrayList<>(days));
            this.remainingPapers = remainingPapers;
            this.workDays = workDays;
            this.dailyLimit = dailyLimit;
        }

        public String clipboardText() {
            StringBuilder output = new StringBuilder(
                "StudyOne · 시험지·학습지 분량 미리보기 (저장하지 않음)\n"
                + "남은 시험지 " + remainingPapers + "회 · 하루 최대 " + dailyLimit + "회\n");
            if (days.isEmpty()) output.append("배정할 남은 시험지가 없습니다.\n");
            for (Day day : days) {
                output.append("\n").append(day.date).append(" · ")
                    .append(day.review).append(" 개념 복습\n");
                for (Slot paper : day.papers) {
                    output.append("☐ [").append(paper.lane).append("] ")
                        .append(paper.title).append("\n");
                }
            }
            output.append("\n읽기 전용 추천안입니다. 실제 숙제·진도에 반영되지 않았습니다.");
            return output.toString();
        }
    }

    private static String label(String value) {
        if (value == null || value.isEmpty() || value.length() > 80
                || !value.equals(value.trim()) || value.contains("\n")
                || value.contains("\r")) {
            throw new IllegalArgumentException("단원·시험지 이름은 1~80자로 입력하세요.");
        }
        return value;
    }

    private static final class PaperCursor {
        final Paper original;
        int next;
        int remaining;

        PaperCursor(Paper original) {
            this.original = original;
            this.next = original.completed + 1;
            this.remaining = original.total - original.completed;
        }
    }

    private static final class LaneCursor {
        final String name;
        final List<PaperCursor> items = new ArrayList<>();
        int cursor;

        LaneCursor(Lane lane) {
            name = lane.name;
            for (Paper paper : lane.papers) items.add(new PaperCursor(paper));
        }

        boolean hasRemaining() {
            for (PaperCursor paper : items) if (paper.remaining > 0) return true;
            return false;
        }

        Slot takeOne() {
            for (int delta = 0; delta < items.size(); delta++) {
                int index = (cursor + delta) % items.size();
                PaperCursor paper = items.get(index);
                if (paper.remaining == 0) continue;
                String number = paper.next <= NUMBERS.length
                    ? NUMBERS[paper.next - 1] : "(" + paper.next + ")";
                Slot result = new Slot(name, paper.original.title + " " + number);
                paper.next++;
                paper.remaining--;
                cursor = (index + 1) % items.size();
                return result;
            }
            throw new IllegalStateException("남은 시험지를 선택할 수 없습니다.");
        }
    }

    public static Preview preview(LocalDate start, LocalDate end, int dailyLimit,
                                  Set<LocalDate> skipDates, String firstReview,
                                  List<Lane> lanes) {
        if (start == null || end == null || end.isBefore(start)
                || ChronoUnit.DAYS.between(start, end) >= 120) {
            throw new IllegalArgumentException("시작일~마감일을 120일 이내로 지정하세요.");
        }
        if (dailyLimit < 1 || dailyLimit > 12) {
            throw new IllegalArgumentException("하루 최대 분량은 1~12회입니다.");
        }
        if (!"학습지".equals(firstReview) && !"교재".equals(firstReview)) {
            throw new IllegalArgumentException("첫 복습 출처를 학습지 또는 교재로 선택하세요.");
        }
        if (lanes == null || lanes.isEmpty() || lanes.size() > 8) {
            throw new IllegalArgumentException("단원은 1~8개로 지정하세요.");
        }
        Set<LocalDate> skips = skipDates == null ? Collections.emptySet()
                : new HashSet<>(skipDates);
        if (skips.size() > 120) {
            throw new IllegalArgumentException("쉬는 날이 너무 많습니다.");
        }
        for (LocalDate skipped : skips) {
            if (skipped == null || skipped.isBefore(start) || skipped.isAfter(end)) {
                throw new IllegalArgumentException("쉬는 날은 계획 범위 안에 있어야 합니다.");
            }
        }
        List<LaneCursor> remaining = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int total = 0;
        for (Lane lane : lanes) {
            if (lane == null || !names.add(lane.name)) {
                throw new IllegalArgumentException("중복되거나 비어 있는 단원이 있습니다.");
            }
            remaining.add(new LaneCursor(lane));
            for (Paper item : lane.papers) total += item.total - item.completed;
        }
        if (total > 240) throw new IllegalArgumentException("남은 시험지는 최대 240회입니다.");
        List<LocalDate> work = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (!skips.contains(date)) work.add(date);
        }
        if (total > work.size() * dailyLimit) {
            throw new IllegalArgumentException("남은 날짜에 모두 끝낼 수 없습니다. 날짜나 하루 분량을 조정하세요.");
        }
        int original = total;
        List<Day> days = new ArrayList<>();
        for (int i = 0; i < work.size() && total > 0; i++) {
            List<Slot> selected = new ArrayList<>();
            for (int slot = 0; slot < dailyLimit && total > 0; slot++) {
                int desired = (i * dailyLimit + slot) % remaining.size();
                LaneCursor chosen = null;
                for (int shift = 0; shift < remaining.size(); shift++) {
                    LaneCursor candidate = remaining.get((desired + shift) % remaining.size());
                    if (candidate.hasRemaining()) { chosen = candidate; break; }
                }
                if (chosen == null) throw new IllegalStateException("회차 분배가 중단됐습니다.");
                selected.add(chosen.takeOne());
                total--;
            }
            String review = i % 2 == 0 ? firstReview
                    : ("학습지".equals(firstReview) ? "교재" : "학습지");
            days.add(new Day(work.get(i), review, selected));
        }
        if (total != 0) throw new IllegalStateException("분량 분배 결과가 불완전합니다.");
        return new Preview(days, original, work.size(), dailyLimit);
    }
}
