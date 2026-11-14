import com.studyone.app.StudyPaperGenericProgressLink;
import com.studyone.app.StudyPaperSchedule;
import com.studyone.app.StudyProgressItem;
import java.util.ArrayList;
import java.util.List;

/** Pure synthetic smoke: no Android preferences, user data or network. */
public final class StudyPaperGenericProgressLinkSmoke {
    static int checks=0;
    static void check(boolean ok,String why) {
        checks++; if(!ok)throw new AssertionError(why);
    }
    static void reject(Runnable action,String why) {
        boolean refused=false;
        try { action.run(); } catch(IllegalArgumentException expected){refused=true;}
        check(refused,why);
    }
    public static void main(String[] ignored) {
        var lane=new StudyPaperSchedule.Lane("1단원",List.of(
            new StudyPaperSchedule.Paper("형성평가",3,0)));
        List<StudyProgressItem> rows=new ArrayList<>();
        String[] circled={"①","②","③"};
        for(int i=0;i<3;i++)rows.add(StudyProgressItem.create("수학","풍산자",
            "[1단원] 형성평가 "+circled[i],
            i==0?StudyProgressItem.DONE:StudyProgressItem.TODO,true));
        List<String> originalIds=rows.stream().map(p->p.id).toList();
        var linked=StudyPaperGenericProgressLink.checked(rows,"수학","풍산자",List.of(lane));
        check(linked.completed==1&&linked.lanes.get(0).papers.get(0).completed==1,
            "One exact completed round");
        check(originalIds.equals(rows.stream().map(p->p.id).toList()),
            "Individual UUIDs never modified");
        reject(()->StudyPaperGenericProgressLink.checked(rows.subList(0,2),
            "수학","풍산자",List.of(lane)),"Missing round fails");
        reject(()->StudyPaperGenericProgressLink.checked(
            List.of(StudyProgressItem.create("수학","풍산자","1/3 완료",
                StudyProgressItem.IN_PROGRESS,true)),"수학","풍산자",List.of(lane)),
            "No guessing aggregate completion");
        List<StudyProgressItem> duplicates=new ArrayList<>(rows);duplicates.add(rows.get(0));
        reject(()->StudyPaperGenericProgressLink.checked(duplicates,
            "수학","풍산자",List.of(lane)),"Duplicate UUID fails");
        List<StudyProgressItem> outOfOrder=new ArrayList<>(rows);
        outOfOrder.set(2,StudyProgressItem.create("수학","풍산자",
            "[1단원] 형성평가 ③",StudyProgressItem.DONE,true));
        reject(()->StudyPaperGenericProgressLink.checked(outOfOrder,
            "수학","풍산자",List.of(lane)),"Nonconsecutive completion fails");
        List<StudyProgressItem> excluded=new ArrayList<>(rows);
        excluded.set(1,StudyProgressItem.create("수학","풍산자",
            "[1단원] 형성평가 ②",StudyProgressItem.TODO,false));
        reject(()->StudyPaperGenericProgressLink.checked(excluded,
            "수학","풍산자",List.of(lane)),"Excluded exam scope fails");
        System.out.println("GENERIC_PROGRESS_LINK_PASSED: "+checks+" assertions");
    }
}
