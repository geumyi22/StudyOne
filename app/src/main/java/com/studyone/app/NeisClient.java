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

        HttpURLConnection c = (HttpURLConnection) new URL(q.toString()).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "StudyOne/2.0 Android");

        int status = c.getResponseCode();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) body.append(line);
        r.close();
        c.disconnect();

        if (status < 200 || status >= 300)
            throw new ApiException("HTTP-" + status, "NEIS 서버 HTTP 오류 " + status);
        return body.toString();
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
