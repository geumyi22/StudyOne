package com.studyone.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local-only, separately backed-up optional task details; never part of v1 task or sync payloads. */
public final class StudyTaskDetailStorage {
    private static final String FORMAT = "studyone-task-details";
    private static final int MAX_ITEMS = 5000;
    private final SharedPreferences prefs;
    private final Context context;

    public StudyTaskDetailStorage(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("studyone_task_details_v1", Context.MODE_PRIVATE);
    }

    private static String key(long id) { return "task_" + id; }

    private static JSONObject toJson(StudyTaskDetail detail) throws Exception {
        JSONObject out = new JSONObject();
        out.put("taskId", detail.taskId);
        out.put("estimatedMinutes", detail.estimatedMinutes == null
                ? JSONObject.NULL : detail.estimatedMinutes);
        JSONArray steps = new JSONArray();
        for (String step : detail.steps) steps.put(step);
        out.put("steps", steps);
        out.put("completionCriteria", detail.completionCriteria);
        return out;
    }

    private static StudyTaskDetail parseItem(JSONObject json) throws Exception {
        if (json.length() != 4 || !json.has("taskId") ||
                !json.has("estimatedMinutes") || !json.has("steps") ||
                !json.has("completionCriteria"))
            throw new IllegalArgumentException("학습 상세 정보의 필드가 올바르지 않습니다.");
        Object duration = json.get("estimatedMinutes");
        Integer minutes = null;
        if (duration != JSONObject.NULL) {
            if (!(duration instanceof Integer) && !(duration instanceof Long))
                throw new IllegalArgumentException("예상 시간 형식이 올바르지 않습니다.");
            long number = ((Number) duration).longValue();
            if (number < 1 || number > 600)
                throw new IllegalArgumentException("예상 시간 범위가 올바르지 않습니다.");
            minutes = (int) number;
        }
        JSONArray values = json.getJSONArray("steps");
        if (values.length() > 20) throw new IllegalArgumentException("공부 단계가 너무 많습니다.");
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) steps.add(values.getString(i));
        return new StudyTaskDetail(json.getLong("taskId"), minutes,
                steps, json.getString("completionCriteria"));
    }

    public synchronized StudyTaskDetail get(long taskId) {
        if (taskId <= 0) throw new IllegalArgumentException("학습 일정 ID가 올바르지 않습니다.");
        String raw = prefs.getString(key(taskId), null);
        if (raw == null) return null;
        try {
            StudyTaskDetail found = parseItem(new JSONObject(raw));
            if (found.taskId != taskId) throw new IllegalStateException("상세 정보 ID 불일치");
            return found;
        } catch (Exception error) {
            throw new IllegalStateException("학습 상세 정보가 손상됐습니다. 기존 원본은 보존했습니다.",error);
        }
    }

    public synchronized void save(StudyTaskDetail detail) {
        if (detail == null) throw new IllegalArgumentException("학습 상세 정보가 없습니다.");
        try {
            SharedPreferences.Editor editor = prefs.edit();
            if (detail.isEmpty()) editor.remove(key(detail.taskId));
            else editor.putString(key(detail.taskId), toJson(detail).toString());
            if (!editor.commit()) throw new IllegalStateException("학습 상세 정보 저장 실패");
        } catch (Exception error) {
            throw new IllegalStateException("학습 상세 정보 저장 실패. 다시 확인하세요.",error);
        }
    }

    public synchronized String exportBackup() throws Exception {
        List<StudyTaskDetail> items = all();
        JSONObject doc = new JSONObject();
        doc.put("format", FORMAT);
        doc.put("schema", 1);
        JSONArray array = new JSONArray();
        for (StudyTaskDetail item : items) array.put(toJson(item));
        doc.put("items", array);
        String out = doc.toString(2);
        if (out.getBytes(StandardCharsets.UTF_8).length > 2_000_000)
            throw new IllegalArgumentException("학습 상세 백업은 최대 2MB입니다.");
        return out;
    }

    private List<StudyTaskDetail> all() {
        Map<String, ?> map = prefs.getAll();
        if (map.size() > MAX_ITEMS) throw new IllegalStateException("상세 정보 항목 한도 초과");
        List<StudyTaskDetail> records = new ArrayList<>();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            if (!entry.getKey().startsWith("task_") || !(entry.getValue() instanceof String))
                throw new IllegalStateException("알 수 없는 상세 정보 저장 형식");
            long id;
            try { id = Long.parseLong(entry.getKey().substring(5)); }
            catch (NumberFormatException ex) { throw new IllegalStateException("상세 정보 ID 손상", ex); }
            StudyTaskDetail record = get(id);
            if (record != null) records.add(record);
        }
        Collections.sort(records, (a, b) -> Long.compare(a.taskId,b.taskId));
        return records;
    }

    public static List<StudyTaskDetail> parseBackup(String raw) throws Exception {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 2_000_000)
            throw new IllegalArgumentException("학습 상세 백업은 최대 2MB입니다.");
        JSONObject doc = new JSONObject(raw);
        if (doc.length() != 3 || !FORMAT.equals(doc.getString("format")) ||
                doc.getInt("schema") != 1)
            throw new IllegalArgumentException("지원하지 않는 상세 백업 형식입니다.");
        JSONArray array = doc.getJSONArray("items");
        if (array.length() > MAX_ITEMS) throw new IllegalArgumentException("백업 항목 한도 초과");
        List<StudyTaskDetail> items = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            StudyTaskDetail item = parseItem(array.getJSONObject(i));
            if (item.isEmpty() || !ids.add(item.taskId))
                throw new IllegalArgumentException("빈 항목 또는 중복된 상세 정보 ID");
            items.add(item);
        }
        return items;
    }

    /** Explicit replace after user confirmation; records previous exact data in private files first. */
    public synchronized String restoreBackup(List<StudyTaskDetail> verified) throws Exception {
        if (verified == null || verified.size() > MAX_ITEMS)
            throw new IllegalArgumentException("백업 항목이 올바르지 않습니다.");
        String original = exportBackup(); // fails closed on existing corruption
        JSONObject next = new JSONObject();
        next.put("format", FORMAT); next.put("schema", 1);
        JSONArray array = new JSONArray();
        Set<Long> ids = new HashSet<>();
        for (StudyTaskDetail item : verified) {
            if (item == null || item.isEmpty() || !ids.add(item.taskId))
                throw new IllegalArgumentException("백업 항목 ID가 중복되거나 비어 있습니다.");
            array.put(toJson(item));
        }
        next.put("items",array);
        String target = next.toString(2);
        if (target.getBytes(StandardCharsets.UTF_8).length > 2_000_000)
            throw new IllegalArgumentException("복원 데이터가 너무 큽니다.");
        String backupName = "StudyOne-details-before-import-" +
                System.currentTimeMillis() + "-" + java.util.UUID.randomUUID() + ".json";
        try (FileOutputStream output = context.openFileOutput(backupName,Context.MODE_PRIVATE)) {
            output.write(original.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (StudyTaskDetail item : verified) editor.putString(key(item.taskId),toJson(item).toString());
        if (!editor.commit()) throw new IllegalStateException("상세 정보 복원 저장 실패");
        return backupName;
    }
}
