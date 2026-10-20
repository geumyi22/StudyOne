package com.studyone.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Separate schema-1 progress store. Deliberately never touches pre-existing
 * "studyone_v2" task preferences or the schema-1 task backup importer.
 * Bad data fails closed to prevent silent deletion by a subsequent save.
 */
public final class StudyProgressStorage {
    private static final String PREFERENCES = "studyone_progress_v1";
    private static final String KEY = "progress_json";
    private static final int LIMIT = 2000;
    private final SharedPreferences preferences;

    public StudyProgressStorage(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public synchronized List<StudyProgressItem> list() {
        List<StudyProgressItem> out = new ArrayList<>();
        String raw = preferences.getString(KEY, null);
        if (raw == null) return out;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.getInt("schema") != 1) throw new IllegalArgumentException("지원하지 않는 진도 저장 버전");
            JSONArray items = root.getJSONArray("items");
            if (items.length() > LIMIT) throw new IllegalArgumentException("진도 저장 항목 수 초과");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject object = items.getJSONObject(i);
                StudyProgressItem item = new StudyProgressItem(
                        object.getString("id"),
                        object.getString("subject"),
                        object.getString("material"),
                        object.getString("unit"),
                        object.getString("status"),
                        object.getBoolean("inExamScope"),
                        object.getLong("revision"),
                        object.getLong("updatedAt")
                );
                if (!ids.add(item.id)) throw new IllegalArgumentException("진도 ID가 중복되었습니다.");
                out.add(item);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("진도 데이터를 읽지 못했습니다. 원본을 보존하고 수정을 차단합니다.", e);
        }
    }

    public synchronized StudyProgressItem add(String subject, String material,
                                              String unit, String status, boolean scope) {
        List<StudyProgressItem> data = list();
        if (data.size() >= LIMIT) throw new IllegalStateException("진도 항목은 최대 2000개입니다.");
        StudyProgressItem item = StudyProgressItem.create(subject, material, unit, status, scope);
        data.add(item);
        write(data);
        return item;
    }

    public synchronized StudyProgressItem update(String id, long expectedRevision,
                                                 String subject, String material, String unit,
                                                 String status, boolean scope) {
        List<StudyProgressItem> data = list();
        for (int i = 0; i < data.size(); i++) {
            StudyProgressItem current = data.get(i);
            if (!current.id.equals(id)) continue;
            if (current.revision != expectedRevision) {
                throw new IllegalStateException("진도가 다른 화면에서 변경되었습니다. 다시 확인하세요.");
            }
            StudyProgressItem updated = current.edit(subject, material, unit, status, scope);
            data.set(i, updated);
            write(data);
            return updated;
        }
        throw new IllegalStateException("해당 진도 항목을 찾을 수 없습니다.");
    }

    public synchronized void delete(String id, long expectedRevision) {
        List<StudyProgressItem> data = list();
        for (int i = 0; i < data.size(); i++) {
            StudyProgressItem current = data.get(i);
            if (!current.id.equals(id)) continue;
            if (current.revision != expectedRevision) {
                throw new IllegalStateException("수정된 항목은 이전 화면에서 삭제할 수 없습니다.");
            }
            data.remove(i);
            write(data);
            return;
        }
        throw new IllegalStateException("해당 진도 항목을 찾을 수 없습니다.");
    }

    private void write(List<StudyProgressItem> data) {
        try {
            JSONObject root = new JSONObject();
            root.put("schema", 1);
            JSONArray items = new JSONArray();
            for (StudyProgressItem item : data) {
                JSONObject object = new JSONObject();
                object.put("id", item.id);
                object.put("subject", item.subject);
                object.put("material", item.material);
                object.put("unit", item.unit);
                object.put("status", item.status);
                object.put("inExamScope", item.inExamScope);
                object.put("revision", item.revision);
                object.put("updatedAt", item.updatedAt);
                items.put(object);
            }
            root.put("items", items);
            if (!preferences.edit().putString(KEY, root.toString()).commit()) {
                throw new IllegalStateException("진도 저장에 실패했습니다. 다시 시도하세요.");
            }
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("진도 직렬화에 실패했습니다.", e);
        }
    }
}
