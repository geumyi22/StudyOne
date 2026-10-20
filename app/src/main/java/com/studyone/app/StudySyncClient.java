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
        if (!endpoint.equals("snapshot") && !endpoint.equals("changes"))
            throw new IllegalArgumentException("허용되지 않은 동기화 서버 경로입니다.");
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
                if(response.getInt("schema")!=1 ||
                        response.getJSONArray("changes").length()!=batch.length())
                    throw new IllegalStateException("서버 저장 결과를 확인하지 못했습니다.");
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
            return prepareNewOnly(java.util.Collections.singletonList(task),
                    java.util.Collections.singletonList(item),snap).length()==0;
        }catch(Exception ignored){return false;}
    }
}
