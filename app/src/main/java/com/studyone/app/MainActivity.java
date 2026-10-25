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
    private int plannerPage;
    private static final String[] PLANNER_PAGES = {"오늘", "내일", "전체", "진도", "오답", "쓰기", "관리"};
    private static final String[] NAV_NAMES = {"홈", "시간표", "급식", "플래너", "추천", "설정"};
    private static final int[] NAV_ICONS = {R.drawable.ic_nav_home, R.drawable.ic_nav_timetable,
            R.drawable.ic_nav_meals, R.drawable.ic_nav_planner,
            R.drawable.ic_nav_coach, R.drawable.ic_nav_settings};
    private final List<LinearLayout> navCells = new ArrayList<>();
    private final List<ImageView> navIcons = new ArrayList<>();
    private final List<TextView> navLabels = new ArrayList<>();
    private static final int BACKUP_EXPORT = 4801;
    private static final int BACKUP_IMPORT = 4802;
    private static final int PROGRESS_EXPORT = 4803;
    private static final int PROGRESS_IMPORT = 4804;
    private static final int DETAILS_EXPORT = 4805;
    private static final int DETAILS_IMPORT = 4806;
    private static final int WRONG_EXPORT = 4807;
    private static final int WRONG_IMPORT = 4808;
    private static final int BUNDLE_EXPORT = 4809;
    private static final int BUNDLE_IMPORT = 4810;
    private static final int BUNDLE_RECOVERY_EXPORT = 4811;

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
        plannerPage=b == null ? 0 : Math.max(0,Math.min(PLANNER_PAGES.length-1,
                b.getInt("studyone_planner_page",0)));
        Reminders.schedule(this);
        showTab(b == null ? 0 : Math.max(0, Math.min(NAV_NAMES.length - 1,
                b.getInt("studyone_active_tab", 0))));
        AppUpdates.check(this, false);
        if(getIntent()!=null && getIntent().getBooleanExtra("studyone_ai_self_test",false)){
            if(!AiClient.selfTest())throw new IllegalStateException("AI fixture parser test failed");
            android.util.Log.i("StudyOneAI","AI_OFFLINE_FIXTURE_TEST_PASSED");
        }
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
                && getIntent()!=null
                && getIntent().getBooleanExtra("studyone_outbox_self_test",false)) {
            if(!StudySyncOutbox.selfTest(getApplicationContext()))
                throw new IllegalStateException("Isolated mobile outbox fixture failed");
            android.util.Log.i("StudyOneOutbox","ANDROID_OUTBOX_ISOLATED_E2E_PASSED");
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
        if ((getApplicationInfo().flags &
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
                getIntent()!=null && getIntent().getBooleanExtra("studyone_nav_self_test",false)) {
            if (!navigationSelfTest()) throw new IllegalStateException("Nav tab state did not update");
            android.util.Log.i("StudyOneNav","NAVIGATION_STATE_SYNC_PASSED");
        }
        if ((getApplicationInfo().flags &
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
                getIntent()!=null &&
                getIntent().getBooleanExtra("studyone_planner_hub_self_test",false)) {
            if(!plannerHubSelfTest())throw new IllegalStateException("Planner hub regression failed");
            android.util.Log.i("StudyOnePlanner","PLANNER_HUB_SEVEN_PAGES_PASSED");
        }
        if (isDeveloperBuild() && getIntent()!=null &&
                getIntent().getBooleanExtra("studyone_bundle_self_test",false)) {
            if(!StudyPortableBundle.selfTest())
                throw new IllegalStateException("Portable backup bundle validation failed");
            android.util.Log.i("StudyOneBundle","TRANSFER_BUNDLE_VALIDATION_PASSED");
        }
        if (isDeveloperBuild() && getIntent()!=null
                && getIntent().getBooleanExtra("studyone_progress_recovery_self_test",false)) {
            if (!StudyProgressStorage.selfTestRecovery(getApplicationContext()))
                throw new IllegalStateException("Isolated progress recovery E2E failed");
            android.util.Log.i("StudyOneProgress","PROGRESS_ISOLATED_RECOVERY_E2E_PASSED");
        }
        if(getIntent()!=null && getIntent().getBooleanExtra("studyone_progress_self_test",false)){
            if(!StudyProgressStorage.selfTestBackup()) {
                throw new IllegalStateException("Progress backup fixture round-trip failed");
            }
            android.util.Log.i("StudyOneProgress","PROGRESS_BACKUP_ROUNDTRIP_PASSED");
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("studyone_active_tab", currentTab);
        out.putInt("studyone_planner_page",plannerPage);
        super.onSaveInstanceState(out);
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
        bar.setPadding(dp(18),dp(12),dp(18),dp(12));
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(2));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_book_check);
        bar.addView(logo,new LinearLayout.LayoutParams(dp(38),dp(38)));

        LinearLayout names = column();
        names.setPadding(dp(10),0,0,0);
        names.addView(tv("StudyOne",20,TEXT,true));
        title = tv("오늘",12,MUTED,false);
        names.addView(title);
        bar.addView(names,new LinearLayout.LayoutParams(0,-2,1f));
        bar.addView(pill("Canary 3.1",BLUE,Color.WHITE));
        return bar;
    }

    private View bottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(5),dp(7),dp(5),dp(8));
        nav.setBackgroundColor(Color.WHITE);
        nav.setElevation(dp(7));
        for (int i=0;i<NAV_NAMES.length;i++) {
            LinearLayout item=navigationItem(i,false);
            nav.addView(item,new LinearLayout.LayoutParams(0,dp(69),1f));
        }
        return nav;
    }

    private View tabletNavigation() {
        LinearLayout rail=column();
        rail.setBackgroundColor(Color.WHITE);
        rail.setElevation(dp(5));
        rail.setPadding(dp(9),dp(48),dp(9),dp(12));
        for (int i=0;i<NAV_NAMES.length;i++) {
            LinearLayout item=navigationItem(i,true);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(124),dp(76));
            params.setMargins(0,dp(3),0,dp(3));
            rail.addView(item,params);
        }
        return rail;
    }

    /** Keeps visible icons and labels tied to one source of truth: currentTab. */
    private LinearLayout navigationItem(int index, boolean rail) {
        LinearLayout cell=column();
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(2),dp(5),dp(2),dp(4));
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setOnClickListener(v->showTab(index));

        ImageView icon=new ImageView(this);
        icon.setImageResource(NAV_ICONS[index]);
        icon.setContentDescription(null);
        LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(dp(24),dp(24));
        iconParams.gravity=Gravity.CENTER_HORIZONTAL;
        cell.addView(icon,iconParams);
        TextView label=tv(NAV_NAMES[index],rail?13:11,MUTED,false);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setPadding(0,dp(5),0,0);
        cell.addView(label,new LinearLayout.LayoutParams(-1,-2));

        navCells.add(cell);
        navIcons.add(icon);
        navLabels.add(label);
        return cell;
    }

    private void updateNavigationSelection() {
        for (int i=0;i<navCells.size();i++) {
            boolean active=i==currentTab;
            LinearLayout cell=navCells.get(i);
            cell.setSelected(active);
            cell.setActivated(active);
            cell.setContentDescription(NAV_NAMES[i]+(active?" · 현재 선택됨":"")+" 탭");
            navIcons.get(i).setColorFilter(active?BLUE_DARK:MUTED,
                    android.graphics.PorterDuff.Mode.SRC_IN);
            TextView label=navLabels.get(i);
            label.setTextColor(active?BLUE_DARK:MUTED);
            label.setTypeface(Typeface.DEFAULT,active?Typeface.BOLD:Typeface.NORMAL);
            GradientDrawable surface=new GradientDrawable();
            surface.setColor(active?Color.rgb(234,239,255):Color.TRANSPARENT);
            surface.setCornerRadius(dp(16));
            if (active) surface.setStroke(dp(1),Color.rgb(204,216,255));
            cell.setBackground(surface);
        }
    }

    private void showTab(int tab) {
        if(tab<0 || tab>=NAV_NAMES.length) return;
        currentTab=tab;
        updateNavigationSelection();
        content.removeAllViews();
        if(tab==0){title.setText("오늘");content.addView(home());}
        else if(tab==1){title.setText("주간 시간표");content.addView(timetable());}
        else if(tab==2){title.setText("이번 주 급식");content.addView(meals());}
        else if(tab==3){title.setText("학습 플래너");content.addView(planner());}
        else if(tab==4){title.setText("공부 추천");content.addView(studyCoach());}
        else{title.setText("설정");content.addView(settings());}
        StudyWidget.refresh(this);
    }

    /** Emulator regression: click real navigation surfaces and assert state, labels and accessibility. */
    private boolean navigationSelfTest() {
        if (navCells.size()!=NAV_NAMES.length || navLabels.size()!=NAV_NAMES.length ||
            navIcons.size()!=NAV_NAMES.length) return false;
        int original=currentTab;
        try {
            for (int i=0;i<NAV_NAMES.length;i++) {
                if (!navCells.get(i).performClick()) return false;
                if (currentTab!=i || content.getChildCount()!=1) return false;
                for (int j=0;j<NAV_NAMES.length;j++) {
                    boolean expected=(j==i);
                    if (navCells.get(j).isSelected()!=expected ||
                        navCells.get(j).isActivated()!=expected ||
                        navLabels.get(j).getCurrentTextColor()!=(expected?BLUE_DARK:MUTED) ||
                        !navCells.get(j).getContentDescription().toString().contains(
                            expected?"현재 선택됨":"탭")) return false;
                }
            }
            navCells.get(original).performClick();
            return currentTab==original && navCells.get(original).isSelected();
        } catch(Exception e) {
            return false;
        }
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
        LinearLayout welcome=card();
        welcome.setPadding(dp(18),dp(19),dp(18),dp(19));
        welcome.addView(tv("오늘의 학습",13,BLUE_DARK,true));
        welcome.addView(spacer(6));
        welcome.addView(tv(today.format(KO),24,TEXT,true));
        welcome.addView(spacer(5));
        welcome.addView(tv("오늘 필요한 공부와 학교 일정을 한눈에",13,MUTED,false));
        c.addView(welcome);

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

    /** StudyOne planner hub: task navigation is presentation only; all records retain their original IDs. */
    private View planner() {
        ScrollView s=page(); LinearLayout c=column(); s.addView(c);
        LocalDate today=LocalDate.now();
        List<Models.StudyTask> tasks=storage.tasks();

        LinearLayout headline=card();
        headline.setPadding(dp(18),dp(17),dp(18),dp(15));
        headline.addView(tv("나의 학습 공간",13,BLUE_DARK,true));
        headline.addView(spacer(4));
        headline.addView(tv(PLANNER_PAGES[plannerPage]+" 관리",23,TEXT,true));
        int finished=0;
        for(Models.StudyTask t:tasks) if(t.completed) finished++;
        List<Models.StudyTask> tomorrowHomeworks=StudyPlannerSections.tomorrowHomework(tasks,today);
        headline.addView(tv("전체 "+tasks.size()+"개 · 완료 "+finished+"개 · 내일 숙제 "
                +tomorrowHomeworks.size()+"개",12,MUTED,false));
        c.addView(headline);
        c.addView(plannerPageNavigation());
        c.addView(tv("메뉴를 좌우로 밀어 확인하세요",11,MUTED,false));
        c.addView(spacer(7));

        if(plannerPage==0) {
            Button add=primary("+ 학습 일정 추가");
            add.setOnClickListener(v->taskDialog(null)); c.addView(add);
            // Today's actual tasks + unfinished past tasks, unchanged from original.
            appendTodayChecklist(c,tasks,today);
            c.addView(section("내일 숙제 · 미리 하기"));
            c.addView(cardText("마감일을 바꾸지 않고 오늘부터 미리 완료 체크할 수 있습니다. "
                    +"내일 화면에도 같은 일정이 표시되며 하나의 완료 상태를 공유합니다.",12));
            if(tomorrowHomeworks.isEmpty()) {
                c.addView(cardText("내일 마감인 숙제가 없습니다.",13));
            } else {
                for(Models.StudyTask task:tomorrowHomeworks)c.addView(taskCard(task));
            }
            Button tomorrow=secondary("내일 제출할 일 전체 보기");
            tomorrow.setOnClickListener(v->openPlannerPage(1)); c.addView(tomorrow);
            c.addView(section("추천 순서"));
            c.addView(prioritySummary());
        } else if(plannerPage==1) {
            c.addView(section("내일 마감 일정 · "+today.plusDays(1)));
            Button add=primary("+ 학습 일정 추가");
            add.setOnClickListener(v->taskDialog(null));c.addView(add);
            List<Models.StudyTask> tomorrowTasks=StudyPlannerSections.tomorrow(tasks,today);
            if(tomorrowTasks.isEmpty()) {
                c.addView(cardText("내일 마감인 일정이 없습니다. 추가할 때 마감일을 내일로 설정하세요.",13));
            } else {
                c.addView(cardText("내일 마감 "+tomorrowTasks.size()+"개 · 숙제 "
                        +tomorrowHomeworks.size()+"개. 체크 결과는 오늘·전체·달력에도 바로 반영됩니다.",13));
                boolean hasHomework=false,hasOther=false;
                for(Models.StudyTask task:tomorrowTasks) {
                    if(StudyPlannerSections.isHomework(task)) {
                        if(!hasHomework){c.addView(section("제출 숙제"));hasHomework=true;}
                        c.addView(taskCard(task));
                    }
                }
                for(Models.StudyTask task:tomorrowTasks) {
                    if(!StudyPlannerSections.isHomework(task)) {
                        if(!hasOther){c.addView(section("수행·시험·기타 일정"));hasOther=true;}
                        c.addView(taskCard(task));
                    }
                }
            }
        } else if(plannerPage==2) {
            Button add=primary("+ 학습 일정 추가");
            add.setOnClickListener(v->taskDialog(null));c.addView(add);
            Button calendar=secondary("월간 달력에서 일정 보기");
            calendar.setOnClickListener(v->calendarDialog()); c.addView(calendar);
            c.addView(section("전체 일정 · 날짜순"));
            c.addView(cardText("과거·오늘·미래의 일정을 모두 표시합니다. "
                    +"오늘·내일 화면과 동일한 원본에 연결되므로 중복 저장되지 않습니다.",12));
            if(tasks.isEmpty()) {
                c.addView(cardText("등록된 일정이 없습니다.",14));
            } else {
                String lastDate="";
                for(Models.StudyTask task:StudyPlannerSections.allSorted(tasks)) {
                    String date=task.dueDate.toString();
                    if(!date.equals(lastDate)) {
                        c.addView(section(task.dueDate+" · "+dDay(task.dueDate)));
                        lastDate=date;
                    }
                    c.addView(taskCard(task));
                }
            }
        } else if(plannerPage==3) {
            appendProgressHub(c);
        } else if(plannerPage==4) {
            appendWrongAnswerNotebook(c);
        } else if(plannerPage==5) {
            appendWritingPractice(c);
        } else {
            appendPlannerManagement(c);
        }
        return s;
    }

    /** Debug-only, no test writes: visit all sections and check tomorrow preview shares task IDs. */
    private boolean plannerHubSelfTest() {
        int previousTab=currentTab,previousPage=plannerPage;
        try {
            LocalDate date=LocalDate.of(2026,10,23);
            Models.StudyTask next=new Models.StudyTask(172000001L,"숙제 항목","수학",
                    "종이 숙제",date.plusDays(1),2,false);
            Models.StudyTask distant=new Models.StudyTask(172000002L,"기타 항목","사회",
                    "나중 일정",date.plusDays(5),1,false);
            List<Models.StudyTask> synthetic=new ArrayList<>();
            synthetic.add(next);synthetic.add(distant);
            List<Models.StudyTask> tomorrow=StudyPlannerSections.tomorrow(synthetic,date);
            List<Models.StudyTask> homework=StudyPlannerSections.tomorrowHomework(synthetic,date);
            if(tomorrow.size()!=1 || homework.size()!=1 ||
                tomorrow.get(0).id!=next.id || homework.get(0).id!=next.id ||
                synthetic.size()!=2 ||
                StudyPlannerSections.allSorted(synthetic).size()!=2) return false;
            for(int i=0;i<PLANNER_PAGES.length;i++) {
                openPlannerPage(i);
                if(currentTab!=3 || plannerPage!=i || content.getChildCount()!=1) return false;
            }
            return true;
        } catch(Exception error) {
            return false;
        } finally {
            plannerPage=previousPage;
            showTab(previousTab);
        }
    }

    private View plannerPageNavigation() {
        android.widget.HorizontalScrollView scroll=new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        LinearLayout tabs=new LinearLayout(this);
        tabs.setPadding(dp(1),dp(1),dp(1),dp(6));
        scroll.addView(tabs);
        TextView active=null;
        for(int index=0;index<PLANNER_PAGES.length;index++) {
            final int position=index;
            boolean selected=index==plannerPage;
            TextView chip=tv(PLANNER_PAGES[index],13,selected?Color.WHITE:BLUE_DARK,selected);
            chip.setGravity(Gravity.CENTER);
            chip.setContentDescription(PLANNER_PAGES[index]+(selected?" · 현재 선택됨":"")+" 학습 탭");
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setSelected(selected);
            chip.setMinWidth(dp(65));
            chip.setPadding(dp(14),dp(11),dp(14),dp(11));
            GradientDrawable bg=new GradientDrawable();
            bg.setColor(selected?BLUE_DARK:Color.WHITE);
            bg.setCornerRadius(dp(14));
            if(!selected)bg.setStroke(dp(1),Color.rgb(214,222,237));
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(43));
            lp.setMargins(dp(3),0,dp(3),0);
            tabs.addView(chip,lp);
            chip.setOnClickListener(v->openPlannerPage(position));
            if(selected)active=chip;
        }
        final TextView visible=active;
        if(visible!=null)scroll.post(()->scroll.scrollTo(Math.max(0,visible.getLeft()-dp(12)),0));
        return scroll;
    }

    private void openPlannerPage(int page) {
        if(page<0||page>=PLANNER_PAGES.length)return;
        plannerPage=page;
        showTab(3);
    }

    private void appendProgressHub(LinearLayout c) {
        c.addView(section("교재 · 시험범위 · 진도"));
        c.addView(cardText("교재 진도는 기존 일별 숙제와 별도로 관리합니다. 기존 전체 일정 JSON 백업에는 진도가 포함되지 않으므로 아래의 별도 진도 JSON 백업을 사용하세요.",12));
        Button addProgress=secondary("+ 시험범위 진도 추가");
        addProgress.setOnClickListener(v->progressDialog(null));
        c.addView(addProgress);
        Button onlinePractice=secondary("+ 6과목 온라인 내신 문제 빠른 추가");
        onlinePractice.setOnClickListener(v->showOnlinePracticeDialog());
        c.addView(onlinePractice);
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
            java.util.List<StudyExamFocus.Subject> focus=StudyExamFocus.summarize(progress);
            c.addView(section("과목별 시험 준비 · 다음 학습 추천"));
            if(focus.isEmpty()) {
                c.addView(cardText("시험범위에 포함된 진도가 없습니다.",13));
            } else {
                for(StudyExamFocus.Subject subject:focus){
                    c.addView(cardText(subject.summary()+"\n"+subject.nextText(),13));
                }
                c.addView(cardText("온라인 내신 문제는 같은 과목의 다른 시험범위 학습이 "
                        +"남아 있으면 우선 추천하지 않습니다. 진도 기록이 실제 시험대비 자료 "
                        +"완료를 대신 확인해 주는 것은 아닙니다.",12));
            }
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
        Button manage=secondary("진도 백업 및 관리");
        manage.setOnClickListener(v->openPlannerPage(6));
        c.addView(manage);
    }

    private void appendPlannerManagement(LinearLayout c) {
        c.addView(section("분류·복사·백업 관리"));
        c.addView(section("기기 이전 · 전체 안전 백업"));
        c.addView(cardText("일정·진도·학습 상세·오답을 하나의 JSON으로 묶어 백업합니다. "
                +"기존 앱과 이 Canary는 서로 다른 설치 앱이므로 자동으로 기록을 읽어오지 못합니다. "
                +"기존 앱에서 별도 백업을 보존한 후 가져오세요. NEIS 인증키와 Bearer 토큰은 포함되지 않습니다.",12));
        Button bundleSave=primary("4종 학습 데이터 통합 백업 저장");
        bundleSave.setOnClickListener(v->{
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(android.content.Intent.EXTRA_TITLE,"StudyOne-transfer-bundle.json");
            startActivityForResult(intent,BUNDLE_EXPORT);
        });
        c.addView(bundleSave);
        Button bundleOpen=secondary("통합 백업 가져오기 · 기존 기록 교체");
        bundleOpen.setOnClickListener(v->{
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            startActivityForResult(intent,BUNDLE_IMPORT);
        });
        c.addView(bundleOpen);
        Button recovery=secondary("가져오기 전 원본 복구 파일 내보내기");
        recovery.setOnClickListener(v->{
            if(latestTransferRecovery()==null) {
                toast("내부에 저장된 이전 원본 복구 파일이 없습니다.");return;
            }
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(android.content.Intent.EXTRA_TITLE,"StudyOne-transfer-recovery.json");
            startActivityForResult(intent,BUNDLE_RECOVERY_EXPORT);
        });
        c.addView(recovery);
        c.addView(cardText("주의: 통합 가져오기는 4종 기록을 모두 교체합니다. 각 저장소 작업은 "
                +"단일 트랜잭션이 아니므로 오류 시 일부만 적용될 수 있습니다. "
                +"이 경우 내보낼 수 있는 이전 원본 복구 파일을 확인하세요.",12));

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
        c.addView(cardText("일정·진도·학습 상세·오답의 JSON은 서로 다른 데이터입니다. "
                +"기존 원본을 백업한 뒤 가져오세요. 별도 데이터는 자동 동기화되지 않습니다.",12));
        Button toProgress=secondary("진도 현황으로 이동");
        toProgress.setOnClickListener(v->openPlannerPage(3));c.addView(toProgress);
        Button toWrong=secondary("오답 기록으로 이동");
        toWrong.setOnClickListener(v->openPlannerPage(4));c.addView(toWrong);
    }

    /** A live, clickable five-role snapshot; carryover is presentation only. */
    /** Today and carryover are views of existing records; no due date or category mutation. */
    private void appendTodayChecklist(LinearLayout root,List<Models.StudyTask> all,LocalDate today) {
        List<Models.StudyTask> selected=StudyDailyChecklist.visible(all,today);
        int complete=0;
        for(Models.StudyTask task:selected)if(task.completed)complete++;
        root.addView(section("오늘 할꺼 · 5개 학습 분류"));
        root.addView(cardText(today+" · 완료 "+complete+"/"+selected.size()
                +"개 · 오늘 일정 및 이전 날짜의 미완료 항목",13));
        if(selected.isEmpty()) {
            root.addView(cardText("오늘 마감이거나 이월된 일정은 없습니다. 내일 숙제는 아래에서 미리 확인하세요.",13));
            return;
        }
        for(int i=0;i<StudyCategories.ROLE_TITLES.length;i++) {
            int count=0;
            for(Models.StudyTask task:selected)
                if(StudyDailyChecklist.groupIndex(task,today)==i)count++;
            if(count==0)continue;
            root.addView(section((i+1)+". "+StudyCategories.ROLE_TITLES[i]+" · "+count));
            for(Models.StudyTask task:selected) {
                if(StudyDailyChecklist.groupIndex(task,today)!=i)continue;
                root.addView(taskCard(task));
            }
        }
        int legacy=0;
        for(Models.StudyTask task:selected)
            if(StudyDailyChecklist.groupIndex(task,today)==5)legacy++;
        if(legacy>0) {
            root.addView(section("기존 분류 일정 · "+legacy));
            for(Models.StudyTask task:selected)
                if(StudyDailyChecklist.groupIndex(task,today)==5)root.addView(taskCard(task));
        }
    }

    private boolean isDeveloperBuild() {
        return (getApplicationInfo().flags &
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
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


        c.addView(section("계정 동기화 · ChatGPT 계획"));
        c.addView(cardText("정식 계정 동기화는 아직 준비 중입니다. 현재 학습 기록은 이 기기에 저장되며 "
                +"개인 HTTPS StudyOne 서버와 쓰기 권한 장치 토큰을 발급받은 경우에만 "
                +"AI 공부 계획 승인함을 수동으로 열 수 있습니다. 이 ChatGPT 대화와 "
                +"현재 앱은 아직 자동으로 연결되지 않습니다.",12));
        Button chatgptPlans=secondary("ChatGPT 공부 계획 승인함 · 수동 HTTPS 연결");
        chatgptPlans.setOnClickListener(v->openVerifiedPlanInbox());
        c.addView(chatgptPlans);
        // Debug-only transport experiments must not be presented as production sync.
        if (isDeveloperBuild()) {
            Button developer=secondary("개발자 도구 · 로컬 동기화 실험");
            developer.setOnClickListener(v->new AlertDialog.Builder(this)
                    .setTitle("개발자 테스트 전용")
                    .setMessage("이 기능은 로컬 개발 서버를 별도로 실행한 경우에만 사용합니다. 학습 기록은 자동으로 동기화되지 않으며, 서버 전송은 별도의 명시적 작업입니다.")
                    .setPositiveButton("실험 도구 열기",(d,w)->syncExperimentDialog())
                    .setNegativeButton("취소",null).show());
            c.addView(developer);
        }

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

        c.addView(section("앱 이전 / 데이터 보존"));
        c.addView(cardText("Canary 앱 ID와 서명이 바뀌면 자동 업데이트·기록 이전이 되지 않을 수 있습니다. "
                +"플래너 → 관리에서 4종 학습 데이터 통합 백업을 만들거나 가져오세요.",12));
        Button migration=secondary("플래너 관리에서 통합 백업하기");
        migration.setOnClickListener(v->openPlannerPage(6));
        c.addView(migration);

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
        c.addView(cardText("StudyOne "+getPackageManagerVersion()+"\nCanary 검증판 · 데이터는 기기에 저장\n공식 서명판은 동일 앱에 덮어쓰기 가능 (버전 코드 증가·서명 일치 시)",13));
        return s;
    }

    /**
     * Explicit, production-visible HTTPS plan inbox.
     * Does NOT enable experimental upload, store bearer tokens, or sync in background.
     * Plans are committed to the account only after the user's approval and
     * imported to this device only through the existing non-overwriting path.
     */
    private void openVerifiedPlanInbox() {
        LinearLayout body=column();
        body.setPadding(dp(20),dp(8),dp(20),dp(8));
        body.addView(cardText("ChatGPT 연결 실험 기능 · HTTPS 계정 서버가 별도로 필요합니다. "
                +"지금 이 대화와 자동 연동되지는 않습니다. 서버 주소·인증 토큰을 직접 "
                +"설정한 경우에만 동작합니다. 어떤 데이터도 자동 업로드하지 않습니다.",12));
        EditText address=input("StudyOne HTTPS 계정 서버 (https://.../)", "https://");
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        body.addView(address);
        EditText token=input("계정 장치 토큰 (입력 중에만 사용·저장 안 함)", "");
        token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        body.addView(token);
        TextView status=tv("승인 전 학습 계획은 휴대폰 일정에 들어가지 않습니다. "
                +"먼저 승인 대기함을 열어 검토하세요.",13,MUTED,false);
        body.addView(status);
        Button inbox=primary("AI 학습 계획 승인 대기함 확인");
        Button download=secondary("승인 완료된 신규 일정 가져오기");
        body.addView(inbox);
        body.addView(download);
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("StudyOne · ChatGPT 공부 계획")
                .setView(body)
                .setPositiveButton("닫기",(d,w)->{})
                .create();
        dialog.setOnDismissListener(d->token.setText(""));
        dialog.show();
        inbox.setOnClickListener(v->{
            try {
                String endpoint=StudySyncClient.checkedAccountAddress(
                        address.getText().toString().trim());
                showStudyPlanInbox(token.getText().toString(),endpoint,status,dialog);
            }catch(Exception invalid){
                status.setText("유효한 HTTPS 서버 루트 주소를 확인하세요. "
                        +"HTTP와 서버 경로·쿼리 입력은 허용되지 않습니다.");
            }
        });
        download.setOnClickListener(v->{
            try {
                String endpoint=StudySyncClient.checkedAccountAddress(
                        address.getText().toString().trim());
                importNewRemote(true,token.getText().toString(),endpoint,status,dialog);
            }catch(Exception invalid){
                status.setText("유효한 HTTPS 계정 서버 주소를 확인하세요.");
            }
        });
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
        EditText account=input("대기열 대상 계정 UUID (v2/session accountId)","");
        form.addView(account);
        TextView result=tv("서버를 읽기 전입니다. 서버 프로그램을 별도로 실행해야 합니다.",13,MUTED,false);
        result.setPadding(dp(4),dp(12),dp(4),dp(12));
        Button preview=secondary("서버 기록 수 확인 · 전송 없음");
        Button upload=primary("서버에 없는 새 항목만 전송");
        Button pendingPlans=secondary("AI 공부 계획 승인 대기함");
        Button pullTasks=secondary("서버의 신규 일정만 안전하게 가져오기");
        Button pullProgress=secondary("서버의 신규 진도만 안전하게 가져오기");
        Button queueSave=secondary("오프라인 새 기록 보관 · 승인 필요");
        Button queueSend=secondary("대기 기록 HTTPS 수동 재전송 · 승인 필요");
        Button queueStatus=secondary("대기열 상태 확인");
        Button queueDiscard=secondary("첫 대기 요청 폐기 · 승인 필요");
        form.addView(preview);
        form.addView(upload);
        form.addView(pullTasks);
        form.addView(pullProgress);
        form.addView(pendingPlans);
        form.addView(cardText("오프라인 대기열은 토큰을 저장하지 않습니다. 과목·학습 기록은 앱 내부 JSON에 저장되므로 기기 잠금과 외부 백업이 필요합니다. 네트워크 전송에는 동일한 HTTPS 주소·계정·쓰기 권한 토큰과 별도 승인이 필요합니다.",12));
        form.addView(queueSave);
        form.addView(queueSend);
        form.addView(queueStatus);
        form.addView(queueDiscard);
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

        StudySyncOutbox outbox=new StudySyncOutbox(getApplicationContext());
        queueStatus.setOnClickListener(v->{
            try{
                JSONArray rows=outbox.read().getJSONArray("batches");
                int count=0;
                for(int i=0;i<rows.length();i++)
                    count+=rows.getJSONObject(i).getJSONArray("mutations").length();
                result.setText("오프라인 대기 요청 "+rows.length()+"개 · 신규 기록 "+count
                        +"개. 토큰은 로컬 파일에 저장하지 않습니다.");
            }catch(Exception error){result.setText("대기열 읽기 실패. 원본 보존: "+error.getMessage());}
        });
        queueSave.setOnClickListener(v->{
            final String target=account.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            final String endpoint=address.getText().toString().trim();
            new AlertDialog.Builder(this)
                .setTitle("오프라인 기록 보관 확인")
                .setMessage("일정·진도 신규 기록을 서버 전송 없이 앱 내부 JSON에 저장합니다. "
                    +"해당 파일은 암호화되지 않은 개인 학습 내용이므로 휴대전화 저장소를 보호하세요. 계속할까요?")
                .setNegativeButton("취소",null)
                .setPositiveButton("로컬 대기열 저장",(d,w)->{
                    queueSave.setEnabled(false);
                    io.execute(()->{
                        try {
                            int saved=outbox.enqueue(target,endpoint,
                                storage.checkedTasksForSync(),progressStorage.list());
                            runOnUiThread(()->{
                                if(dialog.isShowing())result.setText(
                                    "새 기록 "+saved+"개를 기기에 보관했습니다. 서버 전송은 하지 않았습니다.");
                                queueSave.setEnabled(true);
                            });
                        }catch(Exception ex){
                            runOnUiThread(()->{result.setText("대기열 보관 실패: "+ex.getMessage());
                                queueSave.setEnabled(true);});
                        }
                    });
                }).show();
        });
        queueSend.setOnClickListener(v->{
            final String target=account.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            final String endpoint=address.getText().toString().trim();
            final String secret=token.getText().toString();
            queueSend.setEnabled(false);
            io.execute(()->{
                try{
                    JSONArray rows=outbox.read().getJSONArray("batches");
                    if(rows.length()==0)throw new IllegalStateException("대기열이 비어 있습니다.");
                    final JSONObject first=rows.getJSONObject(0);
                    if(!target.equals(first.getString("accountId")))
                        throw new IllegalStateException("대상 계정 UUID가 다릅니다.");
                    outbox.validateLocal(first,storage.checkedTasksForSync(),progressStorage.list());
                    JSONObject session=StudySyncClient.accountSession(secret,endpoint);
                    StudySyncOutbox.checkSession(first,session,endpoint);
                    runOnUiThread(()->{
                        if(!dialog.isShowing()){queueSend.setEnabled(true);return;}
                        new AlertDialog.Builder(this)
                            .setTitle("HTTPS 계정 확인 · 수동 재전송")
                            .setMessage("인증된 계정 "+target+"에 신규 항목 "
                                +first.optJSONArray("mutations").length()+"개를 전송합니다. "
                                +"기존 ID를 수정하거나 삭제하지 않습니다. 승인하시겠습니까?")
                            .setNegativeButton("취소",(d,w)->queueSend.setEnabled(true))
                            .setPositiveButton("대기 요청 전송",(d,w)->io.execute(()->{
                                try{
                                    outbox.validateLocal(first,storage.checkedTasksForSync(),
                                        progressStorage.list());
                                    JSONObject latest=StudySyncClient.accountSession(secret,endpoint);
                                    StudySyncOutbox.checkSession(first,latest,endpoint);
                                    int sent=StudySyncClient.uploadQueued(secret,endpoint,first);
                                    outbox.removeFirst(first.getString("requestId"));
                                    runOnUiThread(()->{result.setText("새 기록 "+sent
                                        +"개 전송 확인. 첫 대기 요청을 삭제했습니다.");
                                        queueSend.setEnabled(true);});
                                }catch(Exception ex){
                                    runOnUiThread(()->{result.setText("전송 실패·결과 미확인. 같은 요청 ID를 보존했습니다: "
                                        +ex.getMessage());queueSend.setEnabled(true);});
                                }
                            })).show();
                    });
                }catch(Exception ex){
                    runOnUiThread(()->{result.setText("대기 요청 확인 실패: "+ex.getMessage());
                        queueSend.setEnabled(true);});
                }
            });
        });
        queueDiscard.setOnClickListener(v->{
            try {
                JSONArray rows=outbox.read().getJSONArray("batches");
                if(rows.length()==0){result.setText("폐기할 요청이 없습니다.");return;}
                String id=rows.getJSONObject(0).getString("requestId");
                new AlertDialog.Builder(this).setTitle("첫 대기 요청 폐기")
                    .setMessage("서버로 보내지 않은 첫 대기 요청을 폐기합니다. 실제 학습 기록은 보존됩니다.")
                    .setNegativeButton("취소",null)
                    .setPositiveButton("대기 요청만 폐기",(d,w)->{
                        try{outbox.removeFirst(id);result.setText("첫 요청을 폐기했습니다.");}
                        catch(Exception ex){result.setText("폐기 실패: "+ex.getMessage());}
                    }).show();
            }catch(Exception ex){result.setText("대기열 확인 실패: "+ex.getMessage());}
        });

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
                    StudySyncClient.IncomingPlan local=StudySyncClient.previewInbound(
                            storage.checkedTasksForSync(),progressStorage.list(),server);
                    JSONArray outgoing=StudySyncClient.prepareNewOnly(
                            storage.checkedTasksForSync(),progressStorage.list(),server);
                    final String message="서버 대조 완료 · cursor "+server.getLong("cursor")
                            +"\n서버 일정 "+tasks+"개 · 진도 "+progress+"개"
                            +"\n서버에만 있는 일정 "+local.newTasks.size()+"개 · 진도 "
                            +local.newProgress.size()+"개"
                            +"\n기기에만 있는 신규 기록 "+outgoing.length()+"개"
                            +"\n내용 일치 "+local.unchanged+"개 · 같은 ID의 내용 충돌 "
                            +local.conflicts+"개"
                            +"\n충돌은 자동으로 덮어쓰지 않습니다. 이 조회는 데이터를 전송하지 않았습니다.";
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
                    JSONObject reviewed=StudySyncClient.snapshot(secret,endpoint);
                    StudySyncClient.IncomingPlan differences=StudySyncClient.previewInbound(
                            storage.checkedTasksForSync(),progressStorage.list(),reviewed);
                    if(differences.conflicts>0)
                        throw new IllegalStateException("서로 다른 내용의 동일 ID "+
                                differences.conflicts+"개가 있어 업로드하지 않습니다.");
                    JSONArray prepared=StudySyncClient.prepareNewOnly(
                            storage.checkedTasksForSync(), progressStorage.list(),reviewed);
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
                                            JSONObject latest=StudySyncClient.snapshot(secret,endpoint);
                                            if(reviewed.getLong("cursor")!=latest.getLong("cursor"))
                                                throw new IllegalStateException("승인 중 서버 기록이 변경됐습니다.");
                                            StudySyncClient.IncomingPlan verified=StudySyncClient.previewInbound(
                                                    storage.checkedTasksForSync(),progressStorage.list(),latest);
                                            if(verified.conflicts>0)
                                                throw new IllegalStateException("동일 ID 충돌이 발생했습니다.");
                                            JSONArray confirmed=StudySyncClient.prepareNewOnly(
                                                    storage.checkedTasksForSync(),progressStorage.list(),latest);
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
        if(request==BUNDLE_EXPORT) {
            try(java.io.OutputStream out=getContentResolver().openOutputStream(uri,"w")) {
                if(out==null)throw new IllegalStateException("백업 파일을 만들 수 없습니다.");
                String bundleText=StudyPortableBundle.encode(StudyPortableBundle.capture(
                        storage,progressStorage,detailStorage,wrongAnswers));
                out.write(bundleText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                toast("4종 학습 데이터 통합 백업을 저장했습니다.");
            } catch(Exception e) {showBackupError(e);}
        }else if(request==BUNDLE_IMPORT) {
            try(java.io.InputStream in=getContentResolver().openInputStream(uri)) {
                if(in==null)throw new IllegalStateException("통합 백업 파일을 읽지 못했습니다.");
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1) {
                    if(bytes.size()+n>8_200_000)throw new IllegalArgumentException("통합 백업 최대 8.2MB");
                    bytes.write(buffer,0,n);
                }
                StudyPortableBundle.Checked verified=StudyPortableBundle.parse(bytes.toString("UTF-8"));
                StudyPortableBundle.Snapshot original=StudyPortableBundle.capture(
                        storage,progressStorage,detailStorage,wrongAnswers);
                // Also validate current records now, before displaying the destructive consent.
                StudyPortableBundle.validate(original);
                new AlertDialog.Builder(this)
                    .setTitle("4종 기록을 전부 교체하시겠습니까?")
                    .setMessage("가져올 내용: "+verified.summary()+"\n\n"
                        +"현재 이 앱의 네 종류 기록을 전부 교체합니다. 기존 원본은 변경 직전 "
                        +"앱 내부에 백업하고 별도로 내보낼 수 있습니다.\n"
                        +"4종 저장은 원자적이지 않아 실패 시 일부만 교체될 수 있습니다. "
                        +"복구 파일을 삭제하지 마세요.\n"
                        +"NEIS 인증키·계정 및 서버 정보는 변경하지 않습니다.")
                    .setPositiveButton("검증한 기록 4종 교체",(d,w)->{
                        try {
                            String recoveryFile=StudyPortableBundle.restoreWithRecovery(this,
                                storage,progressStorage,detailStorage,wrongAnswers,
                                verified,original);
                            openPlannerPage(6);
                            new AlertDialog.Builder(this).setTitle("기록 이전 완료")
                                .setMessage("네 종류의 기록을 복원했습니다.\n이전 원본 파일: "
                                    +recoveryFile+"\n필요하면 관리에서 원본 복구 파일을 내보내세요.")
                                .setPositiveButton("확인",null).show();
                        }catch(Exception error){
                            new AlertDialog.Builder(this).setTitle("일부 기록이 적용되었을 수 있습니다")
                                .setMessage("원인: "+error.getMessage()
                                   +"\n\n기존 원본이 앱 내부에 저장됐을 수 있습니다. "
                                   +"관리 → '가져오기 전 원본 복구 파일 내보내기'로 먼저 "
                                   +"원본을 보존하고, 작업별 상태를 확인하세요.")
                                .setPositiveButton("확인",null).show();
                        }
                    })
                    .setNegativeButton("취소",null).show();
            }catch(Exception e){showBackupError(e);}
        }else if(request==BUNDLE_RECOVERY_EXPORT) {
            String name=latestTransferRecovery();
            if(name==null){toast("복구 파일이 없습니다.");return;}
            try(java.io.InputStream in=openFileInput(name);
                java.io.OutputStream out=getContentResolver().openOutputStream(uri,"w")) {
                if(out==null)throw new IllegalStateException("원본 백업을 저장할 수 없습니다.");
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                out.flush(); toast("가져오기 전 원본 복구 파일을 내보냈습니다.");
            }catch(Exception e){showBackupError(e);}
        }else if(request==BACKUP_EXPORT) {
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
                final String approvedRaw=progressStorage.rawProgressForSync();
                // Fail closed before showing the user a destructive confirmation.
                progressStorage.list();
                new AlertDialog.Builder(this)
                        .setTitle("진도 백업 가져오기")
                        .setMessage("진도 "+parsed.size()+"개를 확인했습니다.\n"
                                +"현재 이 기기의 진도 목록만 교체합니다. "
                                +"학교·과제·수행평가 일정은 그대로 유지합니다. "
                                +"기존 진도 전체를 앱 내부 복구 파일에 저장한 뒤 교체합니다. "
                                +"현재 진도의 외부 백업도 권장합니다. 계속할까요?")
                        .setPositiveButton("진도 교체",(dialog,which)->{
                            try {
                                String recovery=progressStorage.restoreBackupWithRecovery(
                                        parsed,approvedRaw,getApplicationContext());
                                showTab(3);
                                new AlertDialog.Builder(this)
                                        .setTitle("진도 백업 복원 완료")
                                        .setMessage("기존 진도 복구 파일을 앱 내부에 보존했습니다.\n"
                                                +recovery)
                                        .setPositiveButton("확인",null).show();
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


    private String latestTransferRecovery() {
        String latest=null;
        for(String name:fileList()) {
            if(!name.startsWith("StudyOne-transfer-before-import-") || !name.endsWith(".json"))continue;
            if(latest==null || name.compareTo(latest)>0)latest=name;
        }
        return latest;
    }

    private String getPackageManagerVersion() {
        try {
            android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);
            return info.versionName==null?"Canary":info.versionName;
        }catch(Exception e){return "Canary";}
    }

    private void showBackupError(Exception e) {
        new AlertDialog.Builder(this)
                .setTitle("백업 처리 실패")
                .setMessage(e.getMessage()==null?"파일 형식을 확인하세요.":e.getMessage())
                .setPositiveButton("확인",null).show();
    }

    /** Add only absent six-subject online-exam practice entries after explicit consent.
     * Existing records (including their completion state and IDs) are never changed.
     * A protected local progress backup is created before any new entry is saved.
     */
    private void showOnlinePracticeDialog() {
        try {
            final String reviewedRaw=progressStorage.rawProgressForSync();
            final List<String> missing=StudyOnlinePractice.missingSubjects(progressStorage.list());
            if(missing.isEmpty()) {
                new AlertDialog.Builder(this)
                    .setTitle("온라인 내신 문제")
                    .setMessage("6과목 모두 온라인 내신 문제 진도가 이미 등록되어 있습니다. "
                            +"기존 상태와 기록은 변경하지 않았습니다.")
                    .setPositiveButton("확인",null).show();
                return;
            }
            new AlertDialog.Builder(this)
                .setTitle("6과목 온라인 내신 문제 빠른 추가")
                .setMessage("기존 온라인 내신 문제 항목은 완료·진행 중·미완료 상태와 "
                        +"관계없이 중복 추가하지 않습니다.\n\n"
                        +"새로 추가할 과목 ("+missing.size()+"개): "
                        +android.text.TextUtils.join(", ",missing)+"\n"
                        +"새 항목 상태: 미완료 · 시험범위 포함\n\n"
                        +"저장 전에 기존 진도 원본을 앱 내부에 보관합니다. "
                        +"기존 과제·진도·오답 및 완료 상태는 변경하지 않습니다.")
                .setNegativeButton("취소",null)
                .setPositiveButton("신규 "+missing.size()+"개만 추가",(dialog,which)->{
                    try {
                        if(!java.util.Objects.equals(reviewedRaw,
                                progressStorage.rawProgressForSync()))
                            throw new IllegalStateException(
                                    "검토 중 진도가 수정됐습니다. 새로 미리보기를 확인하세요.");
                        List<StudyProgressItem> latest=progressStorage.list();
                        if(!missing.equals(StudyOnlinePractice.missingSubjects(latest)))
                            throw new IllegalStateException(
                                    "추가 대상이 변경됐습니다. 새로 미리보기를 확인하세요.");
                        List<StudyProgressItem> additions=
                                StudyOnlinePractice.createMissing(latest);
                        // Synchronised storage rechecks exact original bytes and backs up
                        // the old list before appending new UUIDs.
                        String recovery=progressStorage.appendNewProgressForSync(
                                reviewedRaw,additions,getApplicationContext());
                        openPlannerPage(3);
                        new AlertDialog.Builder(this)
                            .setTitle("온라인 내신 문제 "+additions.size()+"개 추가 완료")
                            .setMessage("기존 항목은 보존하고 미완료 진도만 추가했습니다.\n"
                                    +"기존 진도 복구용 내부 파일: "+recovery)
                            .setPositiveButton("확인",null).show();
                    } catch(Exception error) {
                        showBackupError(error);
                    }
                }).show();
        } catch(Exception error) {
            showBackupError(error);
        }
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



    /** Privacy-preserving handwritten-style practice: target exists only in this dialog. */
    private void appendWritingPractice(LinearLayout parent) {
        parent.addView(section("영어 쓰기 수행 연습"));
        parent.addView(cardText("외울 문장을 직접 입력한 뒤 가리고, 정답을 써서 비교합니다. 문장은 기기·AI·서버에 저장하거나 전송하지 않습니다.",12));
        Button open=secondary("쓰기 암기 테스트 시작");
        open.setOnClickListener(v->{
            EditText original=input("암기할 영어 문장", "");
            AlertDialog setup=new AlertDialog.Builder(this)
                    .setTitle("영어 쓰기 · 정답 입력")
                    .setView(original).setPositiveButton("가리고 시작",null)
                    .setNegativeButton("취소",null).create();
            setup.setOnShowListener(w->setup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
                String target=original.getText().toString().trim();
                if(target.isEmpty() || target.length()>1500) {
                    toast("영어 문장을 1~1500자로 입력하세요."); return;
                }
                setup.dismiss();
                EditText answer=input("정답을 보지 않고 입력하세요", "");
                answer.setSingleLine(false);
                answer.setMinLines(3);
                AlertDialog practice=new AlertDialog.Builder(this).setTitle("문장을 가리고 쓰기")
                    .setView(answer).setPositiveButton("채점",null)
                    .setNegativeButton("취소",null).create();
                practice.setOnShowListener(z->practice.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(y->{
                    String entered=answer.getText().toString().trim();
                    if(entered.isEmpty()){toast("문장을 입력하세요.");return;}
                    // Whitespace-normalized only; punctuation and capitalization still matter.
                    boolean exact=target.replaceAll("\\s+"," ").equals(entered.replaceAll("\\s+"," "));
                    practice.dismiss();
                    new AlertDialog.Builder(this).setTitle(exact?"정확하게 작성했습니다.":"다른 부분이 있습니다.")
                        .setMessage("정답: "+target+"\n\n내가 쓴 문장: "+entered+
                            "\n\n공백만 정리해서 비교했습니다. 대문자·구두점은 구분합니다.")
                        .setPositiveButton("확인",null).show();
                }));
                practice.show();
            }));
            setup.show();
        });
        parent.addView(open);
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

    private View section(String x){
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(3),dp(17),dp(2),dp(9));
        View marker=new View(this);
        GradientDrawable accent=new GradientDrawable();
        accent.setColor(BLUE);
        accent.setCornerRadius(dp(8));
        marker.setBackground(accent);
        LinearLayout.LayoutParams markerParams=new LinearLayout.LayoutParams(dp(4),dp(18));
        markerParams.setMargins(0,0,dp(11),0);
        row.addView(marker,markerParams);
        row.addView(tv(x,17,TEXT,true));
        return row;
    }
    private LinearLayout card(){
        LinearLayout l=column();l.setPadding(dp(16),dp(14),dp(16),dp(14));GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setCornerRadius(dp(18));g.setStroke(dp(1),Color.rgb(224,230,242));l.setBackground(g);l.setElevation(dp(1));
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
