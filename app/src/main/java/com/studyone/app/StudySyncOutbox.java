package com.studyone.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Locally durable, token-free, create-only outbox; no background service. */
public final class StudySyncOutbox {
    private static final int MAX_BYTES=2_000_000;
    private static final int MAX_RECORDS=1000;
    private static final int MAX_BATCHES=100;
    private final AtomicFile file;

    public StudySyncOutbox(Context context) {
        this(context,"studyone-sync-outbox.json");
    }

    private StudySyncOutbox(Context context,String name) {
        file=new AtomicFile(new File(context.getFilesDir(),name));
    }

    private static JSONObject fresh() throws Exception {
        return new JSONObject().put("format","studyone-sync-outbox")
                .put("schema",1).put("batches",new JSONArray());
    }

    private static String key(JSONObject item) throws Exception {
        return item.getString("kind")+"#"+item.getString("id").toLowerCase(java.util.Locale.ROOT);
    }

    private static String checkedAccount(String account) {
        if(account==null)throw new IllegalArgumentException("계정 UUID 누락");
        String lower=account.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if(!UUID.fromString(lower).toString().equals(lower))
                throw new IllegalArgumentException("계정 UUID를 확인하세요.");
        }catch(Exception error){ throw new IllegalArgumentException("계정 UUID를 확인하세요.");}
        return lower;
    }

    private static String checkedEndpoint(String address) {
        String safe=StudySyncClient.checkedAccountAddress(address);
        if(!safe.equals(address))throw new IllegalArgumentException("HTTPS 주소를 정확히 입력하세요.");
        return safe;
    }

    private static void verify(JSONObject document) throws Exception {
        if(!"studyone-sync-outbox".equals(document.getString("format")) ||
                document.getInt("schema")!=1 || document.length()!=3)
            throw new IllegalArgumentException("대기열 형식이 손상됐습니다.");
        JSONArray batches=document.getJSONArray("batches");
        if(batches.length()>MAX_BATCHES)throw new IllegalArgumentException("대기열 최대 100개 요청");
        Set<String> requests=new HashSet<>();
        Set<String> keys=new HashSet<>();
        int count=0;
        for(int i=0;i<batches.length();i++){
            JSONObject b=batches.getJSONObject(i);
            if(b.length()!=4)throw new IllegalArgumentException("대기열 요청 필드 오류");
            String id=checkedAccount(b.getString("requestId"));
            String account=checkedAccount(b.getString("accountId"));
            if(!requests.add(id))throw new IllegalArgumentException("중복 요청 ID");
            checkedEndpoint(b.getString("endpoint"));
            JSONArray items=b.getJSONArray("mutations");
            if(items.length()<1 || items.length()>50)
                throw new IllegalArgumentException("대기열 배치 크기 오류");
            count+=items.length();
            if(count>MAX_RECORDS)throw new IllegalArgumentException("대기열 최대 1000항목");
            for(int j=0;j<items.length();j++){
                JSONObject m=items.getJSONObject(j);
                if(m.length()!=5 || m.getInt("baseRevision")!=0 ||
                        m.getBoolean("deleted") || m.isNull("payload"))
                    throw new IllegalArgumentException("대기열은 신규 생성만 허용합니다.");
                String kind=m.getString("kind"), itemId=m.getString("id");
                if(!"task".equals(kind)&&!"progress".equals(kind))
                    throw new IllegalArgumentException("대기열 데이터 종류 오류");
                if("task".equals(kind)){
                    long parsed=Long.parseLong(itemId);
                    if(parsed<1 || !Long.toString(parsed).equals(itemId))
                        throw new IllegalArgumentException("일정 ID 오류");
                }else checkedAccount(itemId);
                if(!keys.add(account+"#"+key(m)))
                    throw new IllegalArgumentException("같은 계정에 중복 대기 기록");
                JSONObject payload=m.getJSONObject("payload");
                if("task".equals(kind)){
                    if(payload.getLong("id")!=Long.parseLong(itemId) ||
                            payload.getInt("importance")<1 ||
                            payload.getInt("importance")>3)
                        throw new IllegalArgumentException("일정 내용 오류");
                }else{
                    if(!payload.getString("id").equals(itemId) ||
                            payload.getLong("revision")<1)
                        throw new IllegalArgumentException("진도 내용 오류");
                }
            }
        }
    }

    public synchronized JSONObject read() throws Exception {
        if(!file.getBaseFile().exists())return fresh();
        byte[] raw=file.readFully();
        if(raw.length>MAX_BYTES)throw new IllegalArgumentException("대기열 저장 용량 초과");
        JSONObject parsed=new JSONObject(new String(raw,StandardCharsets.UTF_8));
        verify(parsed);
        return parsed;
    }

    private synchronized void save(JSONObject document) throws Exception {
        verify(document);
        byte[] bytes=document.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("대기열 크기 제한");
        FileOutputStream output=null;
        try{
            output=file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        }catch(Exception error){
            if(output!=null)file.failWrite(output);
            throw error;
        }
    }

    /** Capture immutable local new-only copies. No API call or token needed. */
    public synchronized int enqueue(String account,String address,
                                    List<Models.StudyTask> tasks,
                                    List<StudyProgressItem> progress) throws Exception {
        account=checkedAccount(account);address=checkedEndpoint(address);
        JSONObject queue=read();
        JSONArray existing=queue.getJSONArray("batches");
        Set<String> keys=new HashSet<>();
        for(int i=0;i<existing.length();i++){
            JSONObject batch=existing.getJSONObject(i);
            if(account.equals(batch.getString("accountId"))){
                JSONArray items=batch.getJSONArray("mutations");
                for(int j=0;j<items.length();j++)keys.add(key(items.getJSONObject(j)));
            }
        }
        JSONObject empty=new JSONObject().put("schema",1).put("records",new JSONArray());
        JSONArray current=StudySyncClient.prepareNewOnly(tasks,progress,empty);
        JSONArray additional=new JSONArray();
        for(int i=0;i<current.length();i++){
            JSONObject item=current.getJSONObject(i);
            if(!keys.contains(key(item)))additional.put(item);
        }
        for(int i=0;i<additional.length();i+=50){
            JSONArray part=new JSONArray();
            for(int j=i;j<Math.min(i+50,additional.length());j++)
                part.put(additional.getJSONObject(j));
            existing.put(new JSONObject().put("requestId",UUID.randomUUID().toString())
                    .put("accountId",account).put("endpoint",address).put("mutations",part));
        }
        if(additional.length()>0)save(queue);
        return additional.length();
    }

    public synchronized void validateLocal(JSONObject batch,
                                          List<Models.StudyTask> tasks,
                                          List<StudyProgressItem> progress) throws Exception {
        JSONObject empty=new JSONObject().put("schema",1).put("records",new JSONArray());
        JSONArray latest=StudySyncClient.prepareNewOnly(tasks,progress,empty);
        java.util.Map<String,String> indexed=new java.util.HashMap<>();
        for(int i=0;i<latest.length();i++){
            JSONObject item=latest.getJSONObject(i);
            indexed.put(key(item),item.getJSONObject("payload").toString());
        }
        JSONArray entries=batch.getJSONArray("mutations");
        for(int i=0;i<entries.length();i++){
            JSONObject item=entries.getJSONObject(i);
            if(!item.getJSONObject("payload").toString().equals(indexed.get(key(item))))
                throw new IllegalStateException("기기 기록이 오프라인 보관 후 변경됐습니다.");
        }
    }

    public static void checkSession(JSONObject batch,JSONObject session,String address)
            throws Exception {
        if(!batch.getString("accountId").equals(session.getString("accountId")) ||
                !"device".equals(session.getString("audience")) ||
                !"read-write".equals(session.getString("scope")) ||
                session.getLong("expiresAt")<=session.getLong("serverTime") ||
                !batch.getString("endpoint").equals(checkedEndpoint(address)))
            throw new IllegalStateException("대기열 계정·서버와 토큰 소유자가 다릅니다.");
    }

    public synchronized void removeFirst(String approvedRequestId) throws Exception {
        JSONObject queue=read();
        JSONArray batches=queue.getJSONArray("batches");
        if(batches.length()==0 ||
                !approvedRequestId.equals(batches.getJSONObject(0).getString("requestId")))
            throw new IllegalStateException("대기열 처리 순서가 변경됐습니다.");
        JSONArray remaining=new JSONArray();
        for(int i=1;i<batches.length();i++)remaining.put(batches.getJSONObject(i));
        queue.put("batches",remaining);
        save(queue);
    }

    /** Uses only isolated file with synthetic JSON (no real StudyOne prefs). */
    public static boolean selfTest(Context context) {
        String name="studyone-outbox-qa-"+UUID.randomUUID()+".json";
        StudySyncOutbox isolated=new StudySyncOutbox(context,name);
        try{
            String account=UUID.randomUUID().toString();
            JSONArray mutations=new JSONArray();
            String id=UUID.randomUUID().toString();
            JSONObject progress=new JSONObject().put("id",id).put("subject","과학")
                    .put("material","합성").put("unit","1단원").put("status","미완료")
                    .put("inExamScope",true).put("revision",1).put("updatedAt",1790000000000L);
            mutations.put(new JSONObject().put("kind","progress").put("id",id)
                    .put("baseRevision",0).put("deleted",false).put("payload",progress));
            String request=UUID.randomUUID().toString();
            JSONObject document=fresh();
            document.getJSONArray("batches").put(new JSONObject()
                    .put("requestId",request).put("accountId",account)
                    .put("endpoint","https://sync.example.org/")
                    .put("mutations",mutations));
            isolated.save(document);
            if(!isolated.read().getJSONArray("batches").getJSONObject(0)
                    .getString("requestId").equals(request))return false;
            JSONObject wrong=new JSONObject().put("accountId",UUID.randomUUID().toString())
                    .put("audience","device").put("scope","read-write")
                    .put("serverTime",1).put("expiresAt",2);
            try {
                checkSession(document.getJSONArray("batches").getJSONObject(0),
                        wrong,"https://sync.example.org/");
                return false;
            }catch(IllegalStateException expected){}
            isolated.removeFirst(request);
            return isolated.read().getJSONArray("batches").length()==0;
        }catch(Exception error){return false;}
        finally{isolated.file.delete();}
    }
}
