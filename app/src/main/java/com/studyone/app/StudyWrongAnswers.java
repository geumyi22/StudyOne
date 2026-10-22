package com.studyone.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Separate, offline-only wrong-answer notebook. Never sends private notes to AI/sync. */
public final class StudyWrongAnswers {
    private static final String FORMAT = "studyone-wrong-answers";
    private static final int LIMIT = 1000;
    private final Context context;
    private final SharedPreferences prefs;

    public StudyWrongAnswers(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("studyone_wrong_answers_v1", Context.MODE_PRIVATE);
    }

    public static final class Entry {
        public final String id, subject, material, topic, correction, nextReview;
        public final int retries;
        public final boolean mastered;

        public Entry(String id, String subject, String material, String topic,
                     String correction, String nextReview, int retries, boolean mastered) {
            if (id == null || !id.equals(id.toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("오답 ID가 올바르지 않습니다.");
            try { UUID.fromString(id); }
            catch (Exception e) { throw new IllegalArgumentException("오답 ID는 UUID여야 합니다."); }
            this.id = id;
            this.subject = check(subject, 60, "과목");
            this.material = check(material, 120, "교재");
            this.topic = check(topic, 500, "틀린 문제 또는 개념");
            this.correction = check(correction, 1000, "틀린 이유와 정답 근거");
            if (retries < 0 || retries > 50) throw new IllegalArgumentException("재풀이 횟수 범위 초과");
            try {
                LocalDate date = LocalDate.parse(nextReview);
                if (!date.toString().equals(nextReview)) throw new IllegalArgumentException();
            } catch (Exception e) { throw new IllegalArgumentException("복습일 형식은 YYYY-MM-DD입니다."); }
            this.nextReview = nextReview;
            this.retries = retries;
            this.mastered = mastered;
        }

        private static String check(String value, int max, String field) {
            if (value == null || value.trim().isEmpty() || value.length() > max || !value.equals(value.trim()))
                throw new IllegalArgumentException(field + "은(는) 1~" + max + "자여야 합니다.");
            return value;
        }
    }

    private static JSONObject object(Entry e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", e.id);
        o.put("subject", e.subject);
        o.put("material", e.material);
        o.put("topic", e.topic);
        o.put("correction", e.correction);
        o.put("nextReview", e.nextReview);
        o.put("retries", e.retries);
        o.put("mastered", e.mastered);
        return o;
    }

    private static Entry parse(JSONObject o) throws Exception {
        if (o.length() != 8) throw new IllegalArgumentException("오답 필드 개수가 올바르지 않습니다.");
        return new Entry(o.getString("id"), o.getString("subject"), o.getString("material"),
                o.getString("topic"), o.getString("correction"), o.getString("nextReview"),
                o.getInt("retries"), o.getBoolean("mastered"));
    }

    public synchronized List<Entry> list() {
        if (prefs.getAll().size() > LIMIT) throw new IllegalStateException("오답 항목 한도 초과");
        List<Entry> records = new ArrayList<>();
        for (java.util.Map.Entry<String, ?> row : prefs.getAll().entrySet()) {
            if (!row.getKey().startsWith("note_") || !(row.getValue() instanceof String))
                throw new IllegalStateException("오답 원본 형식이 손상됐습니다.");
            try {
                Entry e = parse(new JSONObject((String) row.getValue()));
                if (!row.getKey().equals("note_" + e.id)) throw new IllegalStateException("오답 ID 불일치");
                records.add(e);
            } catch (Exception error) {
                throw new IllegalStateException("오답 원본에 문제가 있습니다. 변경을 차단하고 보존합니다.", error);
            }
        }
        Collections.sort(records, (a,b)->a.nextReview.compareTo(b.nextReview));
        return records;
    }

    public synchronized void save(Entry item) {
        List<Entry> all = list(); // fail closed if existing records are invalid
        if (all.size() >= LIMIT && all.stream().noneMatch(e -> e.id.equals(item.id)))
            throw new IllegalStateException("오답 최대 1000개");
        try {
            if (!prefs.edit().putString("note_" + item.id, object(item).toString()).commit())
                throw new IllegalStateException("오답 저장에 실패했습니다.");
        } catch (Exception e) { throw new IllegalStateException("오답 저장 실패",e); }
    }

    public synchronized String exportJson() throws Exception {
        JSONObject doc = new JSONObject();
        doc.put("format", FORMAT);
        doc.put("schema", 1);
        JSONArray items = new JSONArray();
        for (Entry e : list()) items.put(object(e));
        doc.put("items", items);
        String out = doc.toString(2);
        if (out.getBytes(StandardCharsets.UTF_8).length > 2_000_000)
            throw new IllegalStateException("오답 백업은 최대 2MB입니다.");
        return out;
    }

    public static List<Entry> parseBackup(String raw) throws Exception {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 2_000_000)
            throw new IllegalArgumentException("오답 백업은 최대 2MB입니다.");
        JSONObject obj = new JSONObject(raw);
        if (obj.length() != 3 || !FORMAT.equals(obj.getString("format")) || obj.getInt("schema") != 1)
            throw new IllegalArgumentException("오답 백업 형식 오류");
        JSONArray rows = obj.getJSONArray("items");
        if (rows.length() > LIMIT) throw new IllegalArgumentException("오답 1000개 초과");
        List<Entry> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for(int i=0;i<rows.length();i++){
            Entry entry = parse(rows.getJSONObject(i));
            if (!ids.add(entry.id)) throw new IllegalArgumentException("오답 ID 중복");
            out.add(entry);
        }
        return out;
    }

    /** Explicit replacement only after caller's user confirmation; original bytes are saved first. */
    public synchronized void restore(List<Entry> entries) throws Exception {
        String old = exportJson();
        Set<String> ids = new HashSet<>();
        if(entries == null || entries.size() > LIMIT) throw new IllegalArgumentException("오답 항목 초과");
        for(Entry e:entries) if (e == null || !ids.add(e.id)) throw new IllegalArgumentException("오답 ID 중복");
        String backupName = "StudyOne-wrong-answers-before-import-" + UUID.randomUUID() + ".json";
        try (FileOutputStream os=context.openFileOutput(backupName,Context.MODE_PRIVATE)) {
            os.write(old.getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
        }
        SharedPreferences.Editor edit=prefs.edit().clear();
        for (Entry e:entries) edit.putString("note_" + e.id,object(e).toString());
        if (!edit.commit()) throw new IllegalStateException("오답 복원 저장 실패");
    }
}
