import com.studyone.app.StudyTaskDetail;
import com.studyone.app.StudyDailyChecklist;
import com.studyone.app.StudyCategories;
import com.studyone.app.Models;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class StudyTaskDetailSmoke {
    private static void expectFailure(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid study detail accepted");
    }

    public static void main(String[] args) {
        StudyTaskDetail detail=new StudyTaskDetail(37L,35,
                Arrays.asList("핵심 개념 정독","오답 재풀이"),"풀이와 채점 완료");
        if(detail.isEmpty() || !detail.displayText().contains("예상 35분") ||
                !detail.displayText().contains("완료 기준: 풀이와 채점 완료"))
            throw new AssertionError("Detail display failed");
        expectFailure(()->new StudyTaskDetail(0L,30,Collections.emptyList(),""));
        expectFailure(()->new StudyTaskDetail(37L,0,Collections.emptyList(),""));
        expectFailure(()->new StudyTaskDetail(37L,601,Collections.emptyList(),""));
        expectFailure(()->new StudyTaskDetail(37L,30,Arrays.asList(""),""));
        expectFailure(()->new StudyTaskDetail(37L,30,
                Collections.singletonList(String.join("", Collections.nCopies(201,"x"))),""));
        expectFailure(()->new StudyTaskDetail(37L,30,
                Collections.nCopies(21,"a"),""));
        expectFailure(()->new StudyTaskDetail(37L,30,
                Collections.emptyList(),String.join("",Collections.nCopies(501,"x"))));
        try { detail.steps.add("tamper"); throw new AssertionError("mutable model"); }
        catch(UnsupportedOperationException expected) {}

        Models.StudyTask task = new Models.StudyTask(37L,StudyCategories.ROLE_TITLES[3],
                "과학","오투 7-3-1",LocalDate.parse("2026-10-22"),2,false);
        Map<Long,StudyTaskDetail> extras=new HashMap<>();
        extras.put(task.id,detail);
        String formatted=StudyDailyChecklist.format(Arrays.asList(task),
                LocalDate.parse("2026-10-22"),extras);
        if(!formatted.contains("☐ [과학] 오투 7-3-1") ||
                !formatted.contains("예상 35분") ||
                !formatted.contains("1. 핵심 개념 정독") ||
                !formatted.contains("완료 기준: 풀이와 채점 완료"))
            throw new AssertionError("Detailed Reminder missing fields: "+formatted);
        if(task.completed || task.dueDate.isBefore(LocalDate.parse("2026-10-22")))
            throw new AssertionError("Formatter mutated a task");
        if(!new StudyTaskDetail(1L,null,Collections.emptyList(),"").isEmpty())
            throw new AssertionError("Empty detail must be removable");
        expectFailure(()->StudyDailyChecklist.format(Arrays.asList(task),
                LocalDate.parse("2026-10-22"),Collections.singletonMap(37L,
                new StudyTaskDetail(40L,null,Collections.emptyList(),"mismatch"))));
        System.out.println("STUDY_TASK_DETAIL_SMOKE_PASSED");
    }
}
