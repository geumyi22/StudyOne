package com.studyone.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict read-only adapter for paper-specific StudyProgressItem records.
 * NEVER derive a paper completion count from an aggregate '18회' status.
 * All 18 individually tracked records must exist and have unambiguous states.
 */
public final class StudyPaperProgressLink {
    private StudyPaperProgressLink() {}
    public static final String SUBJECT="국어";
    public static final String MATERIAL="국어 인쇄 시험지 18회";
    public static final String[] NAMES={
        "2-1 소단원 평가", "2-2 소단원 평가", "2단원 대단원 평가",
        "3-1 소단원 평가", "3-2 소단원 평가", "3단원 대단원 평가"
    };
    private static final String[] ROUNDS={"①","②","③"};
    public static final class Snapshot {
        public final List<StudyPaperSchedule.Lane> lanes;
        public final int completed;
        public final int tracked;
        Snapshot(List<StudyPaperSchedule.Lane> lanes,int completed,int tracked){
            this.lanes=lanes;this.completed=completed;this.tracked=tracked;
        }
    }
    public static Snapshot checked(List<StudyProgressItem> records){
        if(records==null)throw new IllegalArgumentException("진도 원본을 확인할 수 없습니다.");
        Map<String,StudyProgressItem> found=new HashMap<>();
        for(StudyProgressItem item:records){
            if(item==null)throw new IllegalArgumentException("진도 원본이 손상됐습니다.");
            if(!SUBJECT.equals(item.subject)||!MATERIAL.equals(item.material))continue;
            if(!item.inExamScope)throw new IllegalArgumentException(
                "시험범위 제외 기록이 포함돼 자동 채우기를 중단했습니다.");
            if(found.put(item.unit,item)!=null)throw new IllegalArgumentException(
                "인쇄 시험지 회차 ID가 중복돼 자동 채우기를 중단했습니다.");
        }
        if(found.size()!=18)throw new IllegalArgumentException(
            "인쇄 시험지 18개 회차의 개별 진도 기록이 없습니다. 합계 진도로 완료 회차를 추정하지 않습니다. 직접 입력하세요.");
        List<StudyPaperSchedule.Paper> left=new ArrayList<>(),right=new ArrayList<>();
        int completed=0;
        for(int i=0;i<NAMES.length;i++){
            int done=0;
            boolean earlierIncomplete=false;
            for(int k=0;k<3;k++){
                String unit=NAMES[i]+" "+ROUNDS[k];
                StudyProgressItem item=found.remove(unit);
                if(item==null||StudyProgressItem.UNKNOWN.equals(item.status))
                    throw new IllegalArgumentException("회차 "+unit+"가 누락됐거나 상태 확인이 필요합니다.");
                if(StudyProgressItem.DONE.equals(item.status)){
                    if(earlierIncomplete)throw new IllegalArgumentException(
                        "완료 회차가 순서대로 이어지지 않습니다: "+unit+" · 직접 확인하세요.");
                    done++;completed++;
                } else {
                    earlierIncomplete=true;
                }
            }
            StudyPaperSchedule.Paper paper=new StudyPaperSchedule.Paper(NAMES[i],3,done);
            if(i<3)left.add(paper);else right.add(paper);
        }
        if(!found.isEmpty())throw new IllegalArgumentException(
            "알 수 없는 인쇄 시험지 회차가 포함돼 자동 채우기를 중단했습니다.");
        List<StudyPaperSchedule.Lane> lanes=new ArrayList<>();
        lanes.add(new StudyPaperSchedule.Lane("2단원",left));
        lanes.add(new StudyPaperSchedule.Lane("3단원",right));
        return new Snapshot(lanes,completed,18);
    }
}
