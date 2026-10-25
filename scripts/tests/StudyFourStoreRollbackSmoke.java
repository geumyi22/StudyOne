import com.studyone.app.StudyFourStoreRollback;
import java.util.Arrays;

/** Synthetic standalone 4-store failure-injection test, no Android user data. */
public final class StudyFourStoreRollbackSmoke {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        for (int failing = 0; failing < 4; failing++) {
            final int failureIndex = failing;
            int[] original = { 11, 22, 33, 44 };
            int[] current = original.clone();
            StudyFourStoreRollback.Work[] forward = new StudyFourStoreRollback.Work[4];
            StudyFourStoreRollback.Work[] rollback = new StudyFourStoreRollback.Work[4];
            for (int i = 0; i < 4; i++) {
                final int index = i;
                forward[i] = () -> {
                    current[index] = 100 + index;
                    if (index == failureIndex) throw new IllegalStateException("injected");
                };
                rollback[i] = () -> current[index] = original[index];
            }
            try {
                StudyFourStoreRollback.apply(forward, rollback, () -> {
                    if (!Arrays.equals(current, original))
                        throw new IllegalStateException("rollback verification failed");
                });
                throw new AssertionError("Injected failure was swallowed");
            } catch (IllegalStateException error) {
                check(error.getMessage().contains("복구했습니다"),
                        "successful compensating rollback must be reported");
            }
            check(Arrays.equals(current, original),
                    "all four stores must match original bytes after any forward fault");
        }
        int[] restored = { 1, 2, 3, 4 };
        StudyFourStoreRollback.Work[] forward = new StudyFourStoreRollback.Work[4];
        StudyFourStoreRollback.Work[] rollback = new StudyFourStoreRollback.Work[4];
        for (int i = 0; i < 4; i++) {
            final int index = i;
            forward[i] = () -> restored[index] = 9;
            rollback[i] = () -> restored[index] = index + 1;
        }
        StudyFourStoreRollback.apply(forward, rollback,
                () -> { throw new AssertionError("Success should not invoke rollback check"); });
        check(Arrays.equals(restored, new int[]{9, 9, 9, 9}), "all forward writes");

        forward[0] = () -> { restored[0] = 88; throw new Exception("simulated failure"); };
        rollback[3] = () -> { throw new Exception("simulated recovery failure"); };
        try {
            StudyFourStoreRollback.apply(forward, rollback,
                    () -> { if (restored[0] != 1)
                        throw new Exception("simulated integrity mismatch"); });
            throw new AssertionError("Failed rollback was hidden");
        } catch (IllegalStateException error) {
            check(error.getMessage().contains("원본 복구 파일"),
                    "unrecoverable rollback must require manual recovery");
            check(error.getCause() != null && error.getCause().getSuppressed().length > 0,
                    "rollback failure should be retained as a suppressed cause");
        }
        System.out.println("StudyOne 4-store fault injection and rollback coordinator: PASS");
    }
}
