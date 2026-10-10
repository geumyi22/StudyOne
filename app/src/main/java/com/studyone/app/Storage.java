package com.studyone.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Storage {
    private final SharedPreferences p;

    public Storage(Context context) {
        p = context.getSharedPreferences("studyone_v2", Context.MODE_PRIVATE);
    }

    public String apiKey() { return p.getString("api_key", ""); }
    public int grade() { return p.getInt("grade", 1); }
    public String className() { return p.getString("class_name", "1"); }

    public void saveApiKey(String value) {
        p.edit().putString("api_key", value == null ? "" : value.trim()).apply();
    }

    public void saveGradeClass(int grade, String className) {
        p.edit().putInt("grade", grade)
                .putString("class_name", className == null ? "1" : className.trim()).apply();
        clearSchoolCaches();
    }

    public Models.School school() {
        String code = p.getString("school_code", "");
        if (code == null || code.isEmpty()) return null;
        return new Models.School(
                p.getString("edu_code", ""),
                code,
                p.getString("school_name", ""),
                p.getString("school_kind", ""),
                p.getString("school_address", "")
        );
    }

    public void saveSchool(Models.School s) {
        p.edit()
                .putString("edu_code", s.eduCode)
                .putString("school_code", s.schoolCode)
                .putString("school_name", s.name)
                .putString("school_kind", s.kind)
                .putString("school_address", s.address)
                .apply();
        clearSchoolCaches();
    }

    public synchronized boolean shouldAutoFetch(String key, String identity, long maxAgeMinutes) {
        String raw = getCache(key, identity);
        if (!raw.isEmpty() && cacheAgeMinutes(key) < maxAgeMinutes) return false;
        String prefix = "attempt_" + key;
        String lastIdentity = p.getString(prefix + "_identity", "");
        long lastAt = p.getLong(prefix + "_timestamp", 0L);
        long now = System.currentTimeMillis();
        if (identity.equals(lastIdentity) && lastAt > 0 && now - lastAt >= 0 && now - lastAt < 10 * 60_000L) {
            return false; // Back off for ten minutes after errors or empty results.
        }
        p.edit().putString(prefix + "_identity", identity)
                .putLong(prefix + "_timestamp", now).apply();
        return true;
    }

    public String lastError(String key) { return p.getString("error_" + key, ""); }
    public void saveLastError(String key, String message) {
        p.edit().putString("error_" + key, message == null ? "" : message).apply();
    }

    public void updateTask(Models.StudyTask edited) {
        List<Models.StudyTask> updated = new ArrayList<>();
        for (Models.StudyTask t : tasks()) updated.add(t.id == edited.id ? edited : t);
        saveTasks(updated);
    }

    public void putCache(String key, String identity, String json) {
        p.edit().putString("cache_" + key, json)
                .putString("cache_" + key + "_identity", identity)
                .putLong("cache_" + key + "_ts", System.currentTimeMillis()).apply();
    }

    public String getCache(String key, String identity) {
        String storedIdentity = p.getString("cache_" + key + "_identity", "");
        if (!identity.equals(storedIdentity)) return "";
        return p.getString("cache_" + key, "");
    }

    public long cacheAgeMinutes(String key) {
        long ts = p.getLong("cache_" + key + "_ts", 0L);
        return ts == 0L ? Long.MAX_VALUE :
                Math.max(0L, (System.currentTimeMillis() - ts) / 60000L);
    }

    public void clearSchoolCaches() {
        p.edit()
                .remove("cache_timetable").remove("cache_timetable_identity").remove("cache_timetable_ts")
                .remove("cache_meals").remove("cache_meals_identity").remove("cache_meals_ts")
                .remove("attempt_timetable_identity").remove("attempt_timetable_timestamp")
                .remove("attempt_meals_identity").remove("attempt_meals_timestamp")
                .remove("error_timetable").remove("error_meals")
                .apply();
    }

    public List<Models.StudyTask> tasks() {
        List<Models.StudyTask> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("tasks", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Models.StudyTask(
                        o.getLong("id"),
                        o.optString("type", "과제"),
                        o.optString("subject", ""),
                        o.optString("title", ""),
                        LocalDate.parse(o.getString("due")),
                        o.optInt("importance", 2),
                        o.optBoolean("completed", false)
                ));
            }
        } catch (Exception ignored) {}
        out.sort(Comparator.comparing(t -> t.dueDate));
        return out;
    }

    public void saveTasks(List<Models.StudyTask> tasks) {
        JSONArray a = new JSONArray();
        for (Models.StudyTask t : tasks) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", t.id);
                o.put("type", t.type);
                o.put("subject", t.subject);
                o.put("title", t.title);
                o.put("due", t.dueDate.toString());
                o.put("importance", t.importance);
                o.put("completed", t.completed);
                a.put(o);
            } catch (Exception ignored) {}
        }
        p.edit().putString("tasks", a.toString()).apply();
    }

    public void addTask(Models.StudyTask task) {
        List<Models.StudyTask> list = tasks();
        list.add(task);
        saveTasks(list);
    }

    public void setTaskCompleted(long id, boolean completed) {
        List<Models.StudyTask> list = tasks();
        List<Models.StudyTask> updated = new ArrayList<>();
        for (Models.StudyTask t : list)
            updated.add(t.id == id ? t.withCompleted(completed) : t);
        saveTasks(updated);
    }

    public void deleteTask(long id) {
        List<Models.StudyTask> list = tasks();
        list.removeIf(t -> t.id == id);
        saveTasks(list);
    }
}
