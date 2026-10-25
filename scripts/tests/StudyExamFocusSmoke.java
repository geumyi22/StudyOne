import com.studyone.app.StudyExamFocus;
import com.studyone.app.StudyProgressItem;
import java.util.Arrays;
import java.util.List;

public final class StudyExamFocusSmoke {
    private static void check(boolean condition,String label) {
        if(!condition)throw new AssertionError(label);
    }
    private static StudyProgressItem item(String subject,String material,String unit,
                                           String status,boolean scope) {
        return StudyProgressItem.create(subject,material,unit,status,scope);
    }
    public static void main(String[] args){
        List<StudyProgressItem> original=Arrays.asList(
            item("과학","오투","시험 대비 자료","미완료",true),
            item("역사","체크체크","시험대비 10장","진행 중",true),
            item("역사","온라인 문제풀이","온라인 내신 문제","미완료",true),
            item("국어","자습서","전체 개념 복습","완료",true),
            item("국어","온라인 문제풀이","온라인 내신 문제","미완료",true),
            item("과학","학교","시험범위 외","완료",false));
        List<StudyExamFocus.Subject> rows=StudyExamFocus.summarize(original);
        check(rows.size()==3,"3 subjects only");
        check(rows.get(0).name.equals("국어") &&
              rows.get(1).name.equals("역사") &&
              rows.get(2).name.equals("과학"),"fixed subject order");
        check(rows.get(0).percent()==50 && rows.get(0).next.contains("온라인 내신 문제"),
              "completed textbook allows online suggestion");
        check(rows.get(1).onlineWaiting==1 && rows.get(1).next.contains("시험대비 10장") &&
              !rows.get(1).next.contains("온라인 내신 문제"),
              "10-page history packet recommended before online question bank");
        check(rows.get(2).total==1 && rows.get(2).next.contains("시험 대비 자료"),
              "nonexam records do not inflate progress");
        check(original.get(1).status.equals("진행 중"),"no destructive status mutation");
        check(StudyExamFocus.summarize(java.util.Collections.emptyList()).isEmpty(),
              "empty dataset does not pretend to have progress");
        System.out.println("StudyOne exam-focus subject priority fixture: PASS");
    }
}
