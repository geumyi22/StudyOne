import com.studyone.app.StudyCategories;
import com.studyone.app.StudyDailyChecklist;
import com.studyone.app.Models;
import java.time.LocalDate;
import java.util.Arrays;

public final class StudyDailyChecklistSmoke {
    private static Models.StudyTask task(long id, String type, String subject, String title,
                                         String due, boolean completed) {
        return new Models.StudyTask(id,type,subject,title,LocalDate.parse(due),2,completed);
    }
    public static void main(String[] args) {
        LocalDate today=LocalDate.parse("2026-10-22");
        Models.StudyTask math=task(1,StudyCategories.ROLE_TITLES[3],"수학","내신 종이 숙제","2026-10-21",false);
        Models.StudyTask science=task(2,StudyCategories.ROLE_TITLES[3],"과학","오투 7-3-1","2026-10-22",false);
        Models.StudyTask english=task(3,StudyCategories.ROLE_TITLES[1],"영어","5~6과 전체 복습","2026-10-22",true);
        Models.StudyTask future=task(4,StudyCategories.ROLE_TITLES[3],"역사","다음 주 숙제","2026-10-25",false);
        Models.StudyTask oldDone=task(5,StudyCategories.ROLE_TITLES[3],"국어","완료된 과거 숙제","2026-10-20",true);
        Models.StudyTask legacy=task(6,"과제","사회","단원평가","2026-10-22",false);
        String result=StudyDailyChecklist.format(Arrays.asList(math,science,english,future,oldDone,legacy),today);
        if(!result.contains("☐ [수학] 내신 종이 숙제 (전날 못 다 · 원래 마감 2026-10-21)"))
            throw new AssertionError("carryover missing: "+result);
        if(!result.contains("☐ [과학] 오투 7-3-1") || !result.contains("☑ [영어] 5~6과 전체 복습"))
            throw new AssertionError("today items missing");
        if(result.contains("다음 주 숙제") || result.contains("완료된 과거 숙제"))
            throw new AssertionError("future/past completed items leaked");
        if(!result.contains("기존 분류 일정") || !result.contains("[사회] 단원평가"))
            throw new AssertionError("legacy task lost");
        for(int i=1;i<=5;i++) if(!result.contains("\n"+i+". "+StudyCategories.ROLE_TITLES[i-1]))
            throw new AssertionError("role missing "+i);
        if(!math.type.equals(StudyCategories.ROLE_TITLES[3]))throw new AssertionError("read-only violated");
        System.out.println("STUDY_DAILY_CHECKLIST_SMOKE_PASSED");
    }
}
