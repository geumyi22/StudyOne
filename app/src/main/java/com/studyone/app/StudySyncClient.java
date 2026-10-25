package com.studyone.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Development-only transport for a server running on this device's loopback.
 * Android Emulator: "adb reverse tcp:8765 tcp:8765" before testing against
 * the developer workstation. NEVER expose plain HTTP to LAN/Internet.
 *
 * No background requests, persistent tokens, server-to-local writes or
 * updates/deletes of existing server records.
 */
public final class StudySyncClient {
    private static final String BASE = "http://127.0.0.1:8765/";

    /** Only explicit HTTPS hosts or the test loopback HTTP address are allowed. */
    public static String checkedAddress(String address) {
        try {
            java.net.URI uri = new java.net.URI(address);
            if (uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getRawPath() == null
                    || !uri.getRawPath().equals("/")
                    || uri.getPort() > 65535 || uri.getPort() == 0)
                throw new IllegalArgumentException("서버 주소는 HTTPS 루트 주소여야 합니다.");
            String protocol=uri.getScheme();
            boolean local="127.0.0.1".equals(uri.getHost())
                    || "localhost".equals(uri.getHost())
                    || "::1".equals(uri.getHost());
            if("http".equalsIgnoreCase(protocol)) {
                if(!local || uri.getPort()!=8765)
                    throw new IllegalArgumentException("HTTP는 로컬 8765 포트 실험만 허용합니다.");
            }else if(!"https".equalsIgnoreCase(protocol)){
                throw new IllegalArgumentException("외부 서버에는 HTTPS가 필요합니다.");
            }
            return uri.toASCIIString();
        }catch(java.net.URISyntaxException error) {
            throw new IllegalArgumentException("유효한 HTTPS 주소를 입력하세요.",error);
        }
    }

    /**
     * Explicit HTTPS-only account server address for production-visible screens.
     * Legacy loopback HTTP is strictly limited to developer test tools.
     */
    public static String checkedAccountAddress(String address) {
        String normalized=checkedAddress(address);
        if(!normalized.startsWith("https://"))
            throw new IllegalArgumentException("계정 서버는 HTTPS로만 연결할 수 있습니다.");
        return normalized;
    }

    private static String apiPrefix(String base) {
        return base.startsWith("http://") ? "v1/" : "v2/";
    }
    private static final int MAX_RESPONSE = 4_000_000;
    private static final int BATCH = 50;
    private StudySyncClient() {}

    private static void validateToken(String token) {
        if (token == null || token.length() < 32 || token.length() > 1024)
            throw new IllegalArgumentException("로컬 테스트 토큰은 32~1024자여야 합니다.");
        for (int i=0; i<token.length(); i++)
            if (token.charAt(i) <= 32 || token.charAt(i) >= 127)
                throw new IllegalArgumentException("토큰에 허용되지 않는 문자가 있습니다.");
    }

    private static JSONObject call(String token, String endpoint, JSONObject body,
                                   String address) throws Exception {
        validateToken(token);
        String base=checkedAddress(address);
        boolean ordinary=endpoint.equals("snapshot") || endpoint.equals("changes") || endpoint.equals("session");
        boolean pending=endpoint.equals("proposals");
        boolean decision=endpoint.matches(
                "proposals/[a-fA-F0-9-]{36}/(approve|reject)");
        if(!ordinary && !pending && !decision)
            throw new IllegalArgumentException("허용되지 않은 서버 경로입니다.");
        if((pending || decision || endpoint.equals("session")) && !base.startsWith("https://"))
            throw new IllegalArgumentException("AI 학습 계획 승인은 HTTPS 계정 서버가 필요합니다.");
        HttpURLConnection connection=(HttpURLConnection)
                new URL(base + apiPrefix(base) + endpoint).openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(10000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization","Bearer "+token);
        connection.setRequestProperty("Accept","application/json");
        try {
            if (body != null) {
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
                if(bytes.length>262144)
                    throw new IllegalArgumentException("동기화 요청 크기를 초과했습니다.");
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try(java.io.OutputStream out=connection.getOutputStream()){out.write(bytes);}
            } else {
                connection.setRequestMethod("GET");
            }
            int status=connection.getResponseCode();
            if(status == 401) throw new IllegalStateException("서버 인증이 거절되었습니다.");
            if(status == 403) throw new IllegalStateException(
                    "읽기 전용 장치 토큰으로는 AI 학습 계획을 승인하거나 거부할 수 없습니다.");
            if(status == 409) throw new IllegalStateException(
                    "다른 기기의 기록과 충돌했습니다. 서버 목록을 다시 조회하세요.");
            if(status != 200) throw new IllegalStateException("로컬 서버 HTTP 오류: "+status);
            try(InputStream in=connection.getInputStream()) {
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1){
                    if(out.size()+n>MAX_RESPONSE)
                        throw new IllegalStateException("서버 응답 크기 제한을 초과했습니다.");
                    out.write(buffer,0,n);
                }
                return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    /** GET does not upload task or progress content to the server. */
    public static JSONObject snapshot(String token) throws Exception {
        return snapshot(token, BASE);
    }

    public static JSONObject snapshot(String token, String address) throws Exception {
        JSONObject root=call(token,"snapshot",null,address);
        if(root.getInt("schema")!=1 || root.getLong("cursor")<0)
            throw new IllegalStateException("동기화 서버 버전을 확인하세요.");
        JSONArray records=root.getJSONArray("records");
        if(records.length()>7000)
            throw new IllegalStateException("서버 기록 수가 너무 많습니다.");
        Set<String> ids=new HashSet<>();
        for(int i=0;i<records.length();i++){
            JSONObject record=records.getJSONObject(i);
            String kind=record.getString("kind");
            String id=record.getString("id");
            if(!kind.equals("task")&&!kind.equals("progress"))
                throw new IllegalStateException("지원하지 않는 서버 항목입니다.");
            if(record.getBoolean("deleted") || record.getLong("revision")<1 ||
                    !ids.add(kind+"#"+id))
                throw new IllegalStateException("서버 스냅샷을 확인할 수 없습니다.");
        }
        return root;
    }

    /** Authenticated device account identity: never inferred from a public /health. */
    public static JSONObject accountSession(String token, String address) throws Exception {
        String base=checkedAccountAddress(address);
        JSONObject session=call(token,"session",null,base);
        if(session.getInt("schema")!=1 ||
                !canonicalUuid(session.getString("accountId")) ||
                !canonicalUuid(session.getString("deviceId")) ||
                session.getLong("expiresAt")<=session.getLong("serverTime") ||
                !"device".equals(session.getString("audience")))
            throw new IllegalStateException("계정 인증 응답이 올바르지 않습니다.");
        return session;
    }

    /** Strictly verify every server receipt, not merely HTTP 200 and batch length.
     *  A forged, reordered, truncated, duplicated, or mismatched response is NOT
     *  proof of success. Retain the original requestId for safe reconciliation. */
    public static void verifyChangeAck(JSONObject reply,JSONArray mutations)
            throws JSONException {
        if(reply.getInt("schema")!=1 || mutations.length()<1 ||
                mutations.length()>50)
            throw new IllegalStateException("서버 요청 승인 형식을 확인할 수 없습니다.");
        JSONArray changes=reply.getJSONArray("changes");
        if(changes.length()!=mutations.length())
            throw new IllegalStateException("서버가 일부 요청만 확인했습니다.");
        long previous=-1;
        for(int i=0;i<mutations.length();i++){
            JSONObject source=mutations.getJSONObject(i),ack=changes.getJSONObject(i);
            exactly(ack,"seq","kind","id","revision","deleted","updatedAt");
            long seq=ack.getLong("seq");
            if(seq<=previous || seq<1 ||
                    !source.getString("kind").equals(ack.getString("kind")) ||
                    !source.getString("id").equals(ack.getString("id")) ||
                    ack.getLong("revision")!=source.getLong("baseRevision")+1 ||
                    ack.getBoolean("deleted")!=source.getBoolean("deleted") ||
                    ack.getLong("updatedAt")<0)
                throw new IllegalStateException("서버 응답의 기록 ID 또는 수정 버전이 다릅니다.");
            previous=seq;
        }
        if(reply.getLong("cursor")!=previous)
            throw new IllegalStateException("서버 커서가 마지막 승인 기록과 다릅니다.");
    }

    /** Retry exactly the same queued JSON with the original requestId; no new UUID. */
    public static int uploadQueued(String token, String address, JSONObject queued)
            throws Exception {
        checkedAccountAddress(address);
        String request=queued.getString("requestId");
        if(!canonicalUuid(request))throw new IllegalArgumentException("대기열 요청 ID 오류");
        JSONArray mutations=queued.getJSONArray("mutations");
        if(mutations.length()<1 || mutations.length()>50)
            throw new IllegalArgumentException("대기열 크기 제한");
        JSONObject response=call(token,"changes",queued,address);
        verifyChangeAck(response,mutations);
        return mutations.length();
    }

    /** Single existing-ID review. No inferred deletes, no automatic merge. */
    public static final class ReviewedChange {
        public final JSONObject mutation;
        public final long cursor;
        public final String remoteSummary, localSummary;
        ReviewedChange(JSONObject m,long cursor,String remote,String local) {
            this.mutation=m;this.cursor=cursor;remoteSummary=remote;localSummary=local;
        }
    }

    public static ReviewedChange prepareReviewedChange(
            JSONObject snapshot,List<Models.StudyTask> localTasks,
            List<StudyProgressItem> localProgress,String kind,String id,boolean deleted)
            throws Exception {
        // Validates both complete local stores and every remote record in the
        // snapshot before considering any existing-ID server mutation.
        previewInbound(localTasks,localProgress,snapshot);
        if(!"task".equals(kind) && !"progress".equals(kind))
            throw new IllegalArgumentException("수정할 기록의 종류를 선택하세요.");
        JSONArray records=snapshot.getJSONArray("records");
        JSONObject matching=null;
        for(int i=0;i<records.length();i++){
            JSONObject row=records.getJSONObject(i);
            if(kind.equals(row.getString("kind")) && id.equals(row.getString("id"))){
                if(matching!=null)throw new IllegalArgumentException("동일 서버 ID 중복");
                matching=row;
            }
        }
        if(matching==null || matching.getBoolean("deleted"))
            throw new IllegalStateException("서버에 활성화된 해당 ID가 없습니다.");
        long revision=matching.getLong("revision");
        if(revision<1)throw new IllegalStateException("서버 수정 버전이 올바르지 않습니다.");
        JSONObject remotePayload=matching.getJSONObject("payload");
        JSONObject newPayload=null;
        String source, target;
        if("task".equals(kind)){
            Models.StudyTask remote=checkedRemoteTask(remotePayload,id);
            Models.StudyTask local=null;
            for(Models.StudyTask t:localTasks)
                if(Long.toString(t.id).equals(id)){local=t;break;}
            target="["+remote.subject+"] "+remote.title+" · "+remote.dueDate;
            if(deleted){
                if(local!=null)throw new IllegalStateException(
                        "이 기기에 같은 ID가 남아 있어 서버 삭제할 수 없습니다.");
                source="(기기에는 없는 기록: 원격에서만 삭제)";
            }else{
                if(local==null)throw new IllegalStateException("수정할 기기 일정이 없습니다.");
                newPayload=taskJson(local);
                if(local.type.equals(remote.type)&&local.subject.equals(remote.subject)&&
                        local.title.equals(remote.title)&&local.dueDate.equals(remote.dueDate)&&
                        local.importance==remote.importance&&local.completed==remote.completed)
                    throw new IllegalStateException("서버와 동일해 수정할 내용이 없습니다.");
                source="["+local.subject+"] "+local.title+" · "+local.dueDate;
            }
        }else{
            StudyProgressItem remote=checkedRemoteProgress(remotePayload,id,revision);
            StudyProgressItem local=null;
            for(StudyProgressItem p:localProgress)
                if(id.equalsIgnoreCase(p.id)){local=p;break;}
            target="["+remote.subject+"] "+remote.material+" · "+remote.unit+
                    " · "+remote.status;
            if(deleted){
                if(local!=null)throw new IllegalStateException(
                        "이 기기에 같은 진도 UUID가 남아 있어 서버 삭제할 수 없습니다.");
                source="(기기에는 없는 진도: 원격에서만 삭제)";
            }else{
                if(local==null)throw new IllegalStateException("수정할 기기 진도가 없습니다.");
                newPayload=progressJson(local);
                if(local.subject.equals(remote.subject)&&local.material.equals(remote.material)&&
                        local.unit.equals(remote.unit)&&local.status.equals(remote.status)&&
                        local.inExamScope==remote.inExamScope)
                    throw new IllegalStateException("서버와 동일해 수정할 진도가 없습니다.");
                source="["+local.subject+"] "+local.material+" · "+local.unit+
                        " · "+local.status;
            }
        }
        JSONObject change=new JSONObject()
                .put("kind",kind).put("id",id).put("baseRevision",revision)
                .put("deleted",deleted).put("payload",
                        deleted?JSONObject.NULL:newPayload);
        return new ReviewedChange(change,snapshot.getLong("cursor"),target,source);
    }

    /** Requires an externally verified read-write device session and user consent. */
    public static void uploadReviewed(String token,String address,ReviewedChange change)
            throws Exception {
        checkedAccountAddress(address);
        if(change.mutation.getLong("baseRevision")<1)
            throw new IllegalArgumentException("기존 기록의 수정 버전이 필요합니다.");
        JSONObject body=new JSONObject().put("requestId",UUID.randomUUID().toString())
                .put("mutations",new JSONArray().put(change.mutation));
        verifyChangeAck(call(token,"changes",body,address),body.getJSONArray("mutations"));
    }

    /** Recovery evidence is written before a server write, never after. */
    public static String saveReviewedRecovery(android.content.Context context,
            JSONObject oldServerRecord,JSONObject session,String endpoint,long cursor)
            throws Exception {
        JSONObject report=new JSONObject().put("format","studyone-remote-recovery")
                .put("schema",1).put("accountId",session.getString("accountId"))
                .put("endpoint",checkedAccountAddress(endpoint))
                .put("cursor",cursor).put("record",oldServerRecord);
        byte[] bytes=report.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>2_000_000)throw new IllegalStateException("복구본이 너무 큽니다.");
        java.io.File directory=new java.io.File(context.getFilesDir(),"SyncRemoteRecovery");
        if(!directory.exists()&&!directory.mkdirs())
            throw new java.io.IOException("복구 폴더를 생성하지 못했습니다.");
        String filename=UUID.randomUUID()+".json";
        android.util.AtomicFile output=new android.util.AtomicFile(
                new java.io.File(directory,filename));
        java.io.FileOutputStream stream=null;
        try {
            stream=output.startWrite();
            stream.write(bytes);
            output.finishWrite(stream);
        }catch(Exception error){
            if(stream!=null)output.failWrite(stream);
            throw error;
        }
        return filename; // Never disclose user directory or bearer credentials.
    }

    private static JSONObject taskJson(Models.StudyTask t) throws JSONException {
        if (t.id <= 0 || t.importance<1 || t.importance>3 ||
                t.type == null || t.type.trim().isEmpty() || t.type.length()>100 ||
                t.subject == null || t.subject.length()>60 ||
                t.title == null || t.title.trim().isEmpty() || t.title.length()>500 ||
                t.dueDate == null)
            throw new IllegalArgumentException("잘못된 일정은 동기화하지 않습니다.");
        JSONObject item=new JSONObject();
        item.put("id",t.id); item.put("type",t.type); item.put("subject",t.subject);
        item.put("title",t.title); item.put("dueDate",t.dueDate.toString());
        item.put("importance",t.importance); item.put("completed",t.completed);
        return item;
    }

    private static JSONObject progressJson(StudyProgressItem p) throws JSONException {
        JSONObject item=new JSONObject();
        item.put("id",p.id); item.put("subject",p.subject);
        item.put("material",p.material); item.put("unit",p.unit);
        item.put("status",p.status); item.put("inExamScope",p.inExamScope);
        item.put("revision",p.revision); item.put("updatedAt",p.updatedAt);
        return item;
    }

    private static JSONObject mutation(String kind, String id, JSONObject payload)
            throws JSONException {
        JSONObject mutation=new JSONObject();
        mutation.put("kind",kind);mutation.put("id",id);
        mutation.put("baseRevision",0);mutation.put("deleted",false);
        mutation.put("payload",payload);
        return mutation;
    }

    /** Read the authenticated account's pending AI proposals. No user data upload. */
    public static JSONObject pendingProposals(String token, String address) throws Exception {
        JSONObject response=call(token,"proposals",null,address);
        if(response.getInt("schema")!=1)
            throw new IllegalStateException("지원하지 않는 AI 계획 응답입니다.");
        JSONArray proposals=response.getJSONArray("proposals");
        if(proposals.length()>20)
            throw new IllegalStateException("승인 대기 계획 수 제한을 초과했습니다.");
        Set<String> ids=new HashSet<>();
        for(int i=0;i<proposals.length();i++){
            JSONObject plan=proposals.getJSONObject(i);
            String id=plan.getString("id");
            if(!canonicalUuid(id) || !ids.add(id) ||
                    !"pending".equals(plan.getString("state")) ||
                    plan.getString("title").trim().isEmpty() ||
                    plan.getString("title").length()>100 ||
                    plan.getLong("expiresAt") <= plan.getLong("createdAt"))
                throw new IllegalStateException("승인 대기 계획 정보가 잘못되었습니다.");
            JSONArray tasks=plan.getJSONArray("tasks");
            if(tasks.length()!=plan.getInt("taskCount") ||
                    tasks.length()<1 || tasks.length()>30)
                throw new IllegalStateException("승인 대기 학습 일정이 손상되었습니다.");
            for(int j=0;j<tasks.length();j++){
                JSONObject task=tasks.getJSONObject(j);
                if(task.getLong("id")<=0 || task.getInt("importance")<1 ||
                        task.getInt("importance")>3 ||
                        task.getString("type").trim().isEmpty() ||
                        task.getString("subject").length()>60 ||
                        task.getString("title").trim().isEmpty() ||
                        task.getString("title").length()>500)
                    throw new IllegalStateException("승인 대기 학습 일정이 잘못되었습니다.");
                LocalDate.parse(task.getString("dueDate"));
            }
        }
        return response;
    }

    private static boolean canonicalUuid(String id) {
        if(id==null)return false;
        try {return UUID.fromString(id).toString().equalsIgnoreCase(id);}
        catch(IllegalArgumentException error){return false;}
    }

    /** The device user must click an explicit confirmation before calling this. */
    public static String decideProposal(String token, String address,
                                        String proposalId, boolean approve) throws Exception {
        if(!canonicalUuid(proposalId))
            throw new IllegalArgumentException("AI 계획 ID가 올바르지 않습니다.");
        String action=approve?"approve":"reject";
        JSONObject response=call(token,"proposals/"+proposalId+"/"+action,
                new JSONObject(),address);
        if(!proposalId.equals(response.getString("id")) ||
                !(approve?"approved":"rejected").equals(response.getString("state")))
            throw new IllegalStateException("계획 결정 결과를 확인하지 못했습니다.");
        return response.getString("state");
    }

    /** New server IDs are isolated from existing local IDs; never overwrite. */
    public static final class IncomingPlan {
        public final List<Models.StudyTask> newTasks = new ArrayList<>();
        public final List<StudyProgressItem> newProgress = new ArrayList<>();
        public int unchanged, conflicts;
        public long cursor;
    }

    private static void exactly(JSONObject data, String... names) throws JSONException {
        Set<String> allowed = new HashSet<>(java.util.Arrays.asList(names));
        if(data.length()!=allowed.size())
            throw new IllegalArgumentException("서버에 예상하지 못한 필드가 있습니다.");
        java.util.Iterator<String> keys=data.keys();
        while(keys.hasNext()){
            if(!allowed.remove(keys.next()))
                throw new IllegalArgumentException("서버 기록 필드가 올바르지 않습니다.");
        }
        if(!allowed.isEmpty())
            throw new IllegalArgumentException("서버 기록에 필요한 필드가 없습니다.");
    }

    private static Models.StudyTask checkedRemoteTask(JSONObject value, String id)
            throws JSONException {
        exactly(value,"id","type","subject","title","dueDate","importance","completed");
        long numeric=value.getLong("id");
        if(!Long.toString(numeric).equals(id) || numeric<=0)
            throw new IllegalArgumentException("서버의 일정 ID가 불일치합니다.");
        String type=value.getString("type"), subject=value.getString("subject"),
                title=value.getString("title");
        int importance=value.getInt("importance");
        LocalDate due=LocalDate.parse(value.getString("dueDate"));
        if(type.trim().isEmpty()||type.length()>100||subject.length()>60||
                title.trim().isEmpty()||title.length()>500||
                importance<1||importance>3)
            throw new IllegalArgumentException("서버 일정 필드가 유효하지 않습니다.");
        return new Models.StudyTask(numeric,type,subject,title,due,
                importance,value.getBoolean("completed"));
    }

    private static StudyProgressItem checkedRemoteProgress(JSONObject value, String id,
                                                           long revision) throws JSONException {
        exactly(value,"id","subject","material","unit","status",
                "inExamScope","revision","updatedAt");
        if(!value.getString("id").equalsIgnoreCase(id) ||
                value.getLong("revision")!=revision)
            throw new IllegalArgumentException("서버 진도 ID 또는 수정 버전이 불일치합니다.");
        return new StudyProgressItem(id,value.getString("subject"),
                value.getString("material"),value.getString("unit"),
                value.getString("status"),value.getBoolean("inExamScope"),
                revision,value.getLong("updatedAt"));
    }

    private static boolean sameTask(Models.StudyTask a, Models.StudyTask b) {
        return a.type.equals(b.type) && a.subject.equals(b.subject)
                && a.title.equals(b.title) && a.dueDate.equals(b.dueDate)
                && a.importance==b.importance && a.completed==b.completed;
    }
    private static boolean sameProgress(StudyProgressItem a, StudyProgressItem b) {
        return a.subject.equals(b.subject) && a.material.equals(b.material)
                && a.unit.equals(b.unit) && a.status.equals(b.status)
                && a.inExamScope==b.inExamScope;
    }

    /**
     * Pure preview; this method never writes any local or remote data.
     * Existing IDs with divergent contents count as conflicts, not overwrites.
     */
    public static IncomingPlan previewInbound(List<Models.StudyTask> localTasks,
                                               List<StudyProgressItem> localProgress,
                                               JSONObject snapshot) throws JSONException {
        if(localTasks==null||localProgress==null||localTasks.size()>5000||
                localProgress.size()>2000||snapshot.getInt("schema")!=1 ||
                snapshot.getLong("cursor")<0)
            throw new IllegalArgumentException("동기화 미리보기에 유효하지 않은 데이터가 있습니다.");
        JSONArray records=snapshot.getJSONArray("records");
        if(records.length()>7000)
            throw new IllegalArgumentException("서버 기록 수 제한을 초과했습니다.");
        java.util.Map<Long,Models.StudyTask> localTaskIds=new java.util.HashMap<>();
        java.util.Map<String,StudyProgressItem> localProgressIds=new java.util.HashMap<>();
        for(Models.StudyTask task:localTasks)
            if(localTaskIds.put(task.id,task)!=null)
                throw new IllegalArgumentException("로컬 일정 ID 중복");
        for(StudyProgressItem item:localProgress)
            if(localProgressIds.put(item.id.toLowerCase(java.util.Locale.ROOT),item)!=null)
                throw new IllegalArgumentException("로컬 진도 ID 중복");
        IncomingPlan plan=new IncomingPlan();
        plan.cursor=snapshot.getLong("cursor");
        Set<String> seen=new HashSet<>();
        for(int i=0;i<records.length();i++){
            JSONObject row=records.getJSONObject(i);
            String kind=row.getString("kind"), id=row.getString("id");
            String marker=kind+"#"+id.toLowerCase(java.util.Locale.ROOT);
            if((!"task".equals(kind)&&!"progress".equals(kind)) ||
                    !seen.add(marker)||row.getBoolean("deleted")||
                    row.getLong("revision")<1)
                throw new IllegalArgumentException("서버 기록이 중복되거나 잘못됐습니다.");
            JSONObject payload=row.getJSONObject("payload");
            if("task".equals(kind)){
                Models.StudyTask incoming=checkedRemoteTask(payload,id);
                Models.StudyTask existing=localTaskIds.get(incoming.id);
                if(existing==null)plan.newTasks.add(incoming);
                else if(sameTask(existing,incoming))plan.unchanged++;
                else plan.conflicts++;
            }else{
                StudyProgressItem incoming=checkedRemoteProgress(payload,id,row.getLong("revision"));
                StudyProgressItem existing=localProgressIds.get(
                        incoming.id.toLowerCase(java.util.Locale.ROOT));
                if(existing==null)plan.newProgress.add(incoming);
                else if(sameProgress(existing,incoming))plan.unchanged++;
                else plan.conflicts++;
            }
        }
        if(localTasks.size()+plan.newTasks.size()>5000 ||
                localProgress.size()+plan.newProgress.size()>2000)
            throw new IllegalArgumentException("가져온 뒤 로컬 저장 한도를 초과합니다.");
        return plan;
    }

    /** Identical preview contents and IDs must be present after user consent. */
    public static boolean sameInbound(IncomingPlan a, IncomingPlan b, boolean tasks)
            throws JSONException {
        if(a.cursor!=b.cursor||a.conflicts!=b.conflicts)return false;
        if(tasks){
            if(a.newTasks.size()!=b.newTasks.size())return false;
            for(int i=0;i<a.newTasks.size();i++){
                Models.StudyTask first=a.newTasks.get(i),second=b.newTasks.get(i);
                if(first.id!=second.id || !sameTask(first,second))return false;
            }
        }else{
            if(a.newProgress.size()!=b.newProgress.size())return false;
            for(int i=0;i<a.newProgress.size();i++){
                StudyProgressItem x=a.newProgress.get(i),y=b.newProgress.get(i);
                if(!x.id.equals(y.id)||x.revision!=y.revision||
                        x.updatedAt!=y.updatedAt||!sameProgress(x,y))return false;
            }
        }
        return true;
    }

    /** Derive new-ID-only upload from validated local collections. */
    public static JSONArray prepareNewOnly(List<Models.StudyTask> tasks,
                                           List<StudyProgressItem> progress,
                                           JSONObject serverSnapshot) throws JSONException {
        if(tasks==null||progress==null || tasks.size()>5000||progress.size()>2000)
            throw new IllegalArgumentException("로컬 데이터 크기가 올바르지 않습니다.");
        if(serverSnapshot.getInt("schema") != 1)
            throw new IllegalArgumentException("지원되지 않는 서버 정보입니다.");
        Set<String> remote=new HashSet<>();
        JSONArray records=serverSnapshot.getJSONArray("records");
        if(records.length()>7000)throw new IllegalArgumentException("서버 기록이 너무 많습니다.");
        for(int i=0;i<records.length();i++) {
            JSONObject row=records.getJSONObject(i);
            remote.add(row.getString("kind")+"#"+row.getString("id"));
        }
        Set<String> local=new HashSet<>();
        JSONArray pending=new JSONArray();
        for(Models.StudyTask t:tasks){
            JSONObject payload=taskJson(t);String id=Long.toString(t.id);
            if(!local.add("task#"+id))
                throw new IllegalArgumentException("일정 ID가 중복되었습니다.");
            if(!remote.contains("task#"+id))
                pending.put(mutation("task",id,payload));
        }
        for(StudyProgressItem p:progress){
            JSONObject payload=progressJson(p);
            if(!local.add("progress#"+p.id))
                throw new IllegalArgumentException("진도 ID가 중복되었습니다.");
            if(!remote.contains("progress#"+p.id))
                pending.put(mutation("progress",p.id,payload));
        }
        return pending;
    }

    /** Each requested batch is atomic; no implicit retry if result is unknown. */
    public static int uploadNew(String token, JSONArray pending) throws Exception {
        return uploadNew(token, pending, BASE);
    }

    public static int uploadNew(String token, JSONArray pending, String address) throws Exception {
        validateToken(token);
        checkedAddress(address);
        if(pending==null || pending.length()==0)return 0;
        int sent=0;
        for(int i=0;i<pending.length();i+=BATCH) {
            JSONArray batch=new JSONArray();
            for(int j=i;j<Math.min(i+BATCH,pending.length());j++)
                batch.put(pending.getJSONObject(j));
            JSONObject payload=new JSONObject();
            payload.put("requestId",UUID.randomUUID().toString());
            payload.put("mutations",batch);
            try {
                JSONObject response=call(token,"changes",payload,address);
                verifyChangeAck(response,batch);
                sent+=batch.length();
            } catch(Exception error) {
                throw new IllegalStateException("전송이 중단되었습니다. "+
                        sent+"개 전송 이후 일부 저장됐을 수 있습니다. 서버를 다시 조회하세요.",error);
            }
        }
        return sent;
    }

    /** Pure local fixture; does not read private records or issue network calls. */
    public static boolean selfTest() {
        try {
            if(!checkedAddress("https://example.org/").startsWith("https://"))return false;
            if(!checkedAddress("http://127.0.0.1:8765/").contains("8765"))return false;
            for(String forbidden:new String[]{"http://192.168.0.2:8765/",
                    "http://example.org/", "ftp://example.org/",
                    "https://user:pass@example.org/", "https://example.org/path/",
                    "https://example.org/?secret=1"}) {
                try {checkedAddress(forbidden);return false;}
                catch(IllegalArgumentException expected){/* secure rejection */}
            }
            Models.StudyTask task=new Models.StudyTask(
                    1730000000000L,"숙제 항목","수학","테스트 문제", LocalDate.of(2026,10,22),2,false);
            StudyProgressItem item=StudyProgressItem.create(
                    "과학","오투","7-2","미완료",true);
            JSONObject snap=new JSONObject()
                    .put("schema",1).put("cursor",0).put("records",new JSONArray());
            JSONArray first=prepareNewOnly(java.util.Collections.singletonList(task),
                    java.util.Collections.singletonList(item),snap);
            if(first.length()!=2 || first.getJSONObject(0).getLong("baseRevision")!=0) return false;
            snap.put("records",new JSONArray()
                    .put(new JSONObject().put("kind","task")
                            .put("id",Long.toString(task.id))
                            .put("revision",9).put("deleted",false)));
            JSONArray second=prepareNewOnly(java.util.Collections.singletonList(task),
                    java.util.Collections.singletonList(item),snap);
            if(second.length()!=1 || !"progress".equals(second.getJSONObject(0).getString("kind")))
                return false;
            snap.getJSONArray("records").put(new JSONObject().put("kind","progress")
                    .put("id",item.id).put("revision",2).put("deleted",false));
            if(prepareNewOnly(java.util.Collections.singletonList(task),
                    java.util.Collections.singletonList(item),snap).length()!=0)return false;
            JSONObject inbound=new JSONObject().put("schema",1).put("cursor",2)
                    .put("records",new JSONArray()
                            .put(new JSONObject().put("kind","task").put("id",Long.toString(task.id))
                                    .put("revision",1).put("deleted",false)
                                    .put("payload",taskJson(task)))
                            .put(new JSONObject().put("kind","progress").put("id",item.id)
                                    .put("revision",item.revision).put("deleted",false)
                                    .put("payload",progressJson(item))));
            IncomingPlan newOnly=previewInbound(new ArrayList<>(),
                    new ArrayList<>(),inbound);
            if(newOnly.newTasks.size()!=1 || newOnly.newProgress.size()!=1 ||
                    newOnly.conflicts!=0)return false;
            IncomingPlan identical=previewInbound(
                    java.util.Collections.singletonList(task),
                    java.util.Collections.singletonList(item),inbound);
            if(identical.newTasks.size()!=0 || identical.newProgress.size()!=0 ||
                    identical.unchanged!=2 || identical.conflicts!=0)return false;
            if(!sameInbound(newOnly,newOnly,true)||
                    !sameInbound(newOnly,newOnly,false))return false;
            JSONObject changed=new JSONObject(inbound.toString());
            changed.getJSONArray("records").getJSONObject(0).getJSONObject("payload")
                    .put("title","서버에서 변경된 별개 내용");
            IncomingPlan conflicts=previewInbound(
                    java.util.Collections.singletonList(task),
                    java.util.Collections.singletonList(item),changed);
            if(conflicts.conflicts!=1 || conflicts.unchanged!=1)return false;
            if(sameInbound(newOnly,previewInbound(new ArrayList<>(),
                    new ArrayList<>(),changed),true))return false;
            changed.getJSONArray("records").getJSONObject(0).getJSONObject("payload")
                    .put("apiKey","must-be-rejected");
            try {
                previewInbound(new ArrayList<>(),new ArrayList<>(),changed);
                return false;
            }catch(IllegalArgumentException expected){/* safe rejection */}
            JSONObject good=new JSONObject().put("schema",1).put("cursor",2)
                    .put("changes",new JSONArray()
                            .put(new JSONObject().put("seq",1).put("kind","task")
                                    .put("id",Long.toString(task.id)).put("revision",1)
                                    .put("deleted",false).put("updatedAt",1790000000000L))
                            .put(new JSONObject().put("seq",2).put("kind","progress")
                                    .put("id",item.id).put("revision",1)
                                    .put("deleted",false).put("updatedAt",1790000000000L)));
            verifyChangeAck(good,first);
            JSONObject forged=new JSONObject(good.toString());
            forged.getJSONArray("changes").getJSONObject(1).put("id",UUID.randomUUID().toString());
            try { verifyChangeAck(forged,first);return false; }
            catch(IllegalStateException expected){/* mismatched id rejected */}
            forged=new JSONObject(good.toString());
            forged.getJSONArray("changes").getJSONObject(1).put("revision",5);
            try { verifyChangeAck(forged,first);return false; }
            catch(IllegalStateException expected){/* revision mismatch rejected */}
            forged=new JSONObject(good.toString()).put("cursor",999);
            try { verifyChangeAck(forged,first);return false; }
            catch(IllegalStateException expected){/* cursor mismatch rejected */}
            forged=new JSONObject(good.toString());
            forged.getJSONArray("changes").getJSONObject(1).put("seq",1);
            try { verifyChangeAck(forged,first);return false; }
            catch(IllegalStateException expected){/* duplicate sequence rejected */}
            return true;
        }catch(Exception ignored){return false;}
    }
}
