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

    /** Exact stored progress bytes, to detect changes after user review. */
    public synchronized String rawProgressForSync() {
        return preferences.getString(KEY, null);
    }

    /**
     * Saves the original progress in app-private storage, then applies new IDs
     * only. Existing progress status/revisions are never overwritten.
     */
    public synchronized String appendNewProgressForSync(
            String original, List<StudyProgressItem> additions,
            Context context) throws Exception {
        if(additions==null || additions.isEmpty())
            throw new IllegalArgumentException("추가할 진도 항목이 없습니다.");
        String now=rawProgressForSync();
        if(!java.util.Objects.equals(now,original))
            throw new IllegalStateException("검토 도중 기기의 진도가 변경되었습니다.");
        List<StudyProgressItem> current=list();
        if(current.size()+additions.size()>LIMIT)
            throw new IllegalArgumentException("진도 저장 제한을 초과합니다.");
        Set<String> ids=new HashSet<>();
        for(StudyProgressItem p:current)ids.add(p.id.toLowerCase(java.util.Locale.ROOT));
        List<StudyProgressItem> merged=new ArrayList<>(current);
        for(StudyProgressItem item:additions){
            if(item==null || !ids.add(item.id.toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("기존 진도 ID를 덮어쓸 수 없습니다.");
            merged.add(item);
        }
        // Fully validate the resulting payload before generating a backup.
        JSONObject next=encode(merged,false);
        String filename="StudyOne-progress-before-sync-"+System.currentTimeMillis()
                +"-"+java.util.UUID.randomUUID()+".json";
        try(java.io.FileOutputStream file=context.openFileOutput(filename,Context.MODE_PRIVATE)){
            file.write((now==null?"":now).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            file.getFD().sync();
        }
        if(!preferences.edit().putString(KEY,next.toString()).commit())
            throw new IllegalStateException("진도 저장 실패. 기존 데이터 백업을 보존했습니다.");
        return filename;
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

    /**
     * Explicit, portable progress-only backup. Separate from legacy task
     * schema 1 so older Android/iOS installations do not import it as tasks.
     */
    public synchronized String exportBackup() {
        try {
            JSONObject envelope = encode(list(), true);
            return envelope.toString(2);
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("진도 백업 생성에 실패했습니다.", error);
        }
    }

    /** Validate an entire imported backup before replacing any saved entries. */
    public static List<StudyProgressItem> parseBackup(String data) {
        if (data == null || data.length() > 2_000_000) {
            throw new IllegalArgumentException("진도 백업은 최대 2MB까지 가져올 수 있습니다.");
        }
        try {
            JSONObject root = new JSONObject(data);
            if (!"studyone-progress".equals(root.getString("format"))
                    || root.getInt("schema") != 1) {
                throw new IllegalArgumentException("지원하지 않는 StudyOne 진도 백업 형식입니다.");
            }
            JSONArray a = root.getJSONArray("items");
            if (a.length() > LIMIT) throw new IllegalArgumentException("진도 백업 항목 수 초과");
            List<StudyProgressItem> parsed = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                StudyProgressItem item = new StudyProgressItem(
                        o.getString("id"), o.getString("subject"), o.getString("material"),
                        o.getString("unit"), o.getString("status"), o.getBoolean("inExamScope"),
                        o.getLong("revision"), o.getLong("updatedAt"));
                if (!ids.add(item.id)) throw new IllegalArgumentException("진도 백업 ID 중복");
                parsed.add(item);
            }
            return parsed;
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException("진도 백업 JSON이 손상되었습니다.", e);
        }
    }

    /** Emulator fixture: exercise portable serialization with no user data writes. */
    public static boolean selfTestBackup() {
        try {
            List<StudyProgressItem> sample = new ArrayList<>();
            StudyProgressItem item = StudyProgressItem.create(
                    "과학", "오투", "7-2", StudyProgressItem.TODO, true);
            sample.add(item);
            String exported = encode(sample, true).toString();
            List<StudyProgressItem> parsed = parseBackup(exported);
            if (parsed.size() != 1) return false;
            StudyProgressItem copy = parsed.get(0);
            if (!copy.id.equals(item.id) || !copy.subject.equals(item.subject)
                    || !copy.material.equals(item.material) || !copy.unit.equals(item.unit)
                    || !copy.status.equals(item.status) || !copy.inExamScope
                    || copy.revision != item.revision || copy.updatedAt != item.updatedAt) return false;
            JSONObject wrongVersion = new JSONObject(exported);
            wrongVersion.put("schema", 2);
            try { parseBackup(wrongVersion.toString()); return false; }
            catch (IllegalArgumentException expected) { /* correct */ }
            JSONObject duplicate = new JSONObject(exported);
            duplicate.getJSONArray("items").put(duplicate.getJSONArray("items").getJSONObject(0));
            try { parseBackup(duplicate.toString()); return false; }
            catch (IllegalArgumentException expected) { /* correct */ }
            return true;
        } catch (Exception unexpected) {
            return false;
        }
    }

    /**
     * Explicit progress-only JSON import with a durable original recovery file.
     * Review the backup first: any concurrent change blocks the replacement.
     * This is deliberately separate from the four-store transfer importer.
     */
    public synchronized String restoreBackupWithRecovery(
            List<StudyProgressItem> imported, String approvedRaw,
            Context context) throws Exception {
        if (!java.util.Objects.equals(rawProgressForSync(), approvedRaw))
            throw new IllegalStateException("검토 중 기기의 진도가 변경됐습니다. 다시 확인하세요.");
        // Parse the exact incoming portable schema again before touching the disk.
        if (imported == null || imported.size() > LIMIT)
            throw new IllegalArgumentException("진도 백업 크기가 올바르지 않습니다.");
        List<StudyProgressItem> verified = parseBackup(encode(imported, true).toString());
        // Fail closed if the old private data is unreadable. Never overwrite corruption.
        String originalPortable = encode(list(), true).toString(2);
        String name = "StudyOne-progress-before-import-"
                + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID() + ".json";
        try (java.io.FileOutputStream out = context.openFileOutput(name, Context.MODE_PRIVATE)) {
            out.write(originalPortable.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        write(verified);
        return name;
    }

    /** Call only after import preview and an explicit user confirmation. */
    public synchronized void restoreBackup(List<StudyProgressItem> imported) {
        if (imported == null || imported.size() > LIMIT) {
            throw new IllegalArgumentException("진도 백업 크기가 올바르지 않습니다.");
        }
        Set<String> ids = new HashSet<>();
        for (StudyProgressItem entry : imported) {
            if (entry == null || !ids.add(entry.id)) {
                throw new IllegalArgumentException("진도 백업에 잘못된 ID가 있습니다.");
            }
        }
        write(new ArrayList<>(imported));
    }

    private static JSONObject encode(List<StudyProgressItem> data, boolean portable)
            throws org.json.JSONException {
        JSONObject root = new JSONObject();
        if (portable) root.put("format", "studyone-progress");
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
        return root;
    }

    private void write(List<StudyProgressItem> data) {
        try {
            JSONObject root = encode(data, false);
            if (!preferences.edit().putString(KEY, root.toString()).commit()) {
                throw new IllegalStateException("진도 저장에 실패했습니다. 다시 시도하세요.");
            }
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("진도 직렬화에 실패했습니다.", e);
        }
    }
}
