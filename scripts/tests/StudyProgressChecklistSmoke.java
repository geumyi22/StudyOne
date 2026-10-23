import com.studyone.app.StudyProgressChecklist;
import com.studyone.app.StudyProgressItem;
import java.util.Arrays;
import java.util.Collections;

public class StudyProgressChecklistSmoke {
    private static StudyProgressItem item(String id, String subject, String status, boolean exam) {
        return new StudyProgressItem(id, subject, "오투", "7-3-1", status, exam, 1, 10);
    }
    public static void main(String[] args) {
        StudyProgressItem science = item("00000000-0000-0000-0000-000000000001", "과학", "완료", true);
        StudyProgressItem korean = item("00000000-0000-0000-0000-000000000002", "국어", "진행 중", false);
        String result = StudyProgressChecklist.format(Arrays.asList(korean, science));
        if (!result.contains("☑ [과학] 오투 · 7-3-1 — 완료 (시험범위 포함)")) throw new AssertionError(result);
        if (!result.contains("☐ [국어] 오투 · 7-3-1 — 진행 중 (시험범위 제외)")) throw new AssertionError(result);
        if (result.indexOf("[과학]") >= result.indexOf("[국어]")) throw new AssertionError("sort broken");
        if (!StudyProgressItem.DONE.equals(science.status) || !StudyProgressItem.IN_PROGRESS.equals(korean.status))
            throw new AssertionError("formatter mutated inputs");
        if (!StudyProgressChecklist.format(Collections.emptyList()).contains("등록된 진도가 없습니다."))
            throw new AssertionError("empty state missing");
        try { StudyProgressChecklist.format(null); throw new AssertionError("null accepted"); }
        catch (IllegalArgumentException expected) { /* pass */ }
        System.out.println("PROGRESS_CHECKLIST_SMOKE_PASSED");
    }
}
