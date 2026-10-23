import com.studyone.app.StudyCategories;
import java.util.Arrays;

/** Plain JDK test: permanent role metadata and legacy type compatibility. */
public final class StudyCategoriesSmoke {
    public static void main(String[] args) {
        if (StudyCategories.ROLE_TITLES.length != 5
                || StudyCategories.ROLE_DESCRIPTIONS.length != 5) {
            throw new AssertionError("Expected five study roles and five descriptions");
        }
        if (!StudyCategories.isRole("숙제 항목") || StudyCategories.isRole("과제")) {
            throw new AssertionError("Legacy and new category boundaries changed");
        }
        String[] old = StudyCategories.taskTypes("과제");
        if (Arrays.stream(old).filter("과제"::equals).count() != 1) {
            throw new AssertionError("Legacy types must remain without duplicates");
        }
        String[] custom = StudyCategories.taskTypes("나만의 분류");
        if (!"나만의 분류".equals(custom[custom.length - 1])) {
            throw new AssertionError("Unknown saved types must survive editing");
        }
        String[] blank = StudyCategories.taskTypes(" ");
        if (Arrays.asList(blank).contains(" ")) {
            throw new AssertionError("Blank option should not be added");
        }
        if (!StudyCategories.guide().contains("시험범위 전체 오답")) {
            throw new AssertionError("Missing permanent review policy");
        }
        System.out.println("StudyCategories smoke: PASS");
    }
}
