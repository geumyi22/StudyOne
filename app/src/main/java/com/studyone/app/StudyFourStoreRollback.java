package com.studyone.app;

/**
 * Four independent local stores are NOT an ACID transaction. A complete,
 * validated recovery bundle must already be durable before calling this.
 * If an apply operation fails, attempt compensating restore of all four.
 * Unrecoverable failure is reported and must retain the private recovery file.
 */
public final class StudyFourStoreRollback {
    @FunctionalInterface
    public interface Work { void run() throws Exception; }
    private StudyFourStoreRollback() {}

    public static void apply(Work[] forward, Work[] restore, Work verifyRestored)
            throws Exception {
        if (forward == null || restore == null || verifyRestored == null
                || forward.length != 4 || restore.length != 4)
            throw new IllegalArgumentException("4종 적용/복원 단계가 올바르지 않습니다.");
        for (int i = 0; i < 4; i++)
            if (forward[i] == null || restore[i] == null)
                throw new IllegalArgumentException("4종 처리 단계가 누락됐습니다.");

        try {
            for (Work section : forward) section.run();
        } catch (Exception originalFailure) {
            int problems = 0;
            // Undo even the operation that threw: it may have partially committed.
            // Restore in reverse order; do not stop after one rollback error.
            for (int i = 3; i >= 0; i--) {
                try { restore[i].run(); }
                catch (Exception rollbackError) {
                    problems++;
                    originalFailure.addSuppressed(rollbackError);
                }
            }
            try { verifyRestored.run(); }
            catch (Exception verificationError) {
                problems++;
                originalFailure.addSuppressed(verificationError);
            }
            if (problems != 0)
                throw new IllegalStateException(
                    "4종 가져오기/복구 중 오류가 발생했습니다. 내부에 보관된 원본 복구 파일을 사용하세요.",
                    originalFailure);
            throw new IllegalStateException(
                "4종 가져오기에 실패했지만 기존 네 종류의 기록으로 복구했습니다.",
                originalFailure);
        }
    }
}
