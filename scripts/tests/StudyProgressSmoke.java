import com.studyone.app.StudyProgressItem;

public final class StudyProgressSmoke {
    private static void reject(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected input rejection");
    }
    public static void main(String[] args) {
        if (StudyProgressItem.STATUSES.length != 4) throw new AssertionError("Four statuses required");
        if (StudyProgressItem.UNKNOWN.equals(StudyProgressItem.TODO)) {
            throw new AssertionError("Unknown is not the same as incomplete");
        }
        StudyProgressItem record = StudyProgressItem.create(
                "과학", "오투", "7-2", StudyProgressItem.TODO, true);
        if (record.revision != 1 || !record.inExamScope) throw new AssertionError("Creation metadata");
        StudyProgressItem edited = record.edit(
                "과학", "오투", "7-2", StudyProgressItem.DONE, false);
        if (!edited.id.equals(record.id) || edited.revision != 2) throw new AssertionError("Edit identity");
        if (edited.updatedAt <= record.updatedAt || edited.inExamScope ||
                !StudyProgressItem.DONE.equals(edited.status)) {
            throw new AssertionError("Status/scope/timestamp update");
        }
        reject(() -> StudyProgressItem.create("", "오투", "7-2", StudyProgressItem.TODO, true));
        reject(() -> StudyProgressItem.create("과학", "", "7-2", StudyProgressItem.TODO, true));
        reject(() -> StudyProgressItem.create("과학", "오투", " ", StudyProgressItem.TODO, true));
        reject(() -> StudyProgressItem.create("과학", "오투", "7-2", "모름", true));
        reject(() -> new StudyProgressItem("not-a-uuid", "과학", "오투", "7-2",
                StudyProgressItem.TODO, true, 1, 1));
        reject(() -> new StudyProgressItem(record.id, "과학", "오투", "7-2",
                StudyProgressItem.TODO, true, 0, 1));
        System.out.println("StudyProgress model smoke: PASS");
    }
}
