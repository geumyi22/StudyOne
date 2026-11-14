package com.studyone.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed mapping of an arbitrary user-defined paper template to stable,
 * separately tracked progress UUIDs. An aggregate such as "4/18" is never
 * interpreted as completion of a particular round.
 *
 * This adapter does not save, rename or migrate progress entries.
 */
public final class StudyPaperGenericProgressLink {
    private StudyPaperGenericProgressLink() {}
    public static final class Snapshot {
        public final List<StudyPaperSchedule.Lane> lanes;
        public final int completed;
        Snapshot(List<StudyPaperSchedule.Lane> lanes, int completed) {
            this.lanes=lanes;
            this.completed=completed;
        }
    }
    static String suffix(int number) {
        if (number >= 1 && number <= 20) return Character.toString((char)(0x2460+number-1));
        return "(" + number + ")";
    }
    public static Snapshot checked(List<StudyProgressItem> records, String subject,
                                   String material, List<StudyPaperSchedule.Lane> template) {
        if (records == null || subject == null || subject.trim().isEmpty()
                || material == null || material.trim().isEmpty()
                || template == null || template.isEmpty()) {
            throw new IllegalArgumentException("정확한 과목·교재와 개별 진도 기록이 필요합니다.");
        }
        Map<String, StudyProgressItem> byUnit=new HashMap<>();
        Set<String> identifiers=new HashSet<>();
        for (StudyProgressItem record:records) {
            if (record == null) throw new IllegalArgumentException("진도 원본이 손상됐습니다.");
            if (!subject.equals(record.subject) || !material.equals(record.material)) continue;
            if (!record.inExamScope) throw new IllegalArgumentException("시험범위 제외 진도는 자동으로 적용하지 않습니다.");
            if (!identifiers.add(record.id.toLowerCase(java.util.Locale.ROOT)) ||
                    byUnit.put(record.unit,record)!=null)
                throw new IllegalArgumentException("진도 회차/ID가 중복됐습니다.");
        }
        int completed=0, expected=0;
        List<StudyPaperSchedule.Lane> mapped=new ArrayList<>();
        for (StudyPaperSchedule.Lane lane:template) {
            List<StudyPaperSchedule.Paper> papers=new ArrayList<>();
            for (StudyPaperSchedule.Paper paper:lane.papers) {
                int done=0;
                boolean unfinished=false;
                for(int number=1;number<=paper.total;number++) {
                    expected++;
                    String unit="["+lane.name+"] "+paper.title+" "+suffix(number);
                    StudyProgressItem found=byUnit.remove(unit);
                    if (found==null || StudyProgressItem.UNKNOWN.equals(found.status))
                        throw new IllegalArgumentException("개별 회차 '"+unit+"' 기록이 없거나 확인 필요입니다.");
                    if (StudyProgressItem.DONE.equals(found.status)) {
                        if (unfinished) throw new IllegalArgumentException("완료된 회차가 연속되지 않습니다: "+unit);
                        completed++; done++;
                    } else {
                        unfinished=true;
                    }
                }
                papers.add(new StudyPaperSchedule.Paper(paper.title,paper.total,done));
            }
            mapped.add(new StudyPaperSchedule.Lane(lane.name,papers));
        }
        if(expected>240 || !byUnit.isEmpty())
            throw new IllegalArgumentException("선택 교재의 회차 기록과 현재 템플릿이 다릅니다.");
        return new Snapshot(mapped,completed);
    }
}
