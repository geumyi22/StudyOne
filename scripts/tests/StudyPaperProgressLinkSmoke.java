import com.studyone.app.StudyPaperProgressLink;
import com.studyone.app.StudyProgressItem;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class StudyPaperProgressLinkSmoke {
    private static final String[] MARKS={"①","②","③"};
    private static void check(boolean valid,String name){
        if(!valid)throw new AssertionError(name);
    }
    private static StudyProgressItem row(String unit,String status){
        return new StudyProgressItem(UUID.randomUUID().toString(),"국어",
              StudyPaperProgressLink.MATERIAL,unit,status,true,1,1730000000000L);
    }
    private static boolean fails(Runnable action){
        try{action.run();return false;}
        catch(IllegalArgumentException expected){return true;}
    }
    public static void main(String[] args){
        List<StudyProgressItem> fixture=new ArrayList<>();
        for(int i=0;i<StudyPaperProgressLink.NAMES.length;i++){
            String title=StudyPaperProgressLink.NAMES[i];
            for(int step=0;step<3;step++){
                boolean finished=step==0&&(i==0||i==1||i==3||i==4);
                fixture.add(row(title+" "+MARKS[step],finished
                         ?StudyProgressItem.DONE:StudyProgressItem.TODO));
            }
        }
        StudyPaperProgressLink.Snapshot snapshot=StudyPaperProgressLink.checked(fixture);
        check(snapshot.completed==4&&snapshot.tracked==18,"four completed");
        check(snapshot.lanes.get(0).papers.get(0).completed==1 &&
              snapshot.lanes.get(1).papers.get(1).completed==1,"per-type counts");
        check(fixture.size()==18,"original unchanged");
        List<StudyProgressItem> missing=new ArrayList<>(fixture);
        missing.remove(17);
        check(fails(()->StudyPaperProgressLink.checked(missing)),
              "incomplete paper records must not be inferred");
        check(fails(()->StudyPaperProgressLink.checked(
              java.util.Collections.singletonList(row("인쇄 시험지 18회",
              StudyProgressItem.IN_PROGRESS)))),"aggregate is not proof");
        List<StudyProgressItem> repeated=new ArrayList<>(fixture);
        repeated.set(17,fixture.get(0));
        check(fails(()->StudyPaperProgressLink.checked(repeated)),"duplicate round fails");
        List<StudyProgressItem> gap=new ArrayList<>(fixture);
        gap.set(1,row(StudyPaperProgressLink.NAMES[0]+" ②",
               StudyProgressItem.TODO));
        gap.set(2,row(StudyPaperProgressLink.NAMES[0]+" ③",
               StudyProgressItem.DONE));
        check(fails(()->StudyPaperProgressLink.checked(gap)),
              "noncontiguous completed rounds fail");
        List<StudyProgressItem> unknown=new ArrayList<>(fixture);
        unknown.set(15,row(StudyPaperProgressLink.NAMES[5]+" ①",
                      StudyProgressItem.UNKNOWN));
        check(fails(()->StudyPaperProgressLink.checked(unknown)),
              "unknown progress fails closed");
        System.out.println("STUDY_PAPER_PROGRESS_EXACT_AUTOFILL_PASSED");
    }
}
