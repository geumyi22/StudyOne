package com.studyone.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local-only exam overview; never edits the daily checklist or progress store. */
public final class StudyExamFocus {
    private static final List<String> ORDER = Arrays.asList(
            "국어","사회","역사","과학","영어","수학");
    private static final String ONLINE = StudyOnlinePractice.UNIT;
    private StudyExamFocus() {}

    public static final class Subject {
        public final String name;
        public int total,done,inProgress,todo,unknown,onlineWaiting;
        public String next = "";
        Subject(String name){this.name=name;}
        public int percent(){return total==0?0:(int)Math.round(100.0*done/total);}
        public String summary(){
            return "["+name+"] "+done+"/"+total+" ("+percent()+"%) · 진행 "
                    +inProgress+" · 미완료 "+todo+" · 확인 "+unknown
                    +(onlineWaiting>0?" · 온라인 대기 "+onlineWaiting:"");
        }
        public String nextText(){
            return next.isEmpty() ? "시험범위 학습 완료" : "우선: "+next;
        }
    }

    private static int rank(String status) {
        if(StudyProgressItem.IN_PROGRESS.equals(status))return 0;
        if(StudyProgressItem.TODO.equals(status))return 1;
        return 2;
    }

    public static List<Subject> summarize(List<StudyProgressItem> items){
        if(items==null)throw new IllegalArgumentException("진도 목록이 없습니다.");
        Map<String,List<StudyProgressItem>> groups=new LinkedHashMap<>();
        for(StudyProgressItem item:items){
            if(item==null)throw new IllegalArgumentException("손상된 진도 항목");
            if(!item.inExamScope)continue;
            String subject=item.subject.trim();
            if(subject.isEmpty())throw new IllegalArgumentException("과목 이름이 없습니다.");
            groups.computeIfAbsent(subject,k->new ArrayList<>()).add(item);
        }
        List<String> names=new ArrayList<>(groups.keySet());
        names.sort(Comparator.comparingInt((String n)->{
            int index=ORDER.indexOf(n);return index<0?ORDER.size():index;
        }).thenComparing(n->n));
        List<Subject> result=new ArrayList<>();
        for(String name:names){
            Subject row=new Subject(name);
            List<StudyProgressItem> candidates=new ArrayList<>();
            List<StudyProgressItem> online=new ArrayList<>();
            for(StudyProgressItem item:groups.get(name)){
                row.total++;
                if(StudyProgressItem.DONE.equals(item.status)){row.done++;continue;}
                if(StudyProgressItem.IN_PROGRESS.equals(item.status))row.inProgress++;
                else if(StudyProgressItem.TODO.equals(item.status))row.todo++;
                else row.unknown++;
                if(ONLINE.equals(item.unit.trim())){row.onlineWaiting++;online.add(item);}
                else candidates.add(item);
            }
            // Printed textbook/test packets come before online problem banks.
            // This only recommends; it never auto-marks anything completed.
            List<StudyProgressItem> eligible=candidates.isEmpty()?online:candidates;
            eligible.sort(Comparator.comparingInt((StudyProgressItem it)->rank(it.status))
                    .thenComparing(it->it.material).thenComparing(it->it.unit));
            if(!eligible.isEmpty()){
                StudyProgressItem first=eligible.get(0);
                row.next=first.material+" · "+first.unit+" ("+first.status+")";
            }
            result.add(row);
        }
        return result;
    }
}
