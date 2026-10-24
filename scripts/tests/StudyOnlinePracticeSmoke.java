import com.studyone.app.StudyOnlinePractice;
import com.studyone.app.StudyProgressItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class StudyOnlinePracticeSmoke {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        List<StudyProgressItem> empty = new ArrayList<>();
        List<String> all = StudyOnlinePractice.missingSubjects(empty);
        require(all.equals(Arrays.asList("국어", "사회", "역사", "과학", "영어", "수학")),
                "Unexpected six-subject ordering");
        List<StudyProgressItem> first = StudyOnlinePractice.createMissing(empty);
        require(first.size() == 6, "New library should get six entries");
        require(empty.isEmpty(), "Planning must not mutate existing list");
        for (StudyProgressItem p : first) {
            require(p.status.equals(StudyProgressItem.TODO) && p.inExamScope,
                    "New entries must be pending and in exam scope");
            require(StudyOnlinePractice.UNIT.equals(p.unit), "Wrong unit");
        }
        require(StudyOnlinePractice.missingSubjects(first).isEmpty(),
                "Repeated request must skip all existing subjects");

        StudyProgressItem social = StudyProgressItem.create("사회", "학교 자료·추가 문제",
                StudyOnlinePractice.UNIT, StudyProgressItem.IN_PROGRESS, true);
        StudyProgressItem korean = StudyProgressItem.create("국어", "족보닷컴",
                StudyOnlinePractice.UNIT, StudyProgressItem.DONE, true);
        List<StudyProgressItem> current = new ArrayList<>(Arrays.asList(social, korean));
        List<String> missing = StudyOnlinePractice.missingSubjects(current);
        require(missing.equals(Arrays.asList("역사", "과학", "영어", "수학")),
                "Both completed and in-progress existing records should be skipped");
        List<StudyProgressItem> additions = StudyOnlinePractice.createMissing(current);
        require(additions.size() == 4, "Only absent subjects must be created");
        require(current.size() == 2 && current.get(0) == social && current.get(1) == korean
                    && social.status.equals(StudyProgressItem.IN_PROGRESS)
                    && korean.status.equals(StudyProgressItem.DONE),
                "Existing object references, statuses and list must be unchanged");
        current.addAll(additions);
        require(StudyOnlinePractice.createMissing(current).isEmpty(),
                "Second application must be idempotent");
        try {
            StudyOnlinePractice.missingSubjects(null);
            throw new AssertionError("Null list must fail closed");
        } catch (IllegalArgumentException expected) { /* correct */ }
        current.add(null);
        try {
            StudyOnlinePractice.missingSubjects(current);
            throw new AssertionError("Corrupt entries must fail closed");
        } catch (IllegalArgumentException expected) { /* correct */ }
        System.out.println("StudyOne online practice six-subject smoke: PASS");
    }
}
