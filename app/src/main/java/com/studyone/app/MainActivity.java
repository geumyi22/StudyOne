package com.studyone.app;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BLUE = Color.rgb(75,103,242);
    private static final int BLUE_DARK = Color.rgb(52,76,201);
    private static final int BG = Color.rgb(246,248,252);
    private static final int TEXT = Color.rgb(29,35,48);
    private static final int MUTED = Color.rgb(102,112,133);
    private static final int BORDER = Color.rgb(229,233,241);
    private static final DateTimeFormatter KO =
            DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN);

    private Storage storage;
    private final NeisClient neis = new NeisClient();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private FrameLayout content;
    private TextView title;
    private int currentTab;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        storage = new Storage(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.WHITE);

        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.addView(toolbar());
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1,0,1f));
        root.addView(bottomNav());
        setContentView(root);
        showTab(0);
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View toolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18),dp(12),dp(18),dp(10));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_book_check);
        bar.addView(logo,new LinearLayout.LayoutParams(dp(38),dp(38)));

        LinearLayout names = column();
        names.setPadding(dp(10),0,0,0);
        names.addView(tv("StudyOne",20,TEXT,true));
        title = tv("오늘",12,MUTED,false);
        names.addView(title);
        bar.addView(names,new LinearLayout.LayoutParams(0,-2,1f));
        bar.addView(pill("v2",BLUE,Color.WHITE));
        return bar;
    }

    private View bottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(4),dp(6),dp(4),dp(8));
        nav.setBackgroundColor(Color.WHITE);
        String[] n={"홈","시간표","급식","플래너","설정"};
        for(int i=0;i<n.length;i++){
            final int x=i;
            Button b=new Button(this);
            b.setText(n[i]); b.setTextSize(12); b.setAllCaps(false);
            b.setTextColor(i==0?BLUE:MUTED); b.setBackgroundColor(Color.TRANSPARENT);
            b.setOnClickListener(v->showTab(x));
            nav.addView(b,new LinearLayout.LayoutParams(0,dp(52),1f));
        }
        return nav;
    }

    private void showTab(int tab) {
        currentTab=tab;
        content.removeAllViews();
        if(tab==0){title.setText("오늘");content.addView(home());}
        else if(tab==1){title.setText("주간 시간표");content.addView(timetable());}
        else if(tab==2){title.setText("이번 주 급식");content.addView(meals());}
        else if(tab==3){title.setText("학습 플래너");content.addView(planner());}
        else{title.setText("설정");content.addView(settings());}
    }

    private ScrollView page() {
        ScrollView s=new ScrollView(this);
        s.setFillViewport(true); s.setClipToPadding(false);
        s.setPadding(dp(14),dp(2),dp(14),dp(18));
        return s;
    }

    private View home() {
        ScrollView s=page(); LinearLayout c=column(); s.addView(c);
        LocalDate today=LocalDate.now();
        c.addView(tv(today.format(KO),24,TEXT,true));

        Models.School school=storage.school();
        if(school==null){
            c.addView(spacer(10));
            c.addView(infoCard("학교 설정이 필요해요",
                    "NEIS 인증키와 학교·학년·반을 등록하면 실제 시간표와 급식을 불러옵니다.",
                    "설정하기",v->showTab(4)));
            return s;
        }

        c.addView(tv(school.name+" · "+storage.grade()+"학년 "+storage.className()+"반",13,MUTED,false));
        c.addView(spacer(12));
        Button refresh=primary("오늘 데이터 새로고침");
        refresh.setOnClickListener(v->refreshAll(true)); c.addView(refresh);

        c.addView(section("오늘 시간표")); c.addView(timetableSummary(today));
        c.addView(section("오늘 급식")); c.addView(mealSummary(today));
        c.addView(section("오늘 우선순위")); c.addView(prioritySummary());
        c.addView(section("내일 준비물")); c.addView(suppliesSummary(today.plusDays(1)));
        c.addView(section("데이터 상태"));
        String tt=storage.getCache("timetable",timetableIdentity());
        String mm=storage.getCache("meals",mealIdentity());
        c.addView(cardText("시간표: "+(tt.isEmpty()?"미동기화":storage.cacheAgeMinutes("timetable")+"분 전")+
                "\n급식: "+(mm.isEmpty()?"미동기화":storage.cacheAgeMinutes("meals")+"분 전")+
                (storage.lastError("timetable").isEmpty() ? "" : "\n시간표 오류: "+storage.lastError("timetable"))+
                (storage.lastError("meals").isEmpty() ? "" : "\n급식 오류: "+storage.lastError("meals")),14));
        refreshAll(false);
        return s;
    }

    private View timetable() {
        ScrollView s=page(); LinearLayout c=column(); s.addView(c);
        if(storage.school()==null){
            c.addView(infoCard("학교 설정이 필요해요","학교를 설정하면 주간 시간표를 표시합니다.","설정으로",v->showTab(4)));
            return s;
        }
        Button refresh=primary("주간 시간표 동기화");
        refresh.setOnClickListener(v->loadTimetable(true)); c.addView(refresh); c.addView(spacer(10));
        String raw=storage.getCache("timetable",timetableIdentity());
        if(raw.isEmpty()){c.addView(cardText("아직 동기화된 시간표가 없습니다."+errorHint("timetable"),14));loadTimetable(false);return s;}

        List<Models.TimetableEntry> list=decodeTimetable(raw);
        LocalDate monday=LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for(int d=0;d<5;d++){
            LocalDate day=monday.plusDays(d);
            c.addView(section(day.format(DateTimeFormatter.ofPattern("M/d E",Locale.KOREAN))));
            LinearLayout box=card(); boolean any=false;
            for(Models.TimetableEntry e:list){
                if(!e.date.equals(day))continue; any=true;
                LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(pill(e.period+"교시",BLUE,Color.WHITE));
                TextView subject=tv(e.subject,16,TEXT,true); subject.setPadding(dp(10),0,0,0); row.addView(subject);
                box.addView(row); box.addView(spacer(8));
            }
            if(!any)box.addView(tv("수업 정보 없음",14,MUTED,false));
            c.addView(box);
        }
        return s;
    }

    private View meals() {
        ScrollView s=page(); LinearLayout c=column(); s.addView(c);
        if(storage.school()==null){
            c.addView(infoCard("학교 설정이 필요해요","학교를 설정하면 실제 NEIS 중식을 표시합니다.","설정으로",v->showTab(4)));
            return s;
        }
        Button refresh=primary("급식 동기화"); refresh.setOnClickListener(v->loadMeals(true)); c.addView(refresh); c.addView(spacer(10));
        String raw=storage.getCache("meals",mealIdentity());
        if(raw.isEmpty()){c.addView(cardText("아직 동기화된 급식이 없습니다."+errorHint("meals"),14));loadMeals(false);return s;}
        List<Models.Meal> mealList=decodeMeals(raw);
        if(mealList.isEmpty()) c.addView(cardText("이번 주 등록된 중식 데이터가 없습니다.",14));
        for(Models.Meal m:mealList){
            c.addView(section(m.date.format(DateTimeFormatter.ofPattern("M/d E",Locale.KOREAN))));
            LinearLayout box=card(); box.addView(tv(m.menu,15,TEXT,false));
            if(!m.kcal.trim().isEmpty()){TextView k=tv(m.kcal,12,MUTED,false);k.setPadding(0,dp(8),0,0);box.addView(k);}
            c.addView(box);
        }
        return s;
    }

    private View planner() {
        ScrollView s=page(); LinearLayout c=column(); s.addView(c);
        Button add=primary("+ 과제 / 수행평가 / 시험 / 준비물 추가");
        add.setOnClickListener(v->taskDialog(null)); c.addView(add);

        List<Models.StudyTask> tasks=storage.tasks();
        if(tasks.isEmpty()){
            c.addView(spacer(12));
            c.addView(cardText("등록된 일정이 없습니다.\\n시험, 숙제, 수행평가와 준비물을 추가해 보세요.",14));
            return s;
        }

        int done=0;
        for(Models.StudyTask t:tasks) if(t.completed) done++;
        c.addView(cardText("전체 "+tasks.size()+"건 · 완료 "+done+"건 · 미완료 "+(tasks.size()-done)+"건",15));

        c.addView(section("추천 순서"));
        List<Models.StudyTask> ranked=new ArrayList<>(tasks);
        ranked.removeIf(t->t.completed);
        ranked.sort(Comparator.comparingDouble(this::score).reversed());
        LinearLayout rec=card();
        int count=0;
        for(Models.StudyTask t:ranked){
            if(count>=3) break;
            count++;
            rec.addView(tv(count+". "+label(t)+"   "+dDay(t.dueDate),15,TEXT,true));
            rec.addView(spacer(7));
        }
        if(count==0) rec.addView(tv("모든 일정을 완료했습니다.",14,MUTED,false));
        c.addView(rec);

        c.addView(section("전체 일정"));
        for(Models.StudyTask t:tasks) c.addView(taskCard(t));
        return s;
    }

    private View settings() {
        ScrollView s=page(); LinearLayout c=column(); s.addView(c);
        c.addView(section("NEIS 연결"));

        EditText key=input("NEIS 인증키",storage.apiKey());
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); c.addView(key);
        TextView hint=tv("실제 조회는 인증키 사용을 권장합니다. 키는 이 기기에만 저장되고 GitHub에는 올라가지 않습니다.",12,MUTED,false);
        hint.setPadding(dp(2),dp(5),dp(2),dp(8));c.addView(hint);

        EditText schoolName=input("학교명 검색",storage.school()==null?"":storage.school().name);c.addView(schoolName);
        Button search=secondary("학교 검색");
        search.setOnClickListener(v->{storage.saveApiKey(key.getText().toString());searchSchool(schoolName.getText().toString());});c.addView(search);

        Models.School sc=storage.school();
        String selected=sc==null?"선택된 학교 없음":sc.name+"\n"+sc.kind+"\n"+sc.address+"\n"+sc.eduCode+" / "+sc.schoolCode;
        c.addView(cardText(selected,13));

        c.addView(section("학년 / 반"));
        Spinner grade=new Spinner(this); String[] grades={"1","2","3","4","5","6"};
        grade.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,grades));
        grade.setSelection(Math.max(0,Math.min(5,storage.grade()-1)));c.addView(grade);
        EditText className=input("반 (예: 3)",storage.className());className.setInputType(InputType.TYPE_CLASS_NUMBER);c.addView(className);

        Button save=primary("설정 저장");
        save.setOnClickListener(v->{storage.saveApiKey(key.getText().toString());
            int g=Integer.parseInt(grades[grade.getSelectedItemPosition()]);
            String cl=className.getText().toString().trim();if(cl.isEmpty())cl="1";
            storage.saveGradeClass(g,cl);toast("저장했습니다. 새 설정으로 캐시를 초기화했습니다.");});c.addView(save);

        c.addView(section("진단 / 데이터"));
        Button test=secondary("NEIS 연결 테스트");test.setOnClickListener(v->testConnection());c.addView(test);
        Button clear=secondary("시간표·급식 캐시 초기화");clear.setOnClickListener(v->{storage.clearSchoolCaches();toast("캐시를 초기화했습니다.");showTab(currentTab);});c.addView(clear);

        c.addView(section("앱 정보"));
        c.addView(cardText("StudyOne 2.1.0-beta.1\nAndroid 네이티브 재설계\nAPI 37 / Android 17 대응",13));
        return s;
    }

    private LocalDate weekStart() {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private String errorHint(String key) {
        String value=storage.lastError(key);
        return value.isEmpty() ? "" : "\\n최근 동기화 오류: "+value+"\\n재시도 버튼을 눌러 주세요.";
    }

    private String errorSummary(Exception e) {
        if(e instanceof NeisClient.ApiException) {
            return ((NeisClient.ApiException)e).code;
        }
        return "NETWORK";
    }

    private void refreshAll(boolean manual) {
        if(storage.school()==null) {
            if(manual) toast("학교를 먼저 설정하세요.");
            return;
        }
        loadTimetable(manual);
        loadMeals(manual);
    }

    private void loadTimetable(boolean manual) {
        Models.School school=storage.school();
        if(school==null)return;
        LocalDate monday=weekStart(),friday=monday.plusDays(4);
        String identity=timetableIdentity();
        if(!manual && !storage.shouldAutoFetch("timetable",identity,180)) return;
        int grade=storage.grade();
        String cl=storage.className();
        String key=storage.apiKey();
        io.execute(()->{
            try{
                List<Models.TimetableEntry> list=neis.fetchTimetable(key,school,grade,cl,monday,friday);
                if(!identity.equals(timetableIdentity()))return; // A settings change invalidated this response.
                storage.putCache("timetable",identity,encodeTimetable(list));
                storage.saveLastError("timetable","");
                runOnUiThread(()->{
                    if(manual)toast("시간표 동기화 완료: "+list.size()+"개 수업");
                    if(currentTab==0||currentTab==1)showTab(currentTab);
                });
            }catch(Exception e){
                if(!identity.equals(timetableIdentity()))return;
                storage.saveLastError("timetable",errorSummary(e));
                runOnUiThread(()->{
                    if(manual)error("시간표 동기화 실패",e);
                    else if(currentTab==0||currentTab==1)showTab(currentTab);
                });
            }
        });
    }

    private void loadMeals(boolean manual) {
        Models.School school=storage.school();
        if(school==null)return;
        LocalDate from=weekStart(),to=from.plusDays(6);
        String identity=mealIdentity();
        if(!manual && !storage.shouldAutoFetch("meals",identity,180)) return;
        String key=storage.apiKey();
        io.execute(()->{
            try{
                List<Models.Meal> list=neis.fetchMeals(key,school,from,to);
                if(!identity.equals(mealIdentity()))return;
                storage.putCache("meals",identity,encodeMeals(list));
                storage.saveLastError("meals","");
                runOnUiThread(()->{
                    if(manual)toast("급식 동기화 완료: "+list.size()+"건");
                    if(currentTab==0||currentTab==2)showTab(currentTab);
                });
            }catch(Exception e){
                if(!identity.equals(mealIdentity()))return;
                storage.saveLastError("meals",errorSummary(e));
                runOnUiThread(()->{
                    if(manual)error("급식 동기화 실패",e);
                    else if(currentTab==0||currentTab==2)showTab(currentTab);
                });
            }
        });
    }

    private void testConnection(){
        Models.School school=storage.school();if(school==null){toast("학교를 먼저 선택하세요.");return;}LocalDate today=LocalDate.now();
        io.execute(()->{try{
            List<Models.Meal> m=neis.fetchMeals(storage.apiKey(),school,today,today.plusDays(1));
            runOnUiThread(()->new AlertDialog.Builder(this).setTitle("NEIS 연결 정상")
                    .setMessage("학교: "+school.name+"\n학교 코드 검증 완료\n급식 응답: "+m.size()+"건").setPositiveButton("확인",null).show());
        }catch(Exception e){runOnUiThread(()->error("NEIS 연결 실패",e));}});
    }

    private void searchSchool(String name){
        toast("학교를 검색하고 있습니다…");
        io.execute(()->{
            try {
                List<Models.School> results=neis.searchSchools(storage.apiKey(),name);
                runOnUiThread(()->schoolResults(results));
            } catch (Exception firstError) {
                if (!storage.apiKey().trim().isEmpty() && NeisClient.canTrySampleSearch(firstError)) {
                    try {
                        // Official NEIS sample mode is limited to 5 matches.
                        List<Models.School> sample=neis.searchSchools("",name);
                        runOnUiThread(()->new AlertDialog.Builder(this)
                                .setTitle("학교 검색 대체 조회")
                                .setMessage("인증키를 사용한 학교 검색이 실패했습니다.\n"
                                        +"NEIS 공식 샘플 모드에서 최대 5건을 조회했습니다.\n"
                                        +"전체 검색 결과가 아닐 수 있으니 학교명과 주소를 꼭 확인해 주세요.\n\n"
                                        +"실제 시간표·급식 조회에는 정상 인증키가 필요합니다.")
                                .setPositiveButton("검색 결과 보기",(d,w)->schoolResults(sample))
                                .setNegativeButton("취소",null)
                                .show());
                        return;
                    } catch (Exception sampleError) {
                        runOnUiThread(()->new AlertDialog.Builder(this)
                                .setTitle("학교 검색 실패")
                                .setMessage("인증키 검색과 제한된 샘플 검색이 모두 실패했습니다.\n\n"
                                        +"인증키 요청: "+safeError(firstError)+"\n"
                                        +"샘플 요청: "+safeError(sampleError)+"\n\n"
                                        +"NEIS 서버 문제일 수 있으므로 잠시 후 재시도해 주세요.")
                                .setPositiveButton("확인",null)
                                .show());
                        return;
                    }
                }
                runOnUiThread(()->error("학교 검색 실패",firstError));
            }
        });
    }

    private String safeError(Exception e) {
        if (e instanceof NeisClient.ApiException) {
            return ((NeisClient.ApiException)e).code;
        }
        return "NETWORK";
    }

    private void schoolResults(List<Models.School> r){
        if(r.isEmpty()){new AlertDialog.Builder(this).setTitle("검색 결과 없음").setMessage("학교명 또는 인증키를 확인하세요.").setPositiveButton("확인",null).show();return;}
        String[] labels=new String[r.size()];
        for(int i=0;i<r.size();i++){Models.School x=r.get(i);labels[i]=x.name+" · "+x.kind+"\n"+x.address;}
        new AlertDialog.Builder(this).setTitle("학교 선택").setItems(labels,(d,w)->{storage.saveSchool(r.get(w));toast(r.get(w).name+" 선택 완료");showTab(4);}).setNegativeButton("취소",null).show();
    }

    private void taskDialog(Models.StudyTask existing) {
        LinearLayout box=column();
        box.setPadding(dp(18),dp(4),dp(18),0);
        Spinner type=new Spinner(this);
        String[] types={"과제","수행평가","시험","준비물"};
        type.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,types));
        if(existing!=null){
            for(int i=0;i<types.length;i++)if(types[i].equals(existing.type))type.setSelection(i);
        }
        EditText subject=input("과목",existing==null?"":existing.subject);
        EditText task=input("내용",existing==null?"":existing.title);
        LocalDate picked=existing==null?LocalDate.now().plusDays(1):existing.dueDate;
        EditText due=input("마감일 선택",picked.toString());
        due.setFocusable(false);
        due.setOnClickListener(view->{
            LocalDate current;
            try{current=LocalDate.parse(due.getText().toString());}
            catch(Exception e){current=LocalDate.now();}
            new DatePickerDialog(this,(picker,y,month,day)->
                    due.setText(LocalDate.of(y,month+1,day).toString()),
                    current.getYear(),current.getMonthValue()-1,current.getDayOfMonth()).show();
        });
        EditText importance=input("중요도 1~3",existing==null?"2":String.valueOf(existing.importance));
        importance.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(type);box.addView(subject);box.addView(task);box.addView(due);box.addView(importance);
        new AlertDialog.Builder(this).setTitle(existing==null?"학습 일정 추가":"학습 일정 수정")
                .setView(box).setPositiveButton(existing==null?"추가":"저장",(d,w)->{
                    try{
                        String t=task.getText().toString().trim();
                        if(t.isEmpty())throw new IllegalArgumentException();
                        LocalDate date=LocalDate.parse(due.getText().toString().trim());
                        int imp=Integer.parseInt(importance.getText().toString().trim());
                        if(imp<1||imp>3)throw new IllegalArgumentException();
                        long id=existing==null?System.currentTimeMillis():existing.id;
                        Models.StudyTask updated=new Models.StudyTask(id,
                                types[type.getSelectedItemPosition()],
                                subject.getText().toString().trim(),t,date,imp,
                                existing!=null&&existing.completed);
                        if(existing==null)storage.addTask(updated);
                        else storage.updateTask(updated);
                        showTab(3);
                    }catch(Exception e){toast("내용, 날짜, 중요도(1~3)를 확인하세요.");}
                }).setNegativeButton("취소",null).show();
    }

    private View taskCard(Models.StudyTask t) {
        LinearLayout box=card(),row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox check=new CheckBox(this);
        check.setChecked(t.completed);
        check.setOnCheckedChangeListener((b,checked)->{
            storage.setTaskCompleted(t.id,checked);
            showTab(3);
        });
        LinearLayout texts=column();
        texts.addView(tv(label(t),15,t.completed?MUTED:TEXT,true));
        texts.addView(tv(t.type+" · "+t.dueDate+" · "+dDay(t.dueDate)
                +" · 중요도 "+t.importance,12,MUTED,false));
        row.addView(check);
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1f));
        box.addView(row);
        LinearLayout controls=new LinearLayout(this);
        Button edit=secondary("수정");
        controls.addView(edit,new LinearLayout.LayoutParams(0,dp(46),1f));
        edit.setOnClickListener(v->taskDialog(t));
        Button delete=secondary("삭제");
        controls.addView(delete,new LinearLayout.LayoutParams(0,dp(46),1f));
        delete.setOnClickListener(v->
                new AlertDialog.Builder(this).setTitle("일정 삭제")
                        .setMessage(label(t)+"을(를) 삭제할까요?")
                        .setPositiveButton("삭제",(d,w)->{storage.deleteTask(t.id);showTab(3);})
                        .setNegativeButton("취소",null).show());
        box.addView(controls);
        return box;
    }

    private String dDay(LocalDate date) {
        long days=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(),date);
        if(days==0)return "D-day";
        if(days<0)return "D+"+(-days);
        return "D-"+days;
    }

    private View suppliesSummary(LocalDate date) {
        LinearLayout box=card();
        int count=0;
        for(Models.StudyTask t:storage.tasks()){
            if(!t.completed && "준비물".equals(t.type) && date.equals(t.dueDate)){
                count++;box.addView(tv("☐ "+label(t),14,TEXT,false));box.addView(spacer(5));
            }
        }
        if(count==0)box.addView(tv("등록된 준비물이 없습니다.",14,MUTED,false));
        return box;
    }

    private double score(Models.StudyTask t){
        long days=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(),t.dueDate);
        double urgency=days<=0?10:8.0/Math.min(8,days+1);
        double type="시험".equals(t.type)?3:("수행평가".equals(t.type)?2:1);
        return urgency+t.importance*2.0+type;
    }

    private String label(Models.StudyTask t){return (t.subject.trim().isEmpty()?"":t.subject+" · ")+t.title;}

    private View timetableSummary(LocalDate date){
        String raw=storage.getCache("timetable",timetableIdentity());if(raw.isEmpty())return cardText("시간표를 동기화하면 표시됩니다.",14);
        LinearLayout box=card();boolean any=false;
        for(Models.TimetableEntry e:decodeTimetable(raw)){if(!e.date.equals(date))continue;any=true;box.addView(tv(e.period+"교시  "+e.subject,15,TEXT,e.period==1));box.addView(spacer(6));}
        if(!any)box.addView(tv("오늘 등록된 수업이 없습니다.",14,MUTED,false));return box;
    }

    private View mealSummary(LocalDate date){
        String raw=storage.getCache("meals",mealIdentity());if(raw.isEmpty())return cardText("급식을 동기화하면 표시됩니다.",14);
        for(Models.Meal m:decodeMeals(raw))if(m.date.equals(date))return cardText(m.menu+(m.kcal.trim().isEmpty()?"":"\n\n"+m.kcal),14);
        return cardText("오늘 중식 데이터가 없습니다.",14);
    }

    private View prioritySummary(){
        List<Models.StudyTask> list=storage.tasks();list.removeIf(t->t.completed);list.sort(Comparator.comparingDouble(this::score).reversed());
        LinearLayout box=card();if(list.isEmpty()){box.addView(tv("등록된 할 일이 없습니다.",14,MUTED,false));return box;}
        for(int i=0;i<Math.min(3,list.size());i++){box.addView(tv((i+1)+". "+label(list.get(i)),14,TEXT,i==0));box.addView(spacer(6));}return box;
    }

    private String timetableIdentity(){Models.School s=storage.school();return s==null?"":s.identity()+":"+storage.grade()+":"+storage.className()+":"+weekStart();}
    private String mealIdentity(){Models.School s=storage.school();return s==null?"":s.identity()+":"+weekStart();}

    private String encodeTimetable(List<Models.TimetableEntry> list){
        JSONArray a=new JSONArray();try{for(Models.TimetableEntry e:list){JSONObject o=new JSONObject();o.put("date",e.date.toString());o.put("period",e.period);o.put("subject",e.subject);a.put(o);}}catch(Exception ignored){}return a.toString();
    }
    private List<Models.TimetableEntry> decodeTimetable(String raw){
        List<Models.TimetableEntry> out=new ArrayList<>();try{JSONArray a=new JSONArray(raw);for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);out.add(new Models.TimetableEntry(LocalDate.parse(o.getString("date")),o.getInt("period"),o.getString("subject")));}}catch(Exception ignored){}return out;
    }
    private String encodeMeals(List<Models.Meal> list){
        JSONArray a=new JSONArray();try{for(Models.Meal m:list){JSONObject o=new JSONObject();o.put("date",m.date.toString());o.put("menu",m.menu);o.put("kcal",m.kcal);a.put(o);}}catch(Exception ignored){}return a.toString();
    }
    private List<Models.Meal> decodeMeals(String raw){
        List<Models.Meal> out=new ArrayList<>();try{JSONArray a=new JSONArray(raw);for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);out.add(new Models.Meal(LocalDate.parse(o.getString("date")),o.getString("menu"),o.optString("kcal","")));}}catch(Exception ignored){}return out;
    }

    private void error(String heading,Exception e){
        String detail=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
        if(e instanceof NeisClient.ApiException){NeisClient.ApiException a=(NeisClient.ApiException)e;detail=a.code+"\n"+detail;}
        new AlertDialog.Builder(this).setTitle(heading).setMessage(detail+"\n\n가짜 데이터로 대체하지 않았습니다. 기존 정상 캐시는 유지됩니다.").setPositiveButton("확인",null).show();
    }

    private TextView section(String x){TextView t=tv(x,17,TEXT,true);t.setPadding(dp(2),dp(18),dp(2),dp(8));return t;}
    private LinearLayout card(){
        LinearLayout l=column();l.setPadding(dp(16),dp(14),dp(16),dp(14));GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setCornerRadius(dp(18));g.setStroke(dp(1),BORDER);l.setBackground(g);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));l.setLayoutParams(p);return l;
    }
    private View cardText(String x,int size){LinearLayout c=card();c.addView(tv(x,size,TEXT,false));return c;}
    private View infoCard(String h,String b,String action,View.OnClickListener l){LinearLayout c=card();c.addView(tv(h,18,TEXT,true));TextView t=tv(b,14,MUTED,false);t.setPadding(0,dp(8),0,dp(12));c.addView(t);Button btn=primary(action);btn.setOnClickListener(l);c.addView(btn);return c;}
    private Button primary(String x){Button b=new Button(this);b.setText(x);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);GradientDrawable g=new GradientDrawable();g.setColor(BLUE);g.setCornerRadius(dp(14));b.setBackground(g);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50));p.setMargins(0,dp(4),0,dp(6));b.setLayoutParams(p);return b;}
    private Button secondary(String x){Button b=new Button(this);b.setText(x);b.setTextColor(BLUE_DARK);b.setTextSize(14);b.setAllCaps(false);GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setStroke(dp(1),BORDER);g.setCornerRadius(dp(14));b.setBackground(g);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.setMargins(0,dp(4),0,dp(6));b.setLayoutParams(p);return b;}
    private EditText input(String hint,String value){EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setTextSize(14);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setPadding(dp(14),0,dp(14),0);GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setStroke(dp(1),BORDER);g.setCornerRadius(dp(14));e.setBackground(g);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.setMargins(0,dp(4),0,dp(6));e.setLayoutParams(p);return e;}
    private TextView tv(String x,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(x);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setLineSpacing(0,1.08f);return t;}
    private TextView pill(String x,int bg,int fg){TextView t=tv(x,11,fg,true);t.setGravity(Gravity.CENTER);t.setPadding(dp(9),dp(4),dp(9),dp(4));GradientDrawable g=new GradientDrawable();g.setColor(bg);g.setCornerRadius(dp(99));t.setBackground(g);return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private View spacer(int h){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(1,dp(h)));return v;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void toast(String x){Toast.makeText(this,x,Toast.LENGTH_SHORT).show();}
}
