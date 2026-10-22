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
    private StudyProgressStorage progressStorage;
    private StudyTaskDetailStorage detailStorage;
    private StudyWrongAnswers wrongAnswers;
    private final NeisClient neis = new NeisClient();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private FrameLayout content;
    private TextView title;
    private int currentTab;
    private static final int BACKUP_EXPORT = 4801;
    private static final int BACKUP_IMPORT = 4802;
    private static final int PROGRESS_EXPORT = 4803;
    private static final int PROGRESS_IMPORT = 4804;
    private static final int DETAILS_EXPORT = 4805;
    private static final int DETAILS_IMPORT = 4806;
    private static final int WRONG_EXPORT = 4807;
    private static final int WRONG_IMPORT = 4808;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        storage = new Storage(this);
        progressStorage = new StudyProgressStorage(this);
        detailStorage = new StudyTaskDetailStorage(this);
        wrongAnswers = new StudyWrongAnswers(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.WHITE);

        boolean tablet=getResources().getConfiguration().smallestScreenWidthDp>=600;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(tablet ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout central=column();
        central.addView(toolbar());
        content=new FrameLayout(this);
        central.addView(content,new LinearLayout.LayoutParams(-1,0,1f));
        if(tablet) {
            root.addView(tabletNavigation());
            root.addView(central,new LinearLayout.LayoutParams(0,-1,1f));
        } else {
            central.addView(bottomNav());
            root.addView(central,new LinearLayout.LayoutParams(-1,-1));
        }
        setContentView(root);
        Reminders.schedule(this);
        showTab(0);
        AppUpdates.check(this, false);
        if(getIntent()!=null && getIntent().getBooleanExtra("studyone_ai_self_test",false)){
            if(!AiClient.selfTest())throw new IllegalStateException("AI fixture parser test failed");
            android.util.Log.i("StudyOneAI","AI_OFFLINE_FIXTURE_TEST_PASSED");
        }
        if(getIntent()!=null && getIntent().getBooleanExtra("studyone_sync_self_test",false)){
            if(!StudySyncClient.selfTest())throw new IllegalStateException("Sync client fixture failed");
            android.util.Log.i("StudyOneSync","SYNC_CREATE_ONLY_GUARDS_PASSED");
        }
        // Emulator-only synthetic HTTP round-trip: never touches local user data.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent()!=null &&
                getIntent().getBooleanExtra("studyone_sync_network_self_test",false)) {
            final String ephemeralToken=getIntent().getStringExtra("studyone_sync_test_token");
            io.execute(()->{
                try {
                    JSONObject before=StudySyncClient.snapshot(ephemeralToken);
                    Models.StudyTask syntheticTask=new Models.StudyTask(1730000001234L,
                            "숙제 항목","과학","CI 실험 문제",LocalDate.of(2026,10,22),2,false);
                    StudyProgressItem syntheticProgress=StudyProgressItem.create(
                            "과학","오투","7-2","미완료",true);
                    JSONArray candidates=StudySyncClient.prepareNewOnly(
                            java.util.Collections.singletonList(syntheticTask),
                            java.util.Collections.singletonList(syntheticProgress),before);
                    if(candidates.length()!=2)
                        throw new IllegalStateException("Expected two synthetic entries");
                    if(StudySyncClient.uploadNew(ephemeralToken,candidates)!=2)
                        throw new IllegalStateException("Synthetic upload ack incomplete");
                    JSONObject after=StudySyncClient.snapshot(ephemeralToken);
                    if(StudySyncClient.prepareNewOnly(
                            java.util.Collections.singletonList(syntheticTask),
                            java.util.Collections.singletonList(syntheticProgress),after).length()!=0)
                        throw new IllegalStateException("Existing IDs must be skipped");
                    android.util.Log.i("StudyOneSync","LOCAL_SYNC_HTTP_ROUNDTRIP_PASSED");
                } catch(Exception ignored) {
                    android.util.Log.e("StudyOneSync","LOCAL_SYNC_HTTP_ROUNDTRIP_FAILED");
                }
            });
        }
        if(getIntent()!=null && getIntent().getBooleanExtra("studyone_progress_self_test",false)){
            if(!StudyProgressStorage.selfTestBackup()) {
                throw new IllegalStateException("Progress backup fixture round-trip failed");
            }
            android.util.Log.i("StudyOneProgress","PROGRESS_BACKUP_ROUNDTRIP_PASSED");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        StudyWidget.refresh(this);
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
        String[] n={"홈","시간표","급식","플래너","추천","설정"};
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

    private View tabletNavigation() {
        LinearLayout rail=column();
        rail.setBackgroundColor(Color.WHITE);
        rail.setPadding(dp(8),dp(50),dp(8),dp(12));
        String[] tabs={"홈","시간표","급식","플래너","추천","설정"};
        for(int i=0;i<tabs.length;i++){
            final int index=i;
            Button b=new Button(this);
            b.setAllCaps(false);
            b.setText(tabs[i]);
            b.setTextSize(14);
            b.setTextColor(BLUE_DARK);
            b.setBackgroundColor(Color.TRANSPARENT);
            b.setOnClickListener(v->showTab(index));
            rail.addView(b,new LinearLayout.LayoutParams(dp(122),dp(58)));
        }
        return rail;
    }

    private void showTab(int tab) {
        currentTab=tab;
        content.removeAllViews();
        if(tab==0){title.setText("오늘");content.addView(home());}
        else if(tab==1){title.setText("주간 시간표");content.addView(timetable());}
        else if(tab==2){title.setText("이번 주 급식");content.addView(meals());}
        else if(tab==3){title.setText("학습 플래너");content.addView(planner());}
        else if(tab==4){title.setText("공부 추천");content.addView(studyCoach());}
        else{title.setText("설정");content.addView(settings());}
        StudyWidget.refresh(this);
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
                    "설정하기",v->showTab(5)));
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
            c.addView(infoCard("학교 설정이 필요해요","학교를 설정하면 주간 시간표를 표시합니다.","설정으로",v->showTab(5)));
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
            c.addView(infoCard("학교 설정이 필요해요","학교를 설정하면 실제 NEIS 중식을 표시합니다.","설정으로",v->showTab(5)));
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
        Button add=primary("+ 학습 일정 추가");
        add.setOnClickListener(v->taskDialog(null)); c.addView(add);

        Button roles=secondary("5개 학습 분류의 역할 보기");
        roles.setOnClickListener(v->new AlertDialog.Builder(this)
                .setTitle("학습 분류 안내")
                .setMessage(StudyCategories.guide())
                .setPositiveButton("확인",null).show());
        c.addView(roles);

        Button copyToday=secondary("오늘 할꺼 · 과목별 리마인더 목록 복사");
        copyToday.setOnClickListener(v->{
            try {
                List<Models.StudyTask> current=storage.tasks();
                LocalDate today=LocalDate.now();
                java.util.Map<Long,StudyTaskDetail> details=new java.util.HashMap<>();
                for(Models.StudyTask item:StudyDailyChecklist.visible(current,today)) {
                    StudyTaskDetail extra=detailStorage.get(item.id);
                    if(extra!=null)details.put(item.id,extra);
                }
                String content=StudyDailyChecklist.format(current,today,details);
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                if(clipboard==null)throw new IllegalStateException("클립보드에 접근할 수 없습니다.");
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("StudyOne 오늘 할꺼",content));
                toast("오늘·미완료 이월 과목별 체크리스트를 복사했습니다.");
            } catch (RuntimeException error) {
                toast(error.getMessage()==null?"일별 계획 복사 실패":error.getMessage());
            }
        });
        c.addView(copyToday);

        c.addView(cardText("예상 시간·공부 단계·완료 기준은 기존 일정·동기화 JSON과 별도로 저장합니다. 다른 기기에는 전용 JSON 백업을 직접 가져와야 합니다.",12));
        Button detailExport=secondary("학습 상세 정보 JSON 백업");
        detailExport.setOnClickListener(v->{
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(android.content.Intent.EXTRA_TITLE,"StudyOne-task-details.json");
            startActivityForResult(intent,DETAILS_EXPORT);
        });
        c.addView(detailExport);
        Button detailImport=secondary("학습 상세 정보 JSON 가져오기");
        detailImport.setOnClickListener(v->{
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            startActivityForResult(intent,DETAILS_IMPORT);
        });
        c.addView(detailImport);

        appendWrongAnswerNotebook(c);

        // Put the live, directly tappable today's checklist before the broader progress tracker.
        // This is a view of existing local records, not a new persisted or uploaded plan.
        List<Models.StudyTask> tasks=storage.tasks();
        appendTodayChecklist(c,tasks,LocalDate.now());

        c.addView(section("교재 · 시험범위 · 진도"));
        c.addView(cardText("교재 진도는 기존 일별 숙제와 별도로 관리합니다. 기존 전체 일정 JSON 백업에는 진도가 포함되지 않으므로 아래의 별도 진도 JSON 백업을 사용하세요.",12));
        Button addProgress=secondary("+ 시험범위 진도 추가");
        addProgress.setOnClickListener(v->progressDialog(null));
        c.addView(addProgress);
        Button progressExport=secondary("진도 JSON 백업 내보내기");
        progressExport.setOnClickListener(v->{
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(android.content.Intent.EXTRA_TITLE,"StudyOne-progress.json");
            startActivityForResult(intent,PROGRESS_EXPORT);
        });
        c.addView(progressExport);
        Button progressImport=secondary("진도 JSON 백업 가져오기");
        progressImport.setOnClickListener(v->{
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            startActivityForResult(intent,PROGRESS_IMPORT);
        });
        c.addView(progressImport);
        Button copyProgress=secondary("과목명 포함 진도 체크리스트 복사");
        copyProgress.setOnClickListener(v->{
            try {
                String text=StudyProgressChecklist.format(progressStorage.list());
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                if(clipboard==null)throw new IllegalStateException("클립보드에 접근할 수 없습니다.");
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("StudyOne 진도",text));
                toast("과목별 진도 체크리스트를 복사했습니다.");
            } catch (RuntimeException error) {
                toast(error.getMessage()==null?"진도 복사 실패":error.getMessage());
            }
        });
        c.addView(copyProgress);
        try {
            List<StudyProgressItem> progress=progressStorage.list();
            int complete=0, total=0, pending=0, started=0, todo=0;
            for(StudyProgressItem item:progress) {
                if(!item.inExamScope)continue;
                total++;
                if(StudyProgressItem.DONE.equals(item.status))complete++;
                if(StudyProgressItem.UNKNOWN.equals(item.status))pending++;
                if(StudyProgressItem.IN_PROGRESS.equals(item.status))started++;
                if(StudyProgressItem.TODO.equals(item.status))todo++;
            }
            int percent=total==0?0:(int)Math.round(100.0*complete/total);
            c.addView(cardText("시험범위 "+total+"개 · 완료 "+complete+" · 진행 중 "+started
                    +" · 미완료 "+todo+" · 확인 필요 "+pending+" · 완료율 "+percent+"%",13));
            android.widget.ProgressBar meter=new android.widget.ProgressBar(this,null,
                    android.R.attr.progressBarStyleHorizontal);
            meter.setMax(Math.max(total,1));
            meter.setProgress(complete);
            c.addView(meter);
            if(progress.isEmpty()) {
                c.addView(cardText("등록된 교재 진도가 없습니다. 위 버튼에서 직접 추가할 수 있습니다.",13));
            } else {
                progress.sort(Comparator.comparing((StudyProgressItem item)->item.subject)
                        .thenComparing(item->item.material).thenComparing(item->item.unit));
                for(StudyProgressItem item:progress) c.addView(progressCard(item));
            }
        } catch (IllegalStateException e) {
            c.addView(cardText("진도 데이터 읽기 오류: "+e.getMessage(),13));
        }

        if(tasks.isEmpty()){
            c.addView(spacer(12));
            c.addView(cardText("일별 일정은 아직 없습니다. 위에서 교재 진도를 별도로 관리할 수 있습니다.",14));
            return s;
        }

        int done=0;
        for(Models.StudyTask t:tasks) if(t.completed) done++;
        c.addView(cardText("전체 "+tasks.size()+"건 · 완료 "+done+"건 · 미완료 "+(tasks.size()-done)+"건",15));

        Button calendar=secondary("월간 달력에서 일정 보기");
        calendar.setOnClickListener(view->calendarDialog());
        c.addView(calendar);

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

        // Retain every historical and future task, but don't duplicate all of them above the fold.
        LinearLayout everyTask=column();
        everyTask.setVisibility(View.GONE);
        Button expandAll=secondary("과거·미래 포함 전체 일정 펼치기");
        expandAll.setOnClickListener(v->{
            boolean opening=everyTask.getVisibility()!=View.VISIBLE;
            everyTask.setVisibility(opening?View.VISIBLE:View.GONE);
            expandAll.setText(opening?"전체 일정 접기":"과거·미래 포함 전체 일정 펼치기");
        });
        c.addView(expandAll);
        c.addView(everyTask);
        everyTask.addView(section("분류별 전체 일정"));
        for(String role:StudyCategories.ROLE_TITLES) {
            boolean hasRole=false;
            for(Models.StudyTask t:tasks) {
                if(!role.equals(t.type))continue;
                if(!hasRole){everyTask.addView(section(role));hasRole=true;}
                everyTask.addView(taskCard(t));
            }
        }
        boolean hasLegacy=false;
        for(Models.StudyTask t:tasks) {
            if(StudyCategories.isRole(t.type))continue;
            if(!hasLegacy){everyTask.addView(section("기존 일정 (이전 분류)"));hasLegacy=true;}
            everyTask.addView(taskCard(t));
        }
        return s;
    }

    /** A live, clickable five-role snapshot; carryover is presentation only. */
    private void appendTodayChecklist(LinearLayout root,List<Models.StudyTask> all,LocalDate today) {
        List<Models.StudyTask> selected=StudyDailyChecklist.visible(all,today);
        int complete=0;
        for(Models.StudyTask task:selected)if(task.completed)complete++;
        root.addView(section("오늘 할꺼 · 삼성 리마인더 체크리스트"));
        root.addView(cardText(today+" · 오늘과 전날 미완료 항목 · 완료 "+complete+"/"+selected.size()
                +"개\n체크하면 기기 내 원래 일정의 완료 상태가 바뀝니다. 마감일·분류는 유지됩니다.",13));
        for(int i=0;i<StudyCategories.ROLE_TITLES.length;i++) {
            root.addView(section((i+1)+". "+StudyCategories.ROLE_TITLES[i]));
            int count=0;
            for(Models.StudyTask task:selected) {
                if(StudyDailyChecklist.groupIndex(task,today)!=i)continue;
                if(task.dueDate.isBefore(today))
                    root.addView(cardText("전날 못 다 · 원래 마감 "+task.dueDate,12));
                root.addView(taskCard(task));
                count++;
            }
            if(count==0)root.addView(cardText("등록된 할 일이 없습니다.",12));
        }
        int legacy=0;
        for(Models.StudyTask task:selected) {
            if(StudyDailyChecklist.groupIndex(task,today)!=5)continue;
            if(legacy++==0)root.addView(section("기존 분류 일정"));
            root.addView(taskCard(task));
        }
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


        c.addView(section("보안 HTTPS 동기화 실험 · Canary"));
        c.addView(cardText("승인된 HTTPS 계정 서버가 있어야 작동합니다. 실제 계정·인증서·도메인이 아직 없다면 연결되지 않습니다. Debug 에뮬레이터만 localhost HTTP 테스트를 이용할 수 있습니다. 기존 데이터의 자동 수정/삭제 기능은 없습니다.",12));
        Button sync=secondary("계정 서버 수동 연결·신규 항목 전송");
        sync.setOnClickListener(v->syncExperimentDialog());
        c.addView(sync);

        c.addView(section("학습 알림"));
        c.addView(cardText("알림은 사용자가 직접 켠 경우에만 작동합니다. 배터리 정책에 따라 설정 시각보다 늦게 도착할 수 있습니다.",12));
        for(int slot=0;slot<2;slot++){
            final int id=slot;
            CheckBox enabled=new CheckBox(this);
            enabled.setText(slot==0?"아침 일정 알림":"저녁 미완료 일정 알림");
            enabled.setChecked(Reminders.enabled(this,slot));
            enabled.setOnCheckedChangeListener((button,checked)->{
                if(checked && android.os.Build.VERSION.SDK_INT>=33
                        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                           !=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},4120);
                    toast("알림 허용 여부를 확인해 주세요.");
                }
                Reminders.setEnabled(this,id,checked);
            });
            c.addView(enabled);
            Button time=secondary("시간 설정: "+
                    String.format(Locale.KOREAN,"%02d:%02d",Reminders.hour(this,slot),Reminders.minute(this,slot)));
            time.setOnClickListener(view->
                new android.app.TimePickerDialog(this,(picker,hour,minute)->{
                    Reminders.setTime(this,id,hour,minute);
                    showTab(5);
                },Reminders.hour(this,id),Reminders.minute(this,id),true).show());
            c.addView(time);
        }

        c.addView(section("백업 / 복원"));
        c.addView(cardText("과제·학교·학년·반 정보만 JSON 파일로 내보냅니다. NEIS 인증키는 절대 포함하지 않습니다. Google Drive나 Dropbox는 기기의 파일 선택기에서 저장 위치로 고를 수 있습니다.",12));
        Button export=secondary("내 데이터 백업 저장");
        export.setOnClickListener(view->{
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(android.content.Intent.EXTRA_TITLE,"StudyOne-backup.json");
            startActivityForResult(intent,BACKUP_EXPORT);
        });
        c.addView(export);
        Button restore=secondary("백업 JSON 가져오기");
        restore.setOnClickListener(view->{
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            startActivityForResult(intent,BACKUP_IMPORT);
        });
        c.addView(restore);

        c.addView(section("홈 화면 위젯"));
        c.addView(cardText("홈 화면을 길게 눌러 위젯 → StudyOne을 선택하세요. 미완료 일정과 가장 가까운 마감일이 표시됩니다.",13));

        c.addView(section("업데이트"));
        c.addView(cardText("최신 버전은 공식 GitHub 릴리즈에서 확인합니다. 새 APK는 해시·서명 검증 후 Android 설치 화면을 엽니다. 자동 설치나 강제 업데이트는 하지 않습니다.",12));
        Button checkUpdates=primary("새 버전 확인");
        checkUpdates.setOnClickListener(view->AppUpdates.check(this,true));
        c.addView(checkUpdates);
        Button retryUpdate=secondary("검증된 업데이트 설치 다시 시도");
        retryUpdate.setOnClickListener(view->AppUpdates.retryVerifiedInstall(this));
        c.addView(retryUpdate);

        c.addView(section("앱 정보"));
        c.addView(cardText("StudyOne 3.0.0-beta.1\nAndroid 네이티브 재설계\nAPI 37 / Android 17 대응",13));
        return s;
    }

    /**
     * Development-only manual sync: never sends data until a second explicit
     * confirmation. It never mutates local tasks or progress records.
     */
    private void syncExperimentDialog() {
        LinearLayout form=column();
        form.setPadding(dp(20),dp(6),dp(20),dp(8));
        form.addView(cardText("보안 계정 서버: HTTPS 필수. Debug에서만 127.0.0.1 로컬 HTTP 테스트가 가능합니다.\n"
                +"기존 기록의 자동 수정·삭제와 백그라운드 동기화는 아직 지원하지 않습니다.",12));
        boolean debug=(getApplicationInfo().flags &
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        EditText address=input("HTTPS 서버 URL (예: https://example.com/)",
                debug?"http://127.0.0.1:8765/":"https://");
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(address);
        EditText token=input("로컬 테스트 Bearer 토큰 (저장 안 함)","");
        token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(token);
        TextView result=tv("서버를 읽기 전입니다. 서버 프로그램을 별도로 실행해야 합니다.",13,MUTED,false);
        result.setPadding(dp(4),dp(12),dp(4),dp(12));
        Button preview=secondary("서버 기록 수 확인 · 전송 없음");
        Button upload=primary("서버에 없는 새 항목만 전송");
        Button pendingPlans=secondary("AI 공부 계획 승인 대기함");
        Button pullTasks=secondary("서버의 신규 일정만 안전하게 가져오기");
        Button pullProgress=secondary("서버의 신규 진도만 안전하게 가져오기");
        form.addView(preview);
        form.addView(upload);
        form.addView(pullTasks);
        form.addView(pullProgress);
        form.addView(pendingPlans);
        form.addView(result);
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("StudyOne 로컬 동기화 실험")
                .setView(form)
                .setPositiveButton("닫기",(d,w)->{})
                .create();
        dialog.setOnDismissListener(d->token.setText(""));
        dialog.show();

        pullTasks.setOnClickListener(v->
                importNewRemote(true, token.getText().toString(),
                        address.getText().toString().trim(),result,dialog));
        pullProgress.setOnClickListener(v->
                importNewRemote(false,token.getText().toString(),
                        address.getText().toString().trim(),result,dialog));

        pendingPlans.setOnClickListener(v->{
            final String secret=token.getText().toString();
            final String endpoint=address.getText().toString().trim();
            showStudyPlanInbox(secret,endpoint,result,dialog);
        });

        preview.setOnClickListener(v->{
            String secret=token.getText().toString();
            String endpoint=address.getText().toString().trim();
            preview.setEnabled(false);
            result.setText("로컬 서버 조회 중…");
            io.execute(()->{
                try {
                    JSONObject server=StudySyncClient.snapshot(secret,endpoint);
                    JSONArray records=server.getJSONArray("records");
                    int tasks=0, progress=0;
                    for(int i=0;i<records.length();i++){
                        if("task".equals(records.getJSONObject(i).getString("kind")))tasks++;
                        else progress++;
                    }
                    final String message="로컬 서버 조회 완료 · 변경 cursor "
                            +server.getLong("cursor")+"\n서버 일정 "+tasks+"개 · 진도 "+progress
                            +"개\n기기에 저장된 데이터를 서버로 전송하지 않았습니다.";
                    runOnUiThread(()->{
                        if(!isFinishing() && !isDestroyed() && dialog.isShowing()) {
                            result.setText(message);
                            preview.setEnabled(true);
                        }
                    });
                }catch(Exception e){
                    runOnUiThread(()->{
                        if(!isFinishing() && !isDestroyed() && dialog.isShowing()){
                            result.setText("연결 실패: 로컬 서버 실행 상태·토큰을 확인하세요.");
                            preview.setEnabled(true);
                        }
                    });
                }
            });
        });

        upload.setOnClickListener(v->{
            final String secret=token.getText().toString();
            final String endpoint=address.getText().toString().trim();
            upload.setEnabled(false);
            result.setText("서버·로컬 진도를 검증해 전송 후보를 확인 중…");
            io.execute(()->{
                try {
                    JSONArray prepared=StudySyncClient.prepareNewOnly(
                            storage.checkedTasksForSync(), progressStorage.list(),
                            StudySyncClient.snapshot(secret,endpoint));
                    final int count=prepared.length();
                    runOnUiThread(()->{
                        if(isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                        upload.setEnabled(true);
                        if(count==0){
                            result.setText("서버에 없는 새 항목이 없습니다. 기존 기록은 수정하지 않았습니다.");
                            return;
                        }
                        new AlertDialog.Builder(this)
                                .setTitle("새 학습 기록 전송 승인")
                                .setMessage("이 기기의 일정·진도 중 서버에 없는 "+count
                                        +"개를 지정한 HTTPS 계정 서버 또는 로컬 테스트 서버로 전송하시겠습니까?\n"
                                        +"과목·교재·학습 진도 정보가 저장됩니다. "
                                        +"기존 서버 기록과 기기 기록은 수정·삭제하지 않습니다.")
                                .setNegativeButton("취소",(dd,w)->result.setText("전송을 취소했습니다."))
                                .setPositiveButton("새 기록 전송",(dd,w)->{
                                    upload.setEnabled(false);
                                    result.setText("전송 직전 데이터 변경 여부를 다시 확인 중…");
                                    io.execute(()->{
                                        try {
                                            JSONArray confirmed=StudySyncClient.prepareNewOnly(
                                                    storage.checkedTasksForSync(),progressStorage.list(),
                                                    StudySyncClient.snapshot(secret,endpoint));
                                            if(!prepared.toString().equals(confirmed.toString())){
                                                throw new IllegalStateException(
                                                    "확인 도중 데이터가 바뀌어 전송을 중단했습니다.");
                                            }
                                            int sent=StudySyncClient.uploadNew(secret,confirmed,endpoint);
                                            runOnUiThread(()->{
                                                if(!isFinishing() && !isDestroyed() && dialog.isShowing()){
                                                    result.setText("서버에 새 기록 "+sent
                                                            +"개 전송 완료. 기존 기록은 덮어쓰지 않았습니다.");
                                                    upload.setEnabled(true);
                                                }
                                            });
                                        }catch(Exception error){
                                            runOnUiThread(()->{
                                                if(!isFinishing() && !isDestroyed() && dialog.isShowing()){
                                                    result.setText("전송을 중단했습니다. 서버 목록을 다시 확인하세요. "
                                                            +"일부 배치는 반영되었을 수 있습니다.");
                                                    upload.setEnabled(true);
                                                }
                                            });
                                        }
                                    });
                                }).show();
                    });
                }catch(Exception error){
                    runOnUiThread(()->{
                        if(!isFinishing() && !isDestroyed() && dialog.isShowing()){
                            result.setText("데이터 검증 또는 연결 실패. 전송하지 않았습니다.");
                            upload.setEnabled(true);
                        }
                    });
                }
            });
        });
    }

    /** Manual additive-only download, two server reads and local backup. */
    private void importNewRemote(boolean tasks, String token, String endpoint,
                                 TextView result, AlertDialog parent) {
        final String kind=tasks?"일정":"진도";
        result.setText("서버의 신규 "+kind+"을(를) 안전하게 검증 중…");
        io.execute(()->{
            try {
                final String originalTasks=storage.rawTasksForSync();
                final String originalProgress=progressStorage.rawProgressForSync();
                List<Models.StudyTask> localTasks=storage.checkedTasksForSync();
                List<StudyProgressItem> localProgress=progressStorage.list();
                StudySyncClient.IncomingPlan first=StudySyncClient.previewInbound(
                        localTasks,localProgress,StudySyncClient.snapshot(token,endpoint));
                int count=tasks?first.newTasks.size():first.newProgress.size();
                StringBuilder items=new StringBuilder();
                if(tasks) {
                    for(int i=0;i<Math.min(10,count);i++){
                        Models.StudyTask item=first.newTasks.get(i);
                        items.append("\n").append(i+1).append(". ")
                                .append(item.subject).append(" · ").append(item.title);
                    }
                }else{
                    for(int i=0;i<Math.min(10,count);i++){
                        StudyProgressItem item=first.newProgress.get(i);
                        items.append("\n").append(i+1).append(". ")
                                .append(item.subject).append(" · ").append(item.unit);
                    }
                }
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed()||!parent.isShowing())return;
                    if(count==0){
                        result.setText("서버에만 있는 신규 "+kind+"이 없습니다. 기존 ID 충돌 "
                                +first.conflicts+"개는 덮어쓰지 않았습니다.");
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("서버의 신규 "+kind+" "+count+"개 가져오기")
                            .setMessage("가져오기 전에 기기의 기존 "+kind+" 데이터를 "
                                    +"앱 전용 보관함에 백업합니다. 동일한 ID는 절대 덮어쓰지 않습니다.\n"
                                    +"기존 항목과 내용이 다른 ID "+first.conflicts
                                    +"개는 그대로 보존합니다.\n"+items.toString()
                                    +(count>10?"\n외 "+(count-10)+"개":"")
                                    +"\n\n확인 후 추가하시겠습니까?")
                            .setNegativeButton("취소",(d,w)->
                                    result.setText("가져오기를 취소했습니다. 기존 기록은 그대로입니다."))
                            .setPositiveButton("신규 "+kind+"만 추가",(d,w)->{
                                result.setText("승인 후 서버/로컬 기록을 다시 확인 중…");
                                io.execute(()->{
                                    try {
                                        if(!originalTasks.equals(storage.rawTasksForSync()) ||
                                                !java.util.Objects.equals(originalProgress,
                                                        progressStorage.rawProgressForSync()))
                                            throw new IllegalStateException(
                                                    "확인 도중 로컬 기록이 변경되었습니다.");
                                        StudySyncClient.IncomingPlan second=
                                                StudySyncClient.previewInbound(
                                                    storage.checkedTasksForSync(),
                                                    progressStorage.list(),
                                                    StudySyncClient.snapshot(token,endpoint));
                                        if(!StudySyncClient.sameInbound(first,second,tasks))
                                            throw new IllegalStateException(
                                                    "확인 도중 서버 기록이 변경되었습니다.");
                                        String backup=tasks?
                                                storage.appendNewTasksForSync(
                                                        originalTasks,second.newTasks,
                                                        getApplicationContext()):
                                                progressStorage.appendNewProgressForSync(
                                                        originalProgress,second.newProgress,
                                                        getApplicationContext());
                                        runOnUiThread(()->{
                                            if(!isFinishing()&&!isDestroyed()){
                                                StudyWidget.refresh(this);
                                                if(parent.isShowing())
                                                    result.setText("신규 "+kind+" "+count
                                                            +"개를 안전하게 추가했습니다. "
                                                            +"기존 ID "+second.conflicts
                                                            +"개는 보존했습니다.\n개인 백업: "+backup);
                                            }
                                        });
                                    }catch(Exception error){
                                        runOnUiThread(()->{
                                            if(!isFinishing()&&!isDestroyed()&&parent.isShowing())
                                                result.setText("안전 검사 또는 저장 오류로 가져오기를 중단했습니다."
                                                        +" 기존 로컬 기록을 확인해 주세요.");
                                        });
                                    }
                                });
                            }).show();
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(!isFinishing()&&!isDestroyed()&&parent.isShowing())
                        result.setText("서버 데이터를 검증하지 못했습니다. 아무것도 가져오지 않았습니다.");
                });
            }
        });
    }

    private void showStudyPlanInbox(String token, String endpoint,
                                    TextView result, AlertDialog parent) {
        result.setText("AI 계획 승인 대기함을 확인 중…");
        io.execute(()->{
            try {
                JSONArray proposals=StudySyncClient.pendingProposals(token,endpoint)
                        .getJSONArray("proposals");
                String[] names=new String[proposals.length()];
                for(int i=0;i<proposals.length();i++){
                    JSONObject item=proposals.getJSONObject(i);
                    names[i]=item.getString("title")+" · "+item.getInt("taskCount")+"개";
                }
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed()||!parent.isShowing())return;
                    result.setText("승인 대기 공부 계획 "+names.length+"개를 찾았습니다.");
                    if(names.length==0){
                        toast("승인 대기 중인 공부 계획이 없습니다.");
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("AI가 제안한 공부 계획")
                            .setItems(names,(d,which)->{
                                try{
                                    JSONObject item=proposals.getJSONObject(which);
                                    openStudyPlanDecision(token,endpoint,item,result,parent);
                                }catch(Exception ignored){toast("초안을 다시 읽어주세요.");}
                            })
                            .setNegativeButton("닫기",null).show();
                });
            }catch(Exception error) {
                runOnUiThread(()->{
                    if(!isFinishing()&&!isDestroyed()&&parent.isShowing())
                        result.setText("승인 대기함 조회 실패. HTTPS 계정 서버/토큰을 확인하세요.");
                });
            }
        });
    }

    private void openStudyPlanDecision(String token, String endpoint,
                                       JSONObject plan, TextView result,
                                       AlertDialog parent) throws Exception {
        String id=plan.getString("id");
        JSONArray tasks=plan.getJSONArray("tasks");
        StringBuilder details=new StringBuilder();
        details.append("승인 전에는 실제 숙제 목록에 저장되지 않습니다.\n\n");
        for(int i=0;i<tasks.length();i++){
            JSONObject t=tasks.getJSONObject(i);
            details.append(i+1).append(". [").append(t.getString("dueDate"))
                    .append("] ").append(t.getString("type")).append(" · ")
                    .append(t.getString("subject")).append("\n")
                    .append(t.getString("title")).append("\n\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("AI 공부 계획: "+plan.getString("title"))
                .setMessage(details.toString())
                .setPositiveButton("전체 승인",(d,w)->
                        decideStudyPlan(token,endpoint,plan,true,result,parent))
                .setNegativeButton("전체 거부",(d,w)->
                        decideStudyPlan(token,endpoint,plan,false,result,parent))
                .setNeutralButton("취소",null)
                .show();
    }

    private void decideStudyPlan(String token, String endpoint, JSONObject expected,
                                 boolean approve, TextView result, AlertDialog parent) {
        result.setText("계획 처리 전 최신 초안을 다시 확인 중…");
        io.execute(()->{
            try {
                JSONArray latest=StudySyncClient.pendingProposals(token,endpoint)
                        .getJSONArray("proposals");
                JSONObject same=null;
                for(int i=0;i<latest.length();i++){
                    JSONObject candidate=latest.getJSONObject(i);
                    if(expected.getString("id").equals(candidate.getString("id"))){
                        same=candidate;break;
                    }
                }
                if(same==null || !same.toString().equals(expected.toString()))
                    throw new IllegalStateException("계획 내용이 변경되어 처리하지 않았습니다.");
                StudySyncClient.decideProposal(token,endpoint,
                        expected.getString("id"),approve);
                runOnUiThread(()->{
                    if(!isFinishing()&&!isDestroyed()&&parent.isShowing())
                        result.setText(approve?
                                "선택한 공부 계획을 승인해 서버 일정에 등록했습니다. "
                                +"현재 기기에 자동으로 복사되지는 않습니다.":
                                "선택한 AI 공부 계획을 거부했습니다. 숙제는 변경되지 않았습니다.");
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(!isFinishing()&&!isDestroyed()&&parent.isShowing())
                        result.setText("결정 결과를 확인하지 못했습니다. 대기함을 다시 조회하세요.");
                });
            }
        });
    }

    private LocalDate weekStart() {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private String errorHint(String key) {
        String value=storage.lastError(key);
        return value.isEmpty() ? "" : "\n최근 동기화 오류: "+value+"\n재시도 버튼을 눌러 주세요.";
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
        new AlertDialog.Builder(this).setTitle("학교 선택").setItems(labels,(d,w)->{storage.saveSchool(r.get(w));toast(r.get(w).name+" 선택 완료");showTab(5);}).setNegativeButton("취소",null).show();
    }

    private void calendarDialog() {
        android.widget.CalendarView calendar=new android.widget.CalendarView(this);
        calendar.setOnDateChangeListener((view,year,month,day)->{
            LocalDate chosen=LocalDate.of(year,month+1,day);
            List<String> matches=new ArrayList<>();
            for(Models.StudyTask t:storage.tasks()) {
                if(chosen.equals(t.dueDate)) {
                    matches.add((t.completed?"✓ ":"• ")+t.type+" · "+label(t));
                }
            }
            String detail=matches.isEmpty()?"등록된 일정이 없습니다.":String.join("\n",matches);
            new AlertDialog.Builder(this)
                    .setTitle(chosen.toString())
                    .setMessage(detail)
                    .setPositiveButton("확인",null).show();
        });
        new AlertDialog.Builder(this)
                .setTitle("월간 일정 달력")
                .setView(calendar)
                .setPositiveButton("닫기",null).show();
    }

    private View studyCoach() {
        ScrollView scroll=page();
        LinearLayout c=column();
        scroll.addView(c);
        c.addView(section("오늘 공부 계획 · 오프라인"));
        c.addView(cardText("서버 연결 없이 일정·마감일을 활용해 공부 순서를 정합니다.",13));
        android.content.SharedPreferences prefs=getSharedPreferences("studyone_planner",MODE_PRIVATE);
        EditText budget=input("오늘 공부 가능 시간(분)",String.valueOf(prefs.getInt("daily_minutes",120)));
        budget.setInputType(InputType.TYPE_CLASS_NUMBER);
        c.addView(budget);
        LinearLayout output=card();
        Button generate=primary("오프라인 계획 만들기");
        generate.setOnClickListener(view->{
            int minutes;
            try{minutes=Integer.parseInt(budget.getText().toString().trim());}
            catch(Exception e){toast("공부 가능 시간을 숫자로 입력해 주세요.");return;}
            if(minutes<20||minutes>600){toast("20분~600분 범위로 입력해 주세요.");return;}
            prefs.edit().putInt("daily_minutes",minutes).apply();
            output.removeAllViews();
            List<Models.StudyTask> list=storage.tasks();
            list.removeIf(task->task.completed||"준비물".equals(task.type));
            list.sort(Comparator.comparingDouble(this::score).reversed());
            int remaining=minutes,index=0;
            for(Models.StudyTask task:list) {
                if(remaining<20||index>=12) break;
                int duration="시험".equals(task.type)?45:("수행평가".equals(task.type)?40:30);
                duration=Math.min(duration,remaining);
                output.addView(tv((++index)+". "+task.subject+" · "+task.title,15,TEXT,true));
                output.addView(tv("집중 "+duration+"분 · "+dDay(task.dueDate),12,MUTED,false));
                output.addView(spacer(10));
                remaining-=duration;
                if(remaining>=15){remaining-=10;output.addView(tv("휴식 10분",12,BLUE,false));output.addView(spacer(8));}
            }
            if(index==0)output.addView(tv("추천할 일정이 없습니다. 플래너에 숙제나 시험을 등록해 주세요.",14,MUTED,false));
            if(remaining>0&&index>0)output.addView(tv("남은 여유 시간 "+remaining+"분",13,MUTED,false));
        });
        c.addView(generate);
        c.addView(output);

        c.addView(section("StudyOne AI 학습 도우미"));
        c.addView(cardText("선택 사항 · OpenAI API 연결 (gpt-4.1-mini)\n"
                +"API 키가 있는 경우에만 실행합니다. 실제 호출에는 API 별도 요금이 발생할 수 있습니다."
                +" 앱이 키나 질문을 GitHub로 보내지 않습니다."
                +" OpenAI API로 전송할 질문·과제 내용은 매 요청마다 미리 확인합니다.",13));
        TextView keyStatus=tv(AiSecrets.configured(this)?"API 키: 암호화 저장됨":"API 키: 미설정",13,MUTED,false);
        c.addView(keyStatus);
        EditText apiKey=input("개인 OpenAI API 키 입력 (sk-...)", "");
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c.addView(apiKey);
        Button saveKey=secondary("API 키 저장 / 교체");
        saveKey.setOnClickListener(view->{
            try {
                AiSecrets.save(this,apiKey.getText().toString());
                apiKey.setText("");
                keyStatus.setText("API 키: 암호화 저장됨");
                toast("Android Keystore로 API 키를 암호화해 저장했습니다.");
            }catch(Exception e){
                toast("API 키 형식과 기기 보안 저장소를 확인하세요.");
            }
        });
        c.addView(saveKey);
        Button erase=secondary("API 키 삭제");
        erase.setOnClickListener(view->
            new AlertDialog.Builder(this).setTitle("저장된 API 키 삭제")
                .setMessage("기기에 저장된 OpenAI API 키를 삭제할까요?")
                .setPositiveButton("삭제",(dialog,which)->{
                    AiSecrets.clear(this);
                    keyStatus.setText("API 키: 미설정");
                    apiKey.setText("");
                    toast("API 키를 삭제했습니다.");
                }).setNegativeButton("취소",null).show());
        c.addView(erase);

        Spinner modes=new Spinner(this);
        String[] labels={"질문 설명","맞춤 공부 계획","복습 퀴즈"};
        modes.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
        c.addView(modes);
        EditText question=input("AI에게 물어볼 내용 (계획 선택 시 생략 가능)","");
        question.setSingleLine(false);
        question.setMinLines(2);
        question.setMaxLines(5);
        c.addView(question);
        CheckBox includeTasks=new CheckBox(this);
        includeTasks.setText("내 미완료 일정도 AI에 전달 (기본 해제)");
        includeTasks.setChecked(false);
        c.addView(includeTasks);

        LinearLayout response=card();
        response.addView(tv("AI 응답은 여기에 표시됩니다. 전송하기 전 질문과 공유할 일정을 확인하세요.",13,MUTED,false));
        Button ask=primary("AI에게 질문 보내기");
        ask.setOnClickListener(view->{
            if(!AiSecrets.configured(this)){toast("먼저 개인 OpenAI API 키를 설정하세요.");return;}
            int minutes;
            try{minutes=Integer.parseInt(budget.getText().toString().trim());}
            catch(Exception e){minutes=120;}
            final String prompt;
            try {
                prompt=AiClient.makeInput(labels[modes.getSelectedItemPosition()],
                        question.getText().toString(),minutes,storage.tasks(),includeTasks.isChecked());
            } catch(Exception e) {
                toast("질문과 전송 내용을 확인하세요.");
                return;
            }
            new AlertDialog.Builder(this)
                .setTitle("OpenAI API 전송 전 확인")
                .setMessage("아래 내용이 외부 OpenAI API로 전송됩니다. API 키는 이 화면에 표시하거나 백업하지 않습니다."
                        +"\n\n"+prompt
                        +"\n\nAI 답변은 부정확할 수 있고 API 요금이 발생할 수 있습니다.")
                .setNegativeButton("취소",null)
                .setPositiveButton("동의하고 전송",(d,w)->{
                    ask.setEnabled(false);
                    response.removeAllViews();
                    response.addView(tv("AI 답변을 기다리는 중…",14,MUTED,false));
                    final String mode=labels[modes.getSelectedItemPosition()];
                    io.execute(()->{
                        String result;
                        try {
                            String key=AiSecrets.read(this);
                            result=AiClient.ask(key,mode,prompt);
                        }catch(AiClient.AiError e) {
                            result="AI 요청 실패: "+e.display;
                        }catch(Exception e) {
                            result="AI 연결 실패: 네트워크, 키 저장 상태 또는 응답 오류를 확인해 주세요.";
                        }
                        final String shown=result;
                        runOnUiThread(()->{
                            if(isFinishing()||isDestroyed())return;
                            response.removeAllViews();
                            TextView text=tv(shown,14,TEXT,false);
                            text.setTextIsSelectable(true);
                            response.addView(text);
                            ask.setEnabled(true);
                        });
                    });
                }).show();
        });
        c.addView(ask);
        c.addView(response);
        c.addView(cardText("개인 API 키가 없어도 위의 오프라인 학습 계획은 이용할 수 있습니다. "
                +"AI 응답은 참고용이며, 질문·과제의 민감한 개인정보를 보내지 마세요.",12));
        return scroll;
    }

    @Override protected void onActivityResult(int request,int result,android.content.Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null)return;
        final android.net.Uri uri=data.getData();
        if(request==BACKUP_EXPORT) {
            try(java.io.OutputStream os=getContentResolver().openOutputStream(uri,"w")) {
                if(os==null)throw new IllegalStateException("파일을 열 수 없습니다.");
                os.write(BackupIo.exportData(storage).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
                toast("StudyOne 백업을 저장했습니다.");
            }catch(Exception e){showBackupError(e);}
        }else if(request==WRONG_EXPORT) {
            try(java.io.OutputStream os=getContentResolver().openOutputStream(uri,"w")) {
                if(os==null)throw new IllegalStateException("오답 백업 파일 접근 실패");
                os.write(wrongAnswers.exportJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
                toast("오답 JSON 백업을 저장했습니다.");
            }catch(Exception e){showBackupError(e);}
        }else if(request==WRONG_IMPORT) {
            try(java.io.InputStream in=getContentResolver().openInputStream(uri)) {
                if(in==null)throw new IllegalStateException("오답 백업 파일 접근 실패");
                java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
                byte[] chunk=new byte[8192]; int n;
                while((n=in.read(chunk))!=-1) {
                    if(buffer.size()+n>2_000_000)throw new IllegalArgumentException("최대 2MB");
                    buffer.write(chunk,0,n);
                }
                List<StudyWrongAnswers.Entry> incoming=StudyWrongAnswers.parseBackup(
                        buffer.toString("UTF-8"));
                new AlertDialog.Builder(this).setTitle("오답 백업 가져오기")
                    .setMessage("오답 "+incoming.size()+"개로 현재 이 기기의 오답 목록만 교체합니다. 기존 오답 원본을 앱 내부에 백업한 후 적용합니다. 계속할까요?")
                    .setPositiveButton("오답 교체",(d,w)->{
                        try { wrongAnswers.restore(incoming);showTab(3);toast("오답 백업 복원 완료"); }
                        catch(Exception e){showBackupError(e);}
                    }).setNegativeButton("취소",null).show();
            }catch(Exception e){showBackupError(e);}
        }else if(request==DETAILS_EXPORT) {
            try(java.io.OutputStream os=getContentResolver().openOutputStream(uri,"w")) {
                if(os==null)throw new IllegalStateException("학습 상세 백업 파일을 열 수 없습니다.");
                os.write(detailStorage.exportBackup().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
                toast("학습 상세 JSON 백업을 저장했습니다.");
            } catch(Exception error) { showBackupError(error); }
        }else if(request==DETAILS_IMPORT) {
            try(java.io.InputStream in=getContentResolver().openInputStream(uri)) {
                if(in==null)throw new IllegalStateException("학습 상세 파일을 열 수 없습니다.");
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                byte[] buffer=new byte[8192];
                int n;
                while((n=in.read(buffer))!=-1) {
                    if(bytes.size()+n>2_000_000)
                        throw new IllegalArgumentException("학습 상세 백업은 최대 2MB입니다.");
                    bytes.write(buffer,0,n);
                }
                List<StudyTaskDetail> parsed=StudyTaskDetailStorage.parseBackup(bytes.toString("UTF-8"));
                new AlertDialog.Builder(this)
                        .setTitle("학습 상세 정보 가져오기")
                        .setMessage("상세 정보 "+parsed.size()+"개를 검증했습니다. 현재 이 기기의 상세 정보만 교체합니다. 기존 내용을 앱 내부 백업으로 보존한 후 복원합니다. 일정·진도·계정 연결은 변경하지 않습니다. 계속할까요?")
                        .setPositiveButton("상세 정보 교체",(dialog,which)->{
                            try {
                                detailStorage.restoreBackup(parsed);
                                showTab(3);
                                toast("상세 정보 백업을 복원했습니다.");
                            } catch(Exception error) { showBackupError(error); }
                        }).setNegativeButton("취소",null).show();
            } catch(Exception error) { showBackupError(error); }
        }else if(request==PROGRESS_EXPORT) {
            try(java.io.OutputStream os=getContentResolver().openOutputStream(uri,"w")) {
                if(os==null)throw new IllegalStateException("진도 백업 파일을 열 수 없습니다.");
                os.write(progressStorage.exportBackup().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
                toast("진도 JSON 백업을 저장했습니다.");
            } catch(Exception error) { showBackupError(error); }
        }else if(request==PROGRESS_IMPORT) {
            try(java.io.InputStream in=getContentResolver().openInputStream(uri)) {
                if(in==null)throw new IllegalStateException("진도 백업 파일을 열 수 없습니다.");
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                byte[] buffer=new byte[8192];
                int n;
                while((n=in.read(buffer))!=-1) {
                    if(bytes.size()+n>2_000_000)throw new IllegalArgumentException("진도 백업은 최대 2MB입니다.");
                    bytes.write(buffer,0,n);
                }
                List<StudyProgressItem> parsed=StudyProgressStorage.parseBackup(
                        bytes.toString("UTF-8"));
                new AlertDialog.Builder(this)
                        .setTitle("진도 백업 가져오기")
                        .setMessage("진도 "+parsed.size()+"개를 확인했습니다.\n"
                                +"현재 이 기기의 진도 목록만 교체합니다. "
                                +"학교·과제·수행평가 일정은 그대로 유지합니다. "
                                +"현재 진도도 먼저 백업하는 것이 좋습니다. 계속할까요?")
                        .setPositiveButton("진도 교체",(dialog,which)->{
                            try {
                                progressStorage.restoreBackup(parsed);
                                showTab(3);
                                toast("진도 JSON 복원이 완료되었습니다.");
                            } catch(Exception error) { showBackupError(error); }
                        }).setNegativeButton("취소",null).show();
            } catch(Exception error) { showBackupError(error); }
        }else if(request==BACKUP_IMPORT) {
            try(java.io.InputStream in=getContentResolver().openInputStream(uri)) {
                if(in==null)throw new IllegalStateException("파일을 열 수 없습니다.");
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
                byte[] buffer=new byte[8192];
                int n;
                while((n=in.read(buffer))!=-1) {
                    if(out.size()+n>2_000_000)throw new IllegalArgumentException("2MB 이상의 백업은 가져올 수 없습니다.");
                    out.write(buffer,0,n);
                }
                BackupIo.ParsedBackup parsed=BackupIo.parse(out.toString("UTF-8"));
                new AlertDialog.Builder(this)
                        .setTitle("백업 가져오기")
                        .setMessage("일정 "+parsed.tasks.size()+"건을 가져옵니다. 현재 저장된 일정은 교체됩니다. NEIS 인증키는 변경되지 않습니다. 계속할까요?")
                        .setPositiveButton("가져오기",(dialog,which)->{
                            BackupIo.restore(storage,parsed);
                            showTab(3);
                            toast("백업 복원을 완료했습니다.");
                        })
                        .setNegativeButton("취소",null).show();
            }catch(Exception e){showBackupError(e);}
        }
    }

    private void showBackupError(Exception e) {
        new AlertDialog.Builder(this)
                .setTitle("백업 처리 실패")
                .setMessage(e.getMessage()==null?"파일 형식을 확인하세요.":e.getMessage())
                .setPositiveButton("확인",null).show();
    }

    private void progressDialog(StudyProgressItem existing) {
        LinearLayout fields=column();
        fields.setPadding(dp(18),dp(6),dp(18),dp(2));
        EditText subject=input("과목 (예: 과학)",existing==null?"":existing.subject);
        EditText material=input("교재 (예: 오투)",existing==null?"":existing.material);
        EditText unit=input("진도 이름 (예: 7-2 또는 시험범위 전체 오답 복습)",
                existing==null?"":existing.unit);
        Spinner status=new Spinner(this);
        status.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                StudyProgressItem.STATUSES));
        String current=existing==null?StudyProgressItem.TODO:existing.status;
        for(int i=0;i<StudyProgressItem.STATUSES.length;i++) {
            if(StudyProgressItem.STATUSES[i].equals(current))status.setSelection(i);
        }
        CheckBox scope=new CheckBox(this);
        scope.setText("이번 시험범위에 포함");
        scope.setChecked(existing==null||existing.inExamScope);
        fields.addView(subject);
        fields.addView(material);
        fields.addView(unit);
        fields.addView(tv("진도 상태",13,MUTED,false));
        fields.addView(status);
        fields.addView(scope);
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle(existing==null?"교재 진도 추가":"교재 진도 수정")
                .setView(fields)
                .setPositiveButton("저장",null)
                .setNegativeButton("취소",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v->{
                    try {
                        String s=subject.getText().toString();
                        String m=material.getText().toString();
                        String u=unit.getText().toString();
                        String statusText=StudyProgressItem.STATUSES[status.getSelectedItemPosition()];
                        if(existing==null)progressStorage.add(s,m,u,statusText,scope.isChecked());
                        else progressStorage.update(existing.id,existing.revision,s,m,u,statusText,scope.isChecked());
                        dialog.dismiss();
                        showTab(3);
                    } catch (RuntimeException error) {
                        toast(error.getMessage()==null?"입력을 확인하세요.":error.getMessage());
                    }
                }));
        dialog.show();
    }

    private View progressCard(StudyProgressItem item) {
        LinearLayout box=card();
        LinearLayout titleRow=new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox completed=new CheckBox(this);
        completed.setText("완료");
        completed.setContentDescription(item.subject+" "+item.unit+" 완료 체크");
        completed.setChecked(StudyProgressItem.DONE.equals(item.status));
        completed.setOnCheckedChangeListener((button,isChecked)->{
            String next=isChecked?StudyProgressItem.DONE:StudyProgressItem.TODO;
            if(next.equals(item.status))return;
            try {
                progressStorage.update(item.id,item.revision,item.subject,item.material,
                        item.unit,next,item.inExamScope);
                showTab(3);
            } catch(RuntimeException error) {
                toast(error.getMessage()==null?"진도 체크 실패":error.getMessage());
                showTab(3);
            }
        });
        LinearLayout labels=column();
        labels.addView(tv("["+item.subject+"] "+item.material+" · "+item.unit,14,TEXT,true));
        labels.addView(tv(item.status+" · "+(item.inExamScope?"시험범위 포함":"시험범위 제외")
                +" · 수정 "+item.revision+"회차",12,MUTED,false));
        titleRow.addView(completed);
        titleRow.addView(labels,new LinearLayout.LayoutParams(0,-2,1f));
        box.addView(titleRow);
        LinearLayout buttons=new LinearLayout(this);
        Button chooseStatus=secondary("상태 선택");
        chooseStatus.setOnClickListener(v->new AlertDialog.Builder(this)
                .setTitle("["+item.subject+"] "+item.unit+" 상태")
                .setItems(StudyProgressItem.STATUSES,(dialog,which)->{
                    String next=StudyProgressItem.STATUSES[which];
                    if(next.equals(item.status))return;
                    try {
                        progressStorage.update(item.id,item.revision,item.subject,item.material,
                                item.unit,next,item.inExamScope);
                        showTab(3);
                    } catch(RuntimeException error) {
                        toast(error.getMessage()==null?"상태 변경 실패":error.getMessage());
                        showTab(3);
                    }
                }).setNegativeButton("취소",null).show());
        buttons.addView(chooseStatus,new LinearLayout.LayoutParams(0,dp(46),1f));
        Button edit=secondary("수정");
        edit.setOnClickListener(v->progressDialog(item));
        buttons.addView(edit,new LinearLayout.LayoutParams(0,dp(46),1f));
        Button delete=secondary("삭제");
        delete.setOnClickListener(v->new AlertDialog.Builder(this)
                .setTitle("진도 삭제")
                .setMessage(item.subject+" · "+item.unit+" 항목을 삭제하시겠습니까?")
                .setPositiveButton("삭제",(d,w)->{
                    try {
                        progressStorage.delete(item.id,item.revision);
                        showTab(3);
                    } catch(RuntimeException error) {
                        toast(error.getMessage()==null?"삭제 실패":error.getMessage());
                    }
                }).setNegativeButton("취소",null).show());
        buttons.addView(delete,new LinearLayout.LayoutParams(0,dp(46),1f));
        box.addView(buttons);
        return box;
    }

    private void taskDialog(Models.StudyTask existing) {
        LinearLayout box=column();
        box.setPadding(dp(18),dp(4),dp(18),0);
        Spinner type=new Spinner(this);
        String[] types=StudyCategories.taskTypes(existing==null?null:existing.type);
        type.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,types));
        if(existing==null)type.setSelection(3); // 숙제 항목 for newly created items.
        else for(int i=0;i<types.length;i++)if(types[i].equals(existing.type))type.setSelection(i);
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

    private void detailDialog(Models.StudyTask task) {
        final StudyTaskDetail old;
        try { old=detailStorage.get(task.id); }
        catch(Exception e) { toast("상세 정보를 안전하게 읽지 못했습니다. 기존 데이터는 유지합니다."); return; }
        LinearLayout fields=column();
        fields.setPadding(dp(18),dp(6),dp(18),dp(2));
        EditText minutes=input("예상 공부 시간 (분, 미입력 가능)",
                old==null||old.estimatedMinutes==null?"":String.valueOf(old.estimatedMinutes));
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText steps=input("공부 단계 · 한 줄에 하나씩",
                old==null?"":android.text.TextUtils.join("\n",old.steps));
        steps.setSingleLine(false);
        steps.setMinLines(3);
        steps.setMaxLines(8);
        steps.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText criteria=input("완료 기준",old==null?"":old.completionCriteria);
        criteria.setSingleLine(false);
        criteria.setMinLines(2);
        criteria.setMaxLines(4);
        fields.addView(tv("["+task.subject+"] "+task.title,15,TEXT,true));
        fields.addView(cardText("시간은 1~600분, 공부 단계는 최대 20개(각 200자), 완료 기준은 500자까지. 모두 비우면 상세 정보만 지웁니다.",12));
        fields.addView(minutes);
        fields.addView(steps);
        fields.addView(criteria);
        android.widget.ScrollView scrolling=new android.widget.ScrollView(this);
        scrolling.addView(fields);
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("학습 상세 · 단계·시간·완료 기준")
                .setView(scrolling)
                .setNegativeButton("취소",null)
                .setPositiveButton("저장",null)
                .create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try {
                String duration=minutes.getText().toString().trim();
                Integer value=duration.isEmpty()?null:Integer.parseInt(duration);
                List<String> ordered=new ArrayList<>();
                for(String line:steps.getText().toString().split("\\r?\\n")) {
                    if(!line.trim().isEmpty())ordered.add(line.trim());
                }
                StudyTaskDetail detail=new StudyTaskDetail(task.id,value,ordered,
                        criteria.getText().toString());
                detailStorage.save(detail);
                dialog.dismiss();
                showTab(3);
                toast("학습 상세 정보를 저장했습니다.");
            } catch(Exception e) {
                toast(e.getMessage()==null?"상세 정보가 올바르지 않습니다.":e.getMessage());
            }
        }));
        dialog.show();
    }


    private void appendWrongAnswerNotebook(LinearLayout parent) {
        parent.addView(section("오답 관리 · 복습일"));
        parent.addView(cardText("틀린 문제·정답 근거·복습 날짜를 기기에만 저장합니다. 다른 기기와 자동 동기화되지 않습니다.",12));
        Button add = secondary("+ 오답 기록 추가");
        add.setOnClickListener(v->wrongAnswerDialog(null));
        parent.addView(add);
        Button backup=secondary("오답 JSON 백업 내보내기");
        backup.setOnClickListener(v->{
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(android.content.Intent.EXTRA_TITLE,"StudyOne-wrong-answers.json");
            startActivityForResult(intent,WRONG_EXPORT);
        });
        parent.addView(backup);
        Button restore=secondary("오답 JSON 가져오기");
        restore.setOnClickListener(v->{
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            startActivityForResult(intent,WRONG_IMPORT);
        });
        parent.addView(restore);
        try {
            List<StudyWrongAnswers.Entry> all=wrongAnswers.list();
            int waiting=0;
            String today=LocalDate.now().toString();
            for(StudyWrongAnswers.Entry e:all)
                if(!e.mastered && e.nextReview.compareTo(today)<=0)waiting++;
            parent.addView(cardText("오답 "+all.size()+"개 · 오늘까지 복습 대상 "+waiting+"개",13));
            for(StudyWrongAnswers.Entry item:all) {
                LinearLayout card=card();
                card.addView(tv("["+item.subject+"] "+item.material+" · "+item.topic,14,TEXT,true));
                card.addView(tv("원인·정답 근거: "+item.correction,12,MUTED,false));
                card.addView(tv("재풀이 "+item.retries+"회 · 복습일 "+item.nextReview+
                    (item.mastered?" · 숙달":" · 복습 필요"),12,MUTED,false));
                Button review=secondary(item.mastered?"다시 복습 대상으로 전환":"재풀이 성공 (+1회, 내일 복습)");
                review.setOnClickListener(v->{
                    try {
                        boolean mastered=item.mastered;
                        StudyWrongAnswers.Entry updated=new StudyWrongAnswers.Entry(
                            item.id,item.subject,item.material,item.topic,item.correction,
                            mastered?LocalDate.now().toString():LocalDate.now().plusDays(1).toString(),
                            mastered?item.retries:Math.min(50,item.retries+1),false);
                        wrongAnswers.save(updated);
                        showTab(3);
                    }catch(Exception err){toast(err.getMessage()==null?"오답 수정 실패":err.getMessage());}
                });
                card.addView(review);
                Button mastered=secondary(item.mastered?"완료 취소":"완전히 숙달 처리");
                mastered.setOnClickListener(v->{
                    try {
                        wrongAnswers.save(new StudyWrongAnswers.Entry(item.id,item.subject,item.material,
                            item.topic,item.correction,item.nextReview,item.retries,!item.mastered));
                        showTab(3);
                    }catch(Exception e){toast("오답 저장 실패");}
                });
                card.addView(mastered);
                Button edit=secondary("오답 수정");
                edit.setOnClickListener(v->wrongAnswerDialog(item));
                card.addView(edit);
                parent.addView(card);
            }
        } catch(Exception e) {
            parent.addView(cardText("오답 원본 검증 오류: 기존 데이터를 보존하고 수정을 차단합니다.",13));
        }
    }

    private void wrongAnswerDialog(StudyWrongAnswers.Entry original) {
        LinearLayout fields=column();
        fields.setPadding(dp(18),dp(6),dp(18),dp(2));
        EditText subject=input("과목",original==null?"":original.subject);
        EditText material=input("교재·출처",original==null?"":original.material);
        EditText topic=input("틀린 문제·개념",original==null?"":original.topic);
        EditText correction=input("틀린 이유·정답 근거",original==null?"":original.correction);
        EditText day=input("복습 날짜 (YYYY-MM-DD)",
                original==null?LocalDate.now().plusDays(1).toString():original.nextReview);
        fields.addView(subject);fields.addView(material);fields.addView(topic);
        fields.addView(correction);fields.addView(day);
        ScrollView scroll=new ScrollView(this);
        scroll.addView(fields);
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle(original==null?"오답 기록 추가":"오답 기록 수정")
            .setView(scroll).setNegativeButton("취소",null)
            .setPositiveButton("저장",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            try {
                StudyWrongAnswers.Entry entry=new StudyWrongAnswers.Entry(
                    original==null?java.util.UUID.randomUUID().toString():original.id,
                    subject.getText().toString().trim(),material.getText().toString().trim(),
                    topic.getText().toString().trim(),correction.getText().toString().trim(),
                    day.getText().toString().trim(),original==null?0:original.retries,
                    original!=null&&original.mastered);
                wrongAnswers.save(entry);
                dialog.dismiss();showTab(3);
            } catch(Exception e){toast(e.getMessage()==null?"오답 입력 오류":e.getMessage());}
        }));
        dialog.show();
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
        try {
            StudyTaskDetail detail=detailStorage.get(t.id);
            if(detail!=null&&!detail.isEmpty()) {
                texts.addView(spacer(6));
                texts.addView(tv(detail.displayText(),12,MUTED,false));
            }
        } catch(IllegalStateException e) {
            texts.addView(tv("학습 상세 정보 확인 필요 · 원본 보존",12,MUTED,false));
        }
        row.addView(check);
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1f));
        box.addView(row);
        LinearLayout controls=new LinearLayout(this);
        Button guidance=secondary("상세");
        controls.addView(guidance,new LinearLayout.LayoutParams(0,dp(46),1f));
        guidance.setOnClickListener(v->detailDialog(t));
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
