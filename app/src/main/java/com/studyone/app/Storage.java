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

    /**
     * Strict read-only view for an explicit sync upload.
     * The legacy tasks() method tolerates malformed JSON, so NEVER use it to
     * prepare an upload: it can silently return a truncated/empty collection.
     */
    public synchronized List<Models.StudyTask> checkedTasksForSync() {
        try {
            JSONArray data=new JSONArray(p.getString("tasks","[]"));
            if(data.length()>5000)
                throw new IllegalStateException("동기화 일정 수 한도를 초과했습니다.");
            List<Models.StudyTask> verified=new ArrayList<>();
            java.util.Set<Long> ids=new java.util.HashSet<>();
            for(int i=0;i<data.length();i++){
                JSONObject row=data.getJSONObject(i);
                long id=row.getLong("id");
                if(id<=0 || !ids.add(id))
                    throw new IllegalStateException("동기화 일정 ID가 올바르지 않습니다.");
                String type=row.getString("type");
                String subject=row.getString("subject");
                String title=row.getString("title");
                LocalDate date=LocalDate.parse(row.getString("due"));
                int importance=row.getInt("importance");
                boolean completed=row.getBoolean("completed");
                if(type.trim().isEmpty() || type.length()>100 ||
                        subject.length()>60 || title.trim().isEmpty() || title.length()>500 ||
                        importance<1 || importance>3)
                    throw new IllegalStateException("동기화할 일정 필드가 올바르지 않습니다.");
                verified.add(new Models.StudyTask(id,type,subject,title,date,importance,completed));
            }
            return verified;
        }catch(Exception error){
            throw new IllegalStateException(
                    "기존 일정 데이터를 안전하게 읽지 못했습니다. 자동 전송하지 않고 원본을 보존합니다.",
                    error);
        }
    }

    /** Exact original bytes used to detect local modifications during review. */
    public synchronized String rawTasksForSync() {
        return p.getString("tasks", "[]");
    }

    /**
     * Explicit additive import. Keeps a private backup of the original tasks
     * before one synchronous preferences commit; never touches school/API keys.
     * The old task IDs and their content are always retained.
     */
    public synchronized String appendNewTasksForSync(
            String expectedOriginal, List<Models.StudyTask> newEntries,
            Context context) throws Exception {
        if(newEntries==null||newEntries.isEmpty())
            throw new IllegalArgumentException("추가할 일정이 없습니다.");
        List<Models.StudyTask> old=checkedTasksForSync();
        String current=rawTasksForSync();
        if(!current.equals(expectedOriginal))
            throw new IllegalStateException("검토 도중 로컬 일정이 변경되었습니다.");
        if(old.size()+newEntries.size()>5000)
            throw new IllegalStateException("일정 저장 한도를 초과합니다.");
        java.util.Set<Long> ids=new java.util.HashSet<>();
        for(Models.StudyTask item:old)ids.add(item.id);
        List<Models.StudyTask> merged=new ArrayList<>(old);
        for(Models.StudyTask item:newEntries) {
            if(item==null||item.id<=0||!ids.add(item.id))
                throw new IllegalArgumentException("기존 또는 중복 일정 ID를 변경할 수 없습니다.");
            merged.add(item);
        }
        JSONArray payload=new JSONArray();
        for(Models.StudyTask item:merged) {
            JSONObject o=new JSONObject();
            o.put("id",item.id);o.put("type",item.type);o.put("subject",item.subject);
            o.put("title",item.title);o.put("due",item.dueDate.toString());
            o.put("importance",item.importance);o.put("completed",item.completed);
            payload.put(o);
        }
        String next=payload.toString();
        String name="StudyOne-tasks-before-sync-"+System.currentTimeMillis()
                +"-"+java.util.UUID.randomUUID()+".json";
        // app-private backup; if the backup cannot be written, do not import.
        try(java.io.FileOutputStream file=context.openFileOutput(name,Context.MODE_PRIVATE)) {
            file.write(current.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            file.getFD().sync();
        }
        if(!p.edit().putString("tasks",next).commit())
            throw new IllegalStateException("일정 저장 실패. 백업을 보존했습니다.");
        return name;
    }

    /**
     * Add an explicitly approved local paper schedule in one storage commit.
     * Revalidate the exact pre-preview snapshot and semantic duplicate keys
     * UNDER the same synchronized lock as the backup/append implementation.
     * No existing task (including completed ones) is ever overwritten.
     */
    public synchronized String appendReviewedPaperSchedule(
            String expectedOriginal, StudyPaperSchedule.Preview preview, Context context)
            throws Exception {
        if(preview==null||preview.days.isEmpty())
            throw new IllegalArgumentException("저장할 시험지 일정이 없습니다.");
        if(preview.remainingPapers>240)
            throw new IllegalArgumentException("시험지 일정 수가 너무 많습니다.");
        List<Models.StudyTask> originals=checkedTasksForSync();
        if(!rawTasksForSync().equals(expectedOriginal))
            throw new IllegalStateException("계획 미리보기 후 일정이 변경됐습니다. 다시 확인하세요.");
        java.util.Set<String> identities=new java.util.HashSet<>();
        java.util.Set<Long> ids=new java.util.HashSet<>();
        for(Models.StudyTask item:originals){
            ids.add(item.id);
            identities.add(paperTaskKey(item.dueDate,item.type,item.subject,item.title));
        }
        List<Models.StudyTask> additions=new ArrayList<>();
        for(StudyPaperSchedule.Day day:preview.days) {
            if(day==null||day.date==null||day.papers==null)
                throw new IllegalArgumentException("계획에 유효하지 않은 날짜가 있습니다.");
            for(StudyPaperSchedule.Slot slot:day.papers) {
                if(slot==null||slot.lane==null||slot.title==null)
                    throw new IllegalArgumentException("계획 항목이 비어 있습니다.");
                String title="["+slot.lane+"] "+slot.title;
                String type="숙제 항목",subject="국어";
                if(title.length()>500)
                    throw new IllegalArgumentException("계획 제목이 너무 깁니다.");
                String identity=paperTaskKey(day.date,type,subject,title);
                if(!identities.add(identity))
                    throw new IllegalStateException("이미 등록되었거나 완료된 시험지 숙제가 있습니다: "+title
                            +" / "+day.date);
                long id;
                do {id=java.util.concurrent.ThreadLocalRandom.current()
                            .nextLong(1,Long.MAX_VALUE);} while(!ids.add(id));
                additions.add(new Models.StudyTask(id,type,subject,title,day.date,2,false));
            }
        }
        if(additions.size()!=preview.remainingPapers)
            throw new IllegalArgumentException("미리보기와 저장 예정 시험지 수가 다릅니다.");
        // appendNewTasksForSync performs another exact-snapshot check, writes
        // recovery bytes and commits all additions together, or adds none.
        return appendNewTasksForSync(expectedOriginal,additions,context);
    }

    private static String paperTaskKey(LocalDate date,String role,String subject,String title) {
        return date+"|"+role.trim().replaceAll("\\s+"," ")+
                "|"+subject.trim().replaceAll("\\s+"," ")+
                "|"+title.trim().replaceAll("\\s+"," ");
    }

    /**
     * Import-only fail-closed synchronous task replacement; never used to prepare
     * a backup from tolerant tasks(). Preserve all IDs and reject null/duplicates.
     */
    public synchronized void replaceTasksChecked(List<Models.StudyTask> replacement) {
        if(replacement==null || replacement.size()>5000)
            throw new IllegalArgumentException("일정 수가 올바르지 않습니다.");
        JSONArray result=new JSONArray();
        java.util.Set<Long> ids=new java.util.HashSet<>();
        try {
            for(Models.StudyTask item:replacement) {
                if(item==null || item.id<=0 || !ids.add(item.id) ||
                    item.type==null || item.type.trim().isEmpty() || item.type.length()>100 ||
                    item.subject==null || item.subject.length()>60 ||
                    item.title==null || item.title.trim().isEmpty() || item.title.length()>500 ||
                    item.dueDate==null || item.importance<1 || item.importance>3)
                    throw new IllegalArgumentException("일정 값이 잘못되거나 ID가 중복되었습니다.");
                JSONObject row=new JSONObject();
                row.put("id",item.id);row.put("type",item.type);
                row.put("subject",item.subject);row.put("title",item.title);
                row.put("due",item.dueDate.toString());
                row.put("importance",item.importance);
                row.put("completed",item.completed);
                result.put(row);
            }
        }catch(org.json.JSONException e){
            throw new IllegalStateException("복원 일정 직렬화 실패",e);
        }
        if(!p.edit().putString("tasks",result.toString()).commit())
            throw new IllegalStateException("일정 파일 저장에 실패했습니다. 기존 원본 백업을 보존하세요.");
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
