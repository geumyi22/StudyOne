package com.studyone.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public Android update channel policy. No network or Android dependencies.
 *
 * Android applicationId and signing certificate stay identical across
 * signed stable/beta/canary; only versionName and update visibility differ.
 * Never allow a Beta user to be silently offered a Canary build.
 */
public final class StudyUpdateChannels {
    public static final String STABLE = "stable";
    public static final String BETA = "beta";
    public static final String CANARY = "canary";
    public static final String INVALID = "invalid";

    private static final Pattern VERSION = Pattern.compile(
            "^[0-9]+\\.[0-9]+\\.[0-9]+(?:-(beta|rc|canary)\\.[0-9]+)?$");

    private StudyUpdateChannels() {}

    public static String channel(String versionName) {
        if (versionName == null) return INVALID;
        Matcher matcher = VERSION.matcher(versionName.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) return INVALID;
        String suffix = matcher.group(1);
        if (suffix == null) return STABLE;
        return "canary".equals(suffix) ? CANARY : BETA;
    }

    /**
     * This method does not decide version ordering or verify APK signatures.
     * Callers MUST also require increasing versionCode, exact package match,
     * expected release tag/asset, content checksum and signing identity.
     */
    public static boolean permits(String installedVersionName,
                                  String candidateVersionName,
                                  boolean candidatePrerelease) {
        String installed = channel(installedVersionName);
        String candidate = channel(candidateVersionName);
        if (INVALID.equals(candidate)) return false;
        // A stable version cannot be mislabeled as a GitHub prerelease,
        // and a beta or canary cannot be published as an ordinary stable release.
        if (STABLE.equals(candidate) == candidatePrerelease) return false;
        if (CANARY.equals(installed)) return true;
        if (BETA.equals(installed)) return !CANARY.equals(candidate);
        // Unknown/legacy installed versions are treated conservatively.
        return STABLE.equals(candidate);
    }
}
