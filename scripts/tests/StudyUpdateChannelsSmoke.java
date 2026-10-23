import com.studyone.app.StudyUpdateChannels;

public final class StudyUpdateChannelsSmoke {
    private static int tests;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        tests++;
    }

    public static void main(String[] args) {
        check("stable".equals(StudyUpdateChannels.channel("3.1.0")), "stable parse");
        check("beta".equals(StudyUpdateChannels.channel("3.0.0-beta.2")), "beta parse");
        check("beta".equals(StudyUpdateChannels.channel("3.1.0-rc.1")), "rc parse");
        check("canary".equals(StudyUpdateChannels.channel("3.1.0-canary.6")), "canary parse");
        check("invalid".equals(StudyUpdateChannels.channel("3.1.0-canary.6-debug")), "reject debug");
        check("invalid".equals(StudyUpdateChannels.channel("canary")), "reject unversioned");

        check(StudyUpdateChannels.permits("3.0.0", "3.1.0", false),
                "stable -> newer stable");
        check(!StudyUpdateChannels.permits("3.0.0", "3.1.0-beta.1", true),
                "stable must not see beta");
        check(!StudyUpdateChannels.permits("3.0.0", "3.1.0-canary.7", true),
                "stable must not see canary");

        check(StudyUpdateChannels.permits("3.0.0-beta.2", "3.0.0-rc.1", true),
                "beta -> rc");
        check(StudyUpdateChannels.permits("3.0.0-beta.2", "3.1.0", false),
                "beta -> stable");
        check(!StudyUpdateChannels.permits("3.0.0-beta.2", "3.1.0-canary.7", true),
                "beta must not be upgraded to canary");

        check(StudyUpdateChannels.permits("3.1.0-canary.6", "3.1.0-canary.7", true),
                "canary -> newer canary");
        check(StudyUpdateChannels.permits("3.1.0-canary.6", "3.1.0-beta.1", true),
                "canary -> beta when versionCode increases");
        check(StudyUpdateChannels.permits("3.1.0-canary.6", "3.1.0", false),
                "canary -> stable when versionCode increases");

        check(!StudyUpdateChannels.permits("3.0.0", "3.1.0", true),
                "misclassified stable prerelease");
        check(!StudyUpdateChannels.permits("3.1.0-canary.6", "3.1.0-beta.1", false),
                "misclassified beta stable");
        check(!StudyUpdateChannels.permits("3.1.0-canary.6", "3.1.0-canary.7", false),
                "misclassified canary stable");
        check(!StudyUpdateChannels.permits("unknown", "3.1.0-canary.7", true),
                "unrecognized installed version is conservative");
        check(StudyUpdateChannels.permits("unknown", "3.1.0", false),
                "unrecognized installed version permits verified stable");
        System.out.println("STUDYONE_UPDATE_CHANNEL_POLICY_PASSED " + tests);
    }
}
