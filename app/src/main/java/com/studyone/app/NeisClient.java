package com.studyone.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NeisClient {
    private static final String BASE = "https://open.neis.go.kr/hub/";
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static final class ApiException extends Exception {
        public final String code;
        public ApiException(String code, String message) { super(message); this.code = code; }
    }

    private String request(String service, Map<String, String> params) throws Exception {
        StringBuilder q = new StringBuilder(BASE).append(service)
                .append("?Type=json&pIndex=1&pSize=100");
        for (Map.Entry<String, String> e : params.entrySet()) {
            String v = e.getValue();
            if (v == null || v.trim().isEmpty()) continue;
            q.append("&").append(enc(e.getKey())).append("=").append(enc(v));
        }

        // Do not print the URL: it may contain the user's private NEIS API key.
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(q.toString()).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(12000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("User-Agent", "StudyOne/2.0.2 Android");

                int status = connection.getResponseCode();
                java.io.InputStream input = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String body = "";
                if (input != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(input, StandardCharsets.UTF_8))) {
                        StringBuilder out = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null && out.length() < 65536) {
                            out.append(line);
                        }
                        body = out.toString();
                    }
                }
                if (status >= 200 && status < 300) {
                    if (body.trim().isEmpty()) {
                        throw new ApiException("EMPTY", "NEIS 서버가 빈 응답을 보냈습니다.");
                    }
                    return body;
                }

                if (status >= 500 && status <= 504 && attempt == 0) {
                    try { Thread.sleep(650); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ApiException("CANCELLED", "조회가 중단되었습니다.");
                    }
                    continue;
                }
                String detail = neisErrorSummary(body);
                if (status == 429) {
                    throw new ApiException("HTTP-429", "조회 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
                }
                if (status >= 500) {
                    throw new ApiException("HTTP-" + status,
                            "NEIS 서버가 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
                                    + detail + "\n학교 검색은 인증키 없이 제한된 샘플 조회도 가능합니다.");
                }
                throw new ApiException("HTTP-" + status,
                        "NEIS 연결이 거부되었습니다. 인증키 및 API 사용 권한을 확인해 주세요." + detail);
            } catch (java.net.SocketTimeoutException ex) {
                if (attempt == 1) throw new ApiException("TIMEOUT", "NEIS 응답 시간이 초과됐습니다. 잠시 후 다시 시도해 주세요.");
            } catch (java.io.IOException ex) {
                if (attempt == 1) throw new ApiException("NETWORK",
                        "인터넷 또는 NEIS 서버 연결에 실패했습니다. 네트워크 연결을 확인해 주세요.");
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new ApiException("RETRY", "NEIS 재시도 후에도 요청이 실패했습니다.");
    }

    private String neisErrorSummary(String body) {
        if (body == null || body.trim().isEmpty()) return "";
        try {
            JSONObject root = new JSONObject(body);
            JSONObject result = root.optJSONObject("RESULT");
            if (result != null) {
                String code = result.optString("CODE", "");
                String msg = result.optString("MESSAGE", "");
                if (!code.isEmpty()) return "\nNEIS 코드: " + code + (msg.isEmpty() ? "" : "\n" + msg);
            }
        } catch (Exception ignored) {
            // HTML error pages are not shown: they can include gateway diagnostics.
        }
        return "";
    }

    public static boolean canTrySampleSearch(Exception e) {
        if (!(e instanceof ApiException)) return false;
        String code = ((ApiException)e).code;
        return code.startsWith("HTTP-5") || code.equals("ERROR-500")
                || code.equals("ERROR-600") || code.equals("ERROR-601");
    }

    private String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private JSONArray rows(String service, String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject result = root.optJSONObject("RESULT");
        if (result != null) {
            String code = result.optString("CODE", "UNKNOWN");
            String msg = result.optString("MESSAGE", "NEIS 오류");
            if ("INFO-200".equals(code)) return new JSONArray();
            throw new ApiException(code, msg);
        }

        JSONArray svc = root.optJSONArray(service);
        if (svc == null)
            throw new ApiException("PARSE", "NEIS 응답에 " + service + " 데이터가 없습니다.");
        JSONObject second = svc.optJSONObject(1);
        if (second == null) return new JSONArray();
        JSONArray row = second.optJSONArray("row");
        return row == null ? new JSONArray() : row;
    }

    public List<Models.School> searchSchools(String apiKey, String name) throws Exception {
        if (name == null || name.trim().length() < 2)
            throw new ApiException("INPUT", "학교 이름을 2글자 이상 입력하세요.");

        Map<String, String> p = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.trim().isEmpty()) p.put("KEY", apiKey.trim());
        p.put("SCHUL_NM", name.trim());

        JSONArray a = rows("schoolInfo", request("schoolInfo", p));
        List<Models.School> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            out.add(new Models.School(
                    o.optString("ATPT_OFCDC_SC_CODE"),
                    o.optString("SD_SCHUL_CODE"),
                    o.optString("SCHUL_NM"),
                    o.optString("SCHUL_KND_SC_NM"),
                    o.optString("ORG_RDNMA")
            ));
        }
        return out;
    }

    private String timetableService(String kind) {
        if (kind.contains("초등")) return "elsTimetable";
        if (kind.contains("중학교")) return "misTimetable";
        if (kind.contains("고등")) return "hisTimetable";
        if (kind.contains("특수")) return "spsTimetable";
        return "misTimetable";
    }

    public List<Models.TimetableEntry> fetchTimetable(
            String apiKey, Models.School school, int grade, String className,
            LocalDate from, LocalDate to) throws Exception {
        requireConfigured(apiKey, school);

        String service = timetableService(school.kind);
        Map<String, String> p = common(apiKey, school);
        p.put("AY", String.valueOf(academicYear(from)));
        p.put("SEM", String.valueOf(semester(from)));
        p.put("GRADE", String.valueOf(grade));
        p.put("CLASS_NM", className);
        p.put("TI_FROM_YMD", from.format(YMD));
        p.put("TI_TO_YMD", to.format(YMD));

        JSONArray a = rows(service, request(service, p));
        List<Models.TimetableEntry> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);

            if (!school.eduCode.equals(o.optString("ATPT_OFCDC_SC_CODE"))) continue;
            if (!school.schoolCode.equals(o.optString("SD_SCHUL_CODE"))) continue;
            if (!String.valueOf(grade).equals(o.optString("GRADE"))) continue;
            if (!normalizeClass(className).equals(normalizeClass(o.optString("CLASS_NM")))) continue;

            String day = o.optString("ALL_TI_YMD");
            if (day.length() != 8) continue;
            LocalDate date = LocalDate.parse(day, YMD);
            if (date.isBefore(from) || date.isAfter(to)) continue;

            int period;
            try { period = Integer.parseInt(o.optString("PERIO", "0")); }
            catch (Exception e) { continue; }
            String subject = o.optString("ITRT_CNTNT", "").trim();
            if (period > 0 && !subject.isEmpty())
                out.add(new Models.TimetableEntry(date, period, subject));
        }
        out.sort(Comparator.comparing((Models.TimetableEntry x) -> x.date)
                .thenComparingInt(x -> x.period));
        return out;
    }

    public List<Models.Meal> fetchMeals(
            String apiKey, Models.School school, LocalDate from, LocalDate to) throws Exception {
        requireConfigured(apiKey, school);

        Map<String, String> p = common(apiKey, school);
        p.put("MMEAL_SC_CODE", "2");
        p.put("MLSV_FROM_YMD", from.format(YMD));
        p.put("MLSV_TO_YMD", to.format(YMD));

        JSONArray a = rows("mealServiceDietInfo", request("mealServiceDietInfo", p));
        List<Models.Meal> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (!school.eduCode.equals(o.optString("ATPT_OFCDC_SC_CODE"))) continue;
            if (!school.schoolCode.equals(o.optString("SD_SCHUL_CODE"))) continue;
            if (!"2".equals(o.optString("MMEAL_SC_CODE"))) continue;

            String ymd = o.optString("MLSV_YMD");
            if (ymd.length() != 8) continue;
            LocalDate date = LocalDate.parse(ymd, YMD);
            if (date.isBefore(from) || date.isAfter(to)) continue;

            String menu = o.optString("DDISH_NM", "")
                    .replace("<br/>", "\n").replace("<br>", "\n")
                    .replace("&amp;", "&").trim();
            out.add(new Models.Meal(date, menu, o.optString("CAL_INFO", "")));
        }
        out.sort(Comparator.comparing(x -> x.date));
        return out;
    }

    private void requireConfigured(String apiKey, Models.School school) throws ApiException {
        if (school == null) throw new ApiException("CONFIG", "학교를 먼저 설정하세요.");
        if (apiKey == null || apiKey.trim().isEmpty())
            throw new ApiException("KEY", "정확한 조회를 위해 NEIS 인증키를 입력하세요.");
    }

    private Map<String, String> common(String apiKey, Models.School school) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("KEY", apiKey.trim());
        p.put("ATPT_OFCDC_SC_CODE", school.eduCode);
        p.put("SD_SCHUL_CODE", school.schoolCode);
        return p;
    }

    private int academicYear(LocalDate d) {
        return d.getMonthValue() >= 3 ? d.getYear() : d.getYear() - 1;
    }

    private int semester(LocalDate d) {
        int m = d.getMonthValue();
        return (m >= 3 && m <= 7) ? 1 : 2;
    }

    private String normalizeClass(String s) {
        String x = s == null ? "" : s.trim();
        try { return String.valueOf(Integer.parseInt(x)); }
        catch (Exception e) { return x; }
    }
}
