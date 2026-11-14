package com.studyone.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Opt-in all-subject editor. The existing fixed Korean 18-round form stays
 * unchanged. All input and previews are local; explicit confirmation is required
 * for the guarded append to the task store. Never persists template guesses.
 */
public final class StudyPaperGenericDialog {
    private StudyPaperGenericDialog() {}
    private static final class InputRow {
        final LinearLayout container;
        final EditText unit, title, rounds;
        InputRow(LinearLayout c, EditText u, EditText t, EditText r) {
            container=c;unit=u;title=t;rounds=r;
        }
    }
    private static int dp(Activity a,int v) {
        return (int)(v*a.getResources().getDisplayMetrics().density+0.5f);
    }
    private static TextView text(Activity a,String label) {
        TextView t=new TextView(a);
        t.setText(label);
        t.setTextSize(14);
        t.setPadding(0,dp(a,7),0,dp(a,5));
        return t;
    }
    private static EditText input(Activity a,String hint,String value) {
        EditText e=new EditText(a);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setHint(hint);
        e.setText(value);
        e.setContentDescription(hint);
        return e;
    }
    private static String value(EditText e) { return e.getText().toString(); }
    private static void showError(Activity a,Exception error) {
        new AlertDialog.Builder(a).setTitle("계획을 적용하지 않았습니다")
            .setMessage(error.getMessage()==null?"입력값을 확인하세요.":error.getMessage())
            .setPositiveButton("확인",null).show();
    }
    private static String stamp(EditText subject,EditText material,EditText start,
                                EditText end,EditText daily,EditText excluded,
                                Spinner source,List<InputRow> rows) {
        StringBuilder b=new StringBuilder();
        for(EditText e:new EditText[]{subject,material,start,end,daily,excluded})
            b.append(value(e).length()).append(':').append(value(e));
        b.append(source.getSelectedItemPosition());
        for(InputRow r:rows)
            for(EditText e:new EditText[]{r.unit,r.title,r.rounds})
                b.append(value(e).length()).append(':').append(value(e));
        return b.toString();
    }
    private static List<StudyPaperSchedule.Lane> lanes(List<InputRow> rows) {
        if(rows.size()<1||rows.size()>160)
            throw new IllegalArgumentException("시험지 종류는 1~160개까지 입력할 수 있습니다.");
        LinkedHashMap<String,List<StudyPaperSchedule.Paper>> byLane=new LinkedHashMap<>();
        for(InputRow row:rows) {
            String[] count=value(row.rounds).trim().split("/",-1);
            if(count.length!=2) throw new IllegalArgumentException("회차는 총/완료 형식이어야 합니다.");
            int total=Integer.parseInt(count[0].trim());
            int done=Integer.parseInt(count[1].trim());
            StudyPaperSchedule.Paper paper=new StudyPaperSchedule.Paper(value(row.title),total,done);
            byLane.computeIfAbsent(value(row.unit),key->new ArrayList<>()).add(paper);
        }
        List<StudyPaperSchedule.Lane> result=new ArrayList<>();
        for(var entry:byLane.entrySet())
            result.add(new StudyPaperSchedule.Lane(entry.getKey(),entry.getValue()));
        return result;
    }
    private static void appendRow(Activity a,LinearLayout list,List<InputRow> rows) {
        if(rows.size()>=160) throw new IllegalArgumentException("종류는 최대 160개입니다.");
        LinearLayout block=new LinearLayout(a);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(a,8),dp(a,8),dp(a,8),dp(a,8));
        EditText unit=input(a,"단원 이름 (예: 1단원)","1단원");
        EditText title=input(a,"시험지 이름 (예: 소단원 평가)","");
        EditText count=input(a,"전체/연속 완료 (예: 3/1)","1/0");
        count.setInputType(InputType.TYPE_CLASS_TEXT);
        block.addView(unit);block.addView(title);block.addView(count);
        InputRow row=new InputRow(block,unit,title,count);
        Button delete=new Button(a);delete.setText("이 종류 제거");
        delete.setOnClickListener(v->{
            if(rows.size()<=1) {
                showError(a,new IllegalArgumentException("하나 이상의 시험지 종류가 필요합니다."));
                return;
            }
            list.removeView(block);rows.remove(row);
        });
        block.addView(delete);
        rows.add(row);
        list.addView(block);
    }
    public static void show(Activity a,Storage storage,StudyProgressStorage progress,Runnable onAdded) {
        ScrollView scroll=new ScrollView(a);
        LinearLayout form=new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(a,18),dp(a,12),dp(a,18),dp(a,18));
        scroll.addView(form);
        form.addView(text(a,"모든 과목의 시험지·학습지를 직접 입력합니다. 미리보기와 복사는 실제 숙제/진도를 변경하지 않습니다."));
        EditText subject=input(a,"과목 (필수)","");
        EditText material=input(a,"교재 (개별 회차 진도 불러오기 시만 필수)","");
        EditText start=input(a,"시작일 YYYY-MM-DD",LocalDate.now().toString());
        EditText end=input(a,"마감일 YYYY-MM-DD",LocalDate.now().plusDays(8).toString());
        EditText daily=input(a,"하루 최대 1~12","2");daily.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText excluded=input(a,"쉬는 날짜 YYYY-MM-DD, YYYY-MM-DD","");
        form.addView(text(a,"과목"));form.addView(subject);
        form.addView(text(a,"교재 (선택)"));form.addView(material);
        form.addView(text(a,"시작일"));form.addView(start);
        form.addView(text(a,"마감일"));form.addView(end);
        form.addView(text(a,"하루 최대 분량"));form.addView(daily);
        form.addView(text(a,"쉬는 날짜 (선택)"));form.addView(excluded);
        form.addView(text(a,"첫 개념 복습"));
        Spinner source=new Spinner(a);
        String[] reviews={"학습지","교재"};
        source.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,reviews));
        form.addView(source);
        form.addView(text(a,"단원·시험지별 총 횟수 / 연속 완료 횟수"));
        LinearLayout list=new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        form.addView(list);
        List<InputRow> rows=new ArrayList<>();
        appendRow(a,list,rows);
        Button addRow=new Button(a);addRow.setText("+ 시험지 종류 추가");
        addRow.setOnClickListener(v->{try{appendRow(a,list,rows);}catch(Exception e){showError(a,e);}});
        form.addView(addRow);
        final String[] verifiedProgress={null};
        Button autoFill=new Button(a);
        autoFill.setText("기기에서 검증된 개별 회차만 불러오기");
        autoFill.setOnClickListener(v->{
            try {
                String before=progress.rawProgressForSync();
                List<StudyPaperSchedule.Lane> template=lanes(rows);
                StudyPaperGenericProgressLink.Snapshot linked=StudyPaperGenericProgressLink.checked(
                    progress.list(),value(subject),value(material),template);
                if(!before.equals(progress.rawProgressForSync()))
                    throw new IllegalStateException("진도를 확인하는 동안 원본이 변경됐습니다.");
                LinkedHashMap<String,StudyPaperSchedule.Paper> found=new LinkedHashMap<>();
                for(StudyPaperSchedule.Lane lane:linked.lanes)
                    for(StudyPaperSchedule.Paper paper:lane.papers)
                        found.put(lane.name+"\u0001"+paper.title,paper);
                for(InputRow row:rows) {
                    StudyPaperSchedule.Paper paper=found.get(value(row.unit)+"\u0001"+value(row.title));
                    if(paper==null) throw new IllegalStateException("회차를 정확하게 연결할 수 없습니다.");
                    row.rounds.setText(paper.total+"/"+paper.completed);
                }
                verifiedProgress[0]=before;
                new AlertDialog.Builder(a).setMessage("개별 진도 UUID 및 회차 "+linked.completed+
                    "개를 확인했습니다. 집계 진도는 추정하지 않았습니다.").setPositiveButton("확인",null).show();
            }catch(Exception e){verifiedProgress[0]=null;showError(a,e);}
        });
        form.addView(autoFill);
        form.addView(text(a,"기기에 해당 과목·교재의 모든 개별 회차 진도가 정확히 없으면 자동 불러오기는 실패하며 직접 입력해야 합니다. '진도'는 새로 생성하지 않습니다."));
        AlertDialog editor=new AlertDialog.Builder(a).setTitle("범용 시험지 · 학습지 배분")
            .setView(scroll).setPositiveButton("미리보기",null)
            .setNegativeButton("닫기",null).create();
        editor.setOnShowListener(ignored->editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                String s=value(subject);
                List<StudyPaperSchedule.Lane> planLanes=lanes(rows);
                Set<LocalDate> skip=new LinkedHashSet<>();
                if(!value(excluded).trim().isEmpty()) {
                    for(String token:value(excluded).split(",",-1)){
                        if(!skip.add(LocalDate.parse(token.trim())))
                            throw new IllegalArgumentException("쉬는 날짜가 중복됐습니다.");
                    }
                }
                StudyPaperSchedule.Preview result=StudyPaperSchedule.preview(
                    LocalDate.parse(value(start).trim()),LocalDate.parse(value(end).trim()),
                    Integer.parseInt(value(daily).trim()),skip,
                    reviews[source.getSelectedItemPosition()],planLanes);
                storage.checkedTasksForSync();
                String original=storage.rawTasksForSync();
                String inputStamp=stamp(subject,material,start,end,daily,excluded,source,rows);
                String progressStamp=verifiedProgress[0];
                String content=result.clipboardText();
                TextView output=text(a,content);output.setTextIsSelectable(true);
                ScrollView shown=new ScrollView(a);
                shown.setPadding(dp(a,12),dp(a,8),dp(a,12),dp(a,8));
                shown.addView(output);
                new AlertDialog.Builder(a).setTitle("읽기 전용 · "+result.remainingPapers+"회")
                    .setView(shown)
                    .setPositiveButton("내용 복사",(d,w)->{
                        ClipboardManager c=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
                        if(c!=null)c.setPrimaryClip(ClipData.newPlainText("StudyOne 계획",content));
                    })
                    .setNeutralButton("숙제로 추가",(d,w)->{
                        new AlertDialog.Builder(a).setTitle("사용자 승인 · 신규 숙제만 추가")
                            .setMessage("기존 일정·진도는 수정하지 않습니다. 중복 또는 변경된 원본이 있으면 전체 추가를 중단하고 저장 전에 복구본을 만듭니다. 추가할까요?")
                            .setPositiveButton("검토 후 추가",(confirm,which)->{
                                try{
                                    if(!inputStamp.equals(stamp(subject,material,start,end,daily,excluded,source,rows)))
                                        throw new IllegalStateException("미리보기 이후 입력이 수정됐습니다. 다시 계산하세요.");
                                    if(progressStamp!=null && !progressStamp.equals(progress.rawProgressForSync()))
                                        throw new IllegalStateException("개별 진도 원본이 변경됐습니다. 다시 계산하세요.");
                                    String backup=storage.appendReviewedPaperSchedule(original,result,s,a);
                                    new AlertDialog.Builder(a).setTitle("저장 완료")
                                        .setMessage(result.remainingPapers+"개 신규 숙제를 추가했습니다. 복구본: "+backup)
                                        .setPositiveButton("확인",(ok,number)->{editor.dismiss();if(onAdded!=null)onAdded.run();}).show();
                                }catch(Exception error){showError(a,error);}
                            }).setNegativeButton("취소",null).show();
                    }).setNegativeButton("닫기",null).show();
            }catch(Exception error){showError(a,error);}
        }));
        editor.show();
    }
}
