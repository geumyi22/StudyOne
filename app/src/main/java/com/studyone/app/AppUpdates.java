package com.studyone.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * User-confirmed, signed, checksum-verified, in-place app updates.
 * Never silently installs APKs and never bundles GitHub auth/API secrets.
 */
public final class AppUpdates {
    private static final String RELEASES =
            "https://api.github.com/repos/geumyi22/StudyOne/releases?per_page=15";
    private static final String ASSET_BASE =
            "https://github.com/geumyi22/StudyOne/releases/download/";
    private static final String ASSET_METADATA = "studyone-update.json";
    private static final long AUTO_CHECK_DELAY = 12L * 60 * 60 * 1000;
    private static final int MAX_APK_BYTES = 70 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppUpdates() {}

    private static final class ReleaseInfo {
        int code;
        String name;
        String apkUrl;
        String hash;
        String description;
    }

    public static void check(Activity activity, boolean manual) {
        SharedPreferences prefs = activity.getSharedPreferences("studyone_update", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong("last_check", 0) < AUTO_CHECK_DELAY) return;
        prefs.edit().putLong("last_check", now).apply();
        if (manual) Toast.makeText(activity, "새 버전을 확인하는 중입니다.", Toast.LENGTH_SHORT).show();

        IO.execute(() -> {
            try {
                ReleaseInfo release = newestRelease(activity);
                MAIN.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (release == null) {
                        if (manual) new AlertDialog.Builder(activity)
                                .setTitle("StudyOne 업데이트")
                                .setMessage("설치된 버전이 최신이거나 검증 가능한 신규 릴리즈가 없습니다.")
                                .setPositiveButton("확인", null).show();
                        return;
                    }
                    new AlertDialog.Builder(activity)
                            .setTitle("StudyOne " + release.name)
                            .setMessage("새로운 버전이 있습니다.\n\n" + release.description
                                    + "\n\n다운로드 후 SHA-256과 앱 서명을 검증합니다."
                                    + "\n설치는 Android 시스템 확인 화면에서 직접 승인해야 합니다.")
                            .setNegativeButton("나중에", null)
                            .setPositiveButton("업데이트", (d, w) -> download(activity, release))
                            .show();
                });
            } catch (Exception e) {
                MAIN.post(() -> {
                    if (manual && !activity.isFinishing()) new AlertDialog.Builder(activity)
                            .setTitle("업데이트 확인 실패")
                            .setMessage("GitHub 릴리즈에 연결할 수 없습니다.\n네트워크 상태를 확인하거나 나중에 다시 시도하세요.")
                            .setPositiveButton("확인", null).show();
                });
            }
        });
    }

    private static ReleaseInfo newestRelease(Activity ctx) throws Exception {
        int current = installedVersion(ctx);
        String installedVersionName = ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), 0).versionName;
        boolean betaAllowed = installedVersionName != null
                && (installedVersionName.contains("beta") || installedVersionName.contains("rc"));
        JSONArray releases = new JSONArray(getText(RELEASES, 2_000_000));
        ReleaseInfo best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft", true)) continue;
            if (release.optBoolean("prerelease", false) && !betaAllowed) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;

            String metadataUrl = null;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject a = assets.getJSONObject(j);
                if (ASSET_METADATA.equals(a.optString("name"))) {
                    metadataUrl = a.optString("browser_download_url");
                    break;
                }
            }
            if (metadataUrl == null || !metadataUrl.startsWith(ASSET_BASE)) continue;
            JSONObject meta = new JSONObject(getText(metadataUrl, 30000));
            ReleaseInfo candidate = new ReleaseInfo();
            candidate.code = meta.getInt("versionCode");
            candidate.name = meta.getString("versionName");
            candidate.apkUrl = meta.getString("apkUrl");
            candidate.hash = meta.getString("sha256").toLowerCase(java.util.Locale.ROOT);
            candidate.description = meta.optString("notes", "안정성 개선 및 기능 업데이트");
            if (candidate.code <= current || candidate.hash.length() != 64 ||
                    !candidate.hash.matches("[a-f0-9]{64}") ||
                    !candidate.apkUrl.startsWith(ASSET_BASE)) continue;
            if (!releaseHasAsset(assets, candidate.apkUrl)) continue;
            if (best == null || candidate.code > best.code) best = candidate;
        }
        return best;
    }

    private static boolean releaseHasAsset(JSONArray assets, String url) throws Exception {
        for (int i = 0; i < assets.length(); i++) {
            if (url.equals(assets.getJSONObject(i).optString("browser_download_url"))) return true;
        }
        return false;
    }

    private static int installedVersion(Context ctx) throws Exception {
        PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        return (int) versionCode(info);
    }

    private static String getText(String url, int cap) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(18000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "StudyOne-Updater");
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP error");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = input.read(chunk)) != -1) {
                    if (out.size() + n > cap) throw new java.io.IOException("Too large");
                    out.write(chunk, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void download(Activity ctx, ReleaseInfo release) {
        Toast.makeText(ctx, "업데이트 APK를 다운로드하고 검증합니다.", Toast.LENGTH_LONG).show();
        IO.execute(() -> {
            File staging = null;
            try {
                File dir = new File(ctx.getFilesDir(), "studyone-updates");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Update folder");
                staging = new File(dir, "studyone.tmp");
                File output = new File(dir, "studyone.apk");

                HttpURLConnection connection =
                        (HttpURLConnection) new URL(release.apkUrl).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "StudyOne-Updater");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long size = 0;
                try {
                    if (connection.getResponseCode() != 200) throw new java.io.IOException("APK HTTP error");
                    try (InputStream in = connection.getInputStream();
                         FileOutputStream out = new FileOutputStream(staging, false)) {
                        byte[] data = new byte[16384];
                        int n;
                        while ((n = in.read(data)) != -1) {
                            size += n;
                            if (size > MAX_APK_BYTES) throw new java.io.IOException("APK over size limit");
                            out.write(data, 0, n);
                            digest.update(data, 0, n);
                        }
                        out.getFD().sync();
                    }
                } finally {
                    connection.disconnect();
                }
                if (size < 30000) throw new java.io.IOException("APK incomplete");
                String hash = toHex(digest.digest());
                if (!MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
                        release.hash.getBytes(StandardCharsets.US_ASCII))) {
                    throw new SecurityException("APK SHA-256 mismatch");
                }
                verifyApk(ctx, staging, release.code);
                if (output.exists() && !output.delete()) throw new java.io.IOException("Old update file");
                if (!staging.renameTo(output)) throw new java.io.IOException("Move update file");
                MAIN.post(() -> {
                    if (!ctx.isFinishing()) install(ctx);
                });
            } catch (Exception e) {
                if (staging != null) staging.delete();
                final boolean signatureProblem = e instanceof SecurityException;
                MAIN.post(() -> {
                    if (ctx.isFinishing()) return;
                    String message = signatureProblem
                            ? "APK 파일의 무결성 또는 서명이 설치된 앱과 일치하지 않습니다."
                             + "\n\n이전 임시 디버그판에서 고정 서명 배포판으로 처음 전환하는 경우,"
                             + " 과제를 먼저 JSON으로 백업하고 한 번 교체 설치해야 합니다."
                            : "다운로드 또는 검사에 실패했습니다. 인터넷 연결과 저장공간을 확인해 주세요.";
                    new AlertDialog.Builder(ctx)
                            .setTitle("업데이트를 설치할 수 없습니다.")
                            .setMessage(message)
                            .setPositiveButton("확인", null).show();
                });
            }
        });
    }

    private static void verifyApk(Context ctx, File apk, int expectedCode) throws Exception {
        PackageManager manager = ctx.getPackageManager();
        PackageInfo installed = manager.getPackageInfo(
                ctx.getPackageName(), signingFlags());
        PackageInfo candidate = manager.getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null)
            throw new SecurityException("No verified signing data");
        if (!ctx.getPackageName().equals(candidate.packageName))
            throw new SecurityException("Wrong app package");
        if (versionCode(candidate) != expectedCode
                || versionCode(candidate) <= versionCode(installed))
            throw new SecurityException("Wrong version");
        Signature[] current = signatures(installed);
        Signature[] newer = signatures(candidate);
        if (current == null || newer == null || current.length == 0 ||
                current.length != newer.length) throw new SecurityException("Signer mismatch");
        for (Signature a : current) {
            boolean match = false;
            for (Signature b : newer) if (a.equals(b)) match = true;
            if (!match) throw new SecurityException("Signer mismatch");
        }
    }


    private static int signingFlags() {
        if (Build.VERSION.SDK_INT >= 28) return PackageManager.GET_SIGNING_CERTIFICATES;
        return PackageManager.GET_SIGNATURES;
    }

    private static long versionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
        return info.versionCode;
    }

    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) return null;
            return info.signingInfo.getApkContentsSigners();
        }
        return info.signatures;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private static void install(Activity ctx) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(ctx)
                    .setTitle("설치 권한 필요")
                    .setMessage("Android 보안 정책에 따라 StudyOne의 '이 출처 허용'을 한 번 켜야 합니다."
                            + "\n설정에서 허용한 다음 '업데이트 설치'를 다시 눌러주세요.")
                    .setPositiveButton("설정 열기", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + ctx.getPackageName()));
                        ctx.startActivity(intent);
                    })
                    .setNegativeButton("취소", null).show();
            return;
        }
        try {
            File file = new File(new File(ctx.getFilesDir(),"studyone-updates"),"studyone.apk");
            if (!file.exists()) throw new java.io.IOException("No verified APK");
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(Uri.parse("content://" + ctx.getPackageName() +
                            ".updatefiles/apk"), "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(install);
        } catch (Exception ex) {
            new AlertDialog.Builder(ctx)
                    .setTitle("Android 설치 화면을 열 수 없습니다")
                    .setMessage("업데이트 파일을 다시 내려받거나 설치 권한을 확인하세요.")
                    .setPositiveButton("확인", null).show();
        }
    }

    public static void retryVerifiedInstall(Activity activity) {
        File target = new File(new File(activity.getFilesDir(), "studyone-updates"),"studyone.apk");
        if (!target.exists()) {
            check(activity, true);
            return;
        }
        try {
            // Re-verify against the currently installed package before using cached APK.
            PackageInfo candidate = activity.getPackageManager().getPackageArchiveInfo(
                    target.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (candidate == null) throw new SecurityException();
            verifyApk(activity, target, (int)versionCode(candidate));
            install(activity);
        } catch (Exception e) {
            check(activity, true);
        }
    }
}
