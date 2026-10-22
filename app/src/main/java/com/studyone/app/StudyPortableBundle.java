package com.studyone.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Explicit, user-initiated portable four-section backup for migrating between signed
 * and unsigned StudyOne app IDs. Never reads or exports NEIS/API tokens or passwords.
 *
 * IMPORTANT: Existing section-1 importers are kept unmodified. Cross-store writes are
 * NOT an ACID transaction; before applying any section a complete original bundle is
 * persisted as an app-private recovery file. Caller must report partial failures.
 */
public final class StudyPortableBundle {
    private static final String FORMAT = "studyone-transfer-bundle";
    private static final String[] KEYS = {"tasks", "progress", "details", "wrongAnswers"};
    private static final int MAX_BYTES = 8_200_000;

    private StudyPortableBundle() {}

    public static final class Snapshot {
        public final String tasks, progress, details, wrongAnswers;
        Snapshot(String tasks, String progress, String details, String wrongAnswers) {
            this.tasks=tasks; this.progress=progress; this.details=details;
            this.wrongAnswers=wrongAnswers;
        }
        public boolean identical(Snapshot other) {
            return other != null && tasks.equals(other.tasks) && progress.equals(other.progress)
                    && details.equals(other.details) && wrongAnswers.equals(other.wrongAnswers);
        }
    }

    public static final class Checked {
        public final Snapshot snapshot;
        public final BackupIo.ParsedBackup taskRecords;
        public final List<StudyProgressItem> progressRecords;
        public final List<StudyTaskDetail> detailRecords;
        public final List<StudyWrongAnswers.Entry> wrongRecords;

        Checked(Snapshot snapshot, BackupIo.ParsedBackup tasks,
                List<StudyProgressItem> progress, List<StudyTaskDetail> details,
                List<StudyWrongAnswers.Entry> wrong) {
            this.snapshot=snapshot; this.taskRecords=tasks;
            this.progressRecords=progress; this.detailRecords=details; this.wrongRecords=wrong;
        }
        public String summary() {
            return "일정 "+taskRecords.tasks.size()+"개 · 진도 "+progressRecords.size()
                    +"개 · 학습 상세 "+detailRecords.size()+"개 · 오답 "+wrongRecords.size()+"개";
        }
    }

    public static Snapshot capture(Storage tasks, StudyProgressStorage progress,
                                   StudyTaskDetailStorage details,
                                   StudyWrongAnswers wrong) throws Exception {
        return new Snapshot(BackupIo.exportData(tasks), progress.exportBackup(),
                details.exportBackup(), wrong.exportJson());
    }

    public static Checked validate(Snapshot sample) throws Exception {
        if (sample == null) throw new IllegalArgumentException("백업 데이터가 없습니다.");
        return new Checked(sample,
                BackupIo.parse(sample.tasks),
                StudyProgressStorage.parseBackup(sample.progress),
                StudyTaskDetailStorage.parseBackup(sample.details),
                StudyWrongAnswers.parseBackup(sample.wrongAnswers));
    }

    private static String digest(String text) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder(bytes.length*2);
        for (byte b:bytes) hex.append(String.format(java.util.Locale.ROOT,"%02x",b&0xff));
        return hex.toString();
    }

    public static String encode(Snapshot source) throws Exception {
        validate(source);
        String[] values={source.tasks,source.progress,source.details,source.wrongAnswers};
        JSONObject parts=new JSONObject(), checksums=new JSONObject();
        for(int i=0;i<KEYS.length;i++) {
            parts.put(KEYS[i],values[i]);
            checksums.put(KEYS[i],digest(values[i]));
        }
        JSONObject root=new JSONObject();
        root.put("format",FORMAT);
        root.put("schema",1);
        root.put("sections",parts);
        root.put("sha256",checksums);
        String encoded=root.toString(2);
        if(encoded.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw new IllegalArgumentException("통합 백업은 최대 8.2MB입니다.");
        return encoded;
    }

    public static Checked parse(String raw) throws Exception {
        if(raw==null || raw.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw new IllegalArgumentException("백업이 비어 있거나 8.2MB를 초과합니다.");
        JSONObject root=new JSONObject(raw);
        if(root.length()!=4 || !FORMAT.equals(root.getString("format")) ||
                root.getInt("schema")!=1)
            throw new IllegalArgumentException("지원하지 않는 통합 백업 형식입니다.");
        JSONObject parts=root.getJSONObject("sections"),checksums=root.getJSONObject("sha256");
        if(parts.length()!=KEYS.length || checksums.length()!=KEYS.length)
            throw new IllegalArgumentException("통합 백업 항목 개수가 잘못됐습니다.");
        String[] values=new String[KEYS.length];
        for(int i=0;i<KEYS.length;i++) {
            if(!parts.has(KEYS[i]) || !checksums.has(KEYS[i]))
                throw new IllegalArgumentException("필수 백업 항목이 없습니다: "+KEYS[i]);
            values[i]=parts.getString(KEYS[i]);
            if(!digest(values[i]).equals(checksums.getString(KEYS[i])))
                throw new IllegalArgumentException("백업 무결성 오류: "+KEYS[i]);
        }
        return validate(new Snapshot(values[0],values[1],values[2],values[3]));
    }

    /**
     * Every section is validated BEFORE writing and originals are backed up privately.
     * The user explicitly approves this replacement. On partial errors, saved original
     * recovery bundle is preserved and the error is propagated (never report success).
     */
    public static String restoreWithRecovery(Context context,
            Storage tasks, StudyProgressStorage progress, StudyTaskDetailStorage details,
            StudyWrongAnswers wrong, Checked incoming, Snapshot approvedCurrent)
            throws Exception {
        if(incoming==null || approvedCurrent==null)
            throw new IllegalArgumentException("확인된 데이터가 없습니다.");
        // Freshly revalidate incoming and original; reject if modified after preview.
        Checked verified=parse(encode(incoming.snapshot));
        Snapshot original=capture(tasks,progress,details,wrong);
        if(!original.identical(approvedCurrent))
            throw new IllegalStateException("확인하는 동안 학습 데이터가 변경됐습니다. 다시 시작하세요.");
        validate(original);
        String name="StudyOne-transfer-before-import-"+UUID.randomUUID()+".json";
        String backup=encode(original);
        try(FileOutputStream out=context.openFileOutput(name,Context.MODE_PRIVATE)) {
            out.write(backup.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        // Each established importer has its own validation. Restore all, not selectively.
        // Partial failure is NOT silently rolled back: original private recovery stays intact.
        progress.restoreBackup(verified.progressRecords);
        details.restoreBackup(verified.detailRecords);
        wrong.restore(verified.wrongRecords);
        BackupIo.restore(tasks,verified.taskRecords);
        return name;
    }

    public static boolean selfTest() {
        try {
            Snapshot empty=new Snapshot(
                "{\"format\":\"studyone-backup\",\"schema\":1,\"grade\":3,\"className\":\"1\",\"tasks\":[]}",
                "{\"format\":\"studyone-progress\",\"schema\":1,\"items\":[]}",
                "{\"format\":\"studyone-task-details\",\"schema\":1,\"items\":[]}",
                "{\"format\":\"studyone-wrong-answers\",\"schema\":1,\"items\":[]}");
            String encoded=encode(empty);
            if(!parse(encoded).snapshot.identical(empty))return false;
            JSONObject forged=new JSONObject(encoded);
            forged.getJSONObject("sections").put("wrongAnswers",
                    "{\"format\":\"studyone-wrong-answers\",\"schema\":1,\"items\":[{}]}");
            try {parse(forged.toString());return false;}
            catch(Exception expected) { /* modified digest rejected */ }
            JSONObject version=new JSONObject(encoded);
            version.put("schema",2);
            try {parse(version.toString());return false;}
            catch(Exception expected) { /* fail closed */ }
            return true;
        } catch(Exception ignored) {return false;}
    }
}
