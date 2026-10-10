package com.studyone.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * User-initiated, local-only JSON backups. API keys are deliberately excluded.
 * The SAF file picker can save to the user's own cloud storage provider.
 */
public final class BackupIo {
    private BackupIo() {}

    public static String exportData(Storage storage) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "studyone-backup");
        root.put("schema", 1);
        root.put("exportedAt", LocalDate.now().toString());
        root.put("grade", storage.grade());
        root.put("className", storage.className());
        Models.School s = storage.school();
        if (s != null) {
            JSONObject o = new JSONObject();
            o.put("eduCode", s.eduCode);
            o.put("schoolCode", s.schoolCode);
            o.put("name", s.name);
            o.put("kind", s.kind);
            o.put("address", s.address);
            root.put("school", o);
        }
        JSONArray tasks = new JSONArray();
        for (Models.StudyTask t : storage.tasks()) {
            JSONObject o = new JSONObject();
            o.put("id", t.id);
            o.put("type", t.type);
            o.put("subject", t.subject);
            o.put("title", t.title);
            o.put("dueDate", t.dueDate.toString());
            o.put("importance", t.importance);
            o.put("completed", t.completed);
            tasks.put(o);
        }
        root.put("tasks", tasks);
        return root.toString(2);
    }

    public static final class ParsedBackup {
        public final List<Models.StudyTask> tasks;
        public final Models.School school;
        public final int grade;
        public final String className;

        private ParsedBackup(List<Models.StudyTask> tasks, Models.School school,
                             int grade, String className) {
            this.tasks = tasks;
            this.school = school;
            this.grade = grade;
            this.className = className;
        }
    }

    public static ParsedBackup parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (!"studyone-backup".equals(root.getString("format"))
                || root.getInt("schema") != 1) {
            throw new IllegalArgumentException("지원되지 않는 StudyOne 백업 형식입니다.");
        }
        int grade = root.getInt("grade");
        String className = root.getString("className").trim();
        if (grade < 1 || grade > 6 || !className.matches("[0-9]{1,3}")) {
            throw new IllegalArgumentException("백업 학년 또는 반 값이 잘못됐습니다.");
        }
        JSONObject so = root.optJSONObject("school");
        Models.School school = null;
        if (so != null) {
            school = new Models.School(so.getString("eduCode"),
                    so.getString("schoolCode"), so.getString("name"),
                    so.getString("kind"), so.getString("address"));
            if (school.schoolCode.isEmpty() || school.eduCode.isEmpty()) {
                throw new IllegalArgumentException("학교 정보가 올바르지 않습니다.");
            }
        }
        JSONArray array = root.getJSONArray("tasks");
        if (array.length() > 5000) throw new IllegalArgumentException("백업 일정이 너무 많습니다.");
        List<Models.StudyTask> tasks = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            String title = o.getString("title").trim();
            if (title.isEmpty() || title.length() > 500) {
                throw new IllegalArgumentException("일정 제목이 올바르지 않습니다.");
            }
            int imp = o.getInt("importance");
            if (imp < 1 || imp > 3) throw new IllegalArgumentException("중요도 값이 올바르지 않습니다.");
            tasks.add(new Models.StudyTask(o.getLong("id"), o.getString("type"),
                    o.getString("subject"), title, LocalDate.parse(o.getString("dueDate")),
                    imp, o.getBoolean("completed")));
        }
        return new ParsedBackup(tasks, school, grade, className);
    }

    public static void restore(Storage storage, ParsedBackup data) {
        // Validate all fields in parse() before mutating saved data.
        if (data.school != null) storage.saveSchool(data.school);
        storage.saveGradeClass(data.grade, data.className);
        storage.saveTasks(data.tasks);
        storage.clearSchoolCaches();
    }
}
