import com.studyone.app.StudyPaperSchedule;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public final class StudyPaperScheduleSmoke {
    private static void check(boolean ok,String label){
        if(!ok)throw new AssertionError(label);
    }

    private static StudyPaperSchedule.Lane lane(String name, String unit, int[] completed){
        return new StudyPaperSchedule.Lane(name,Arrays.asList(
            new StudyPaperSchedule.Paper(unit+"-1 소단원 평가",3,completed[0]),
            new StudyPaperSchedule.Paper(unit+"-2 소단원 평가",3,completed[1]),
            new StudyPaperSchedule.Paper(unit+"단원 대단원 평가",3,completed[2])));
    }

    private static List<StudyPaperSchedule.Lane> lanes(int[] a,int[] b){
        return Arrays.asList(lane("2단원","2",a),lane("3단원","3",b));
    }

    private static boolean rejected(Runnable r){
        try{r.run();return false;}
        catch(IllegalArgumentException expected){return true;}
    }

    public static void main(String[] args){
        LocalDate first=LocalDate.of(2026,10,25),end=LocalDate.of(2026,11,2);
        List<StudyPaperSchedule.Lane> six=lanes(new int[]{0,0,0},new int[]{0,0,0});
        StudyPaperSchedule.Preview full=StudyPaperSchedule.preview(
            first,end,2,Collections.emptySet(),"학습지",six);
        check(full.remainingPapers==18&&full.workDays==9&&full.days.size()==9,
              "18/9 distribution");
        check(full.days.get(0).papers.get(0).title.equals("2-1 소단원 평가 ①"),
              "first lane paper");
        check(full.days.get(0).papers.get(1).title.equals("3-1 소단원 평가 ①"),
              "second lane paper");
        check(full.days.get(1).review.equals("교재")&&
              full.days.get(2).review.equals("학습지"),"alternating review");
        for(StudyPaperSchedule.Day d:full.days){
            check(d.papers.size()==2,"two per day");
            check(!d.papers.get(0).lane.equals(d.papers.get(1).lane),
                  "fair two-lane distribution");
        }
        String text=full.clipboardText();
        check(text.contains("읽기 전용 추천안")&&text.contains("☐ [2단원]"),
              "clipboard has consent notice");
        StudyPaperSchedule.Preview resume=StudyPaperSchedule.preview(
            first.plusDays(2),end,2,Collections.emptySet(),"교재",
            lanes(new int[]{1,1,0},new int[]{1,1,0}));
        check(resume.remainingPapers==14&&resume.days.size()==7,
              "resume skips four previously finished");
        check(resume.days.get(0).papers.get(0).title.equals("2-1 소단원 평가 ②"),
              "no regeneration of completed paper");
        check(resume.days.get(0).review.equals("교재"),"first review chosen");
        StudyPaperSchedule.Preview breakDay=StudyPaperSchedule.preview(
            first,end.plusDays(1),2,new HashSet<>(Arrays.asList(first.plusDays(1))),
            "학습지",six);
        check(breakDay.days.size()==9&&
              breakDay.days.get(1).date.equals(first.plusDays(2)),
              "skipped day and no silent writes");
        check(rejected(()->StudyPaperSchedule.preview(first,end,1,
              Collections.emptySet(),"학습지",six)),"over capacity must fail");
        check(rejected(()->StudyPaperSchedule.preview(first,end,2,
              Collections.emptySet(),"wrong",six)),"invalid review must fail");
        check(rejected(()->new StudyPaperSchedule.Paper("Test",3,4)),
              "completed > total must fail");
        check(rejected(()->new StudyPaperSchedule.Lane("2단원",Arrays.asList(
              new StudyPaperSchedule.Paper("duplicate",3,0),
              new StudyPaperSchedule.Paper("duplicate",3,0)))),
              "duplicate paper type must fail");
        StudyPaperSchedule.Preview all=StudyPaperSchedule.preview(first,end,2,
            Collections.emptySet(),"학습지",
            lanes(new int[]{3,3,3},new int[]{3,3,3}));
        check(all.remainingPapers==0&&all.days.isEmpty(),"already completed");
        check(six.get(0).papers.get(0).completed==0,"original is unchanged");
        System.out.println("STUDY_PAPER_SCHEDULE_OFFLINE_SMOKE_PASSED");
    }
}
