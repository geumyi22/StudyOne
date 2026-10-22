using System.IO;
using System.Text.Json;
using System.Globalization;

namespace StudyOne.Desktop;

/// <summary>Offline-only notebook; no API keys, accounts, progress or task schema are changed.</summary>
public sealed class StudyWrongAnswer
{
    public string Id { get; set; } = "";
    public string Subject { get; set; } = "";
    public string Material { get; set; } = "";
    public string Topic { get; set; } = "";
    public string Correction { get; set; } = "";
    public string NextReview { get; set; } = "";
    public int Retries { get; set; }
    public bool Mastered { get; set; }
}

public sealed class WrongAnswersBackup
{
    public string Format { get; set; } = "studyone-wrong-answers";
    public int Schema { get; set; } = 1;
    public List<StudyWrongAnswer> Items { get; set; } = new();
}

public static class StudyWrongAnswersData
{
    public static string PathName => Path.Combine(StudyData.DataDirectory, "wrong-answers.json");
    public static WrongAnswersBackup Load() =>
        File.Exists(PathName) ? Parse(StudyData.ReadLimited(PathName)) : new WrongAnswersBackup();

    public static StudyWrongAnswer Validate(StudyWrongAnswer item)
    {
        if (item is null || string.IsNullOrWhiteSpace(item.Id) ||
            !Guid.TryParseExact(item.Id, "D", out _) ||
            item.Id != item.Id.ToLowerInvariant() ||
            item.Subject is null || item.Subject.Length is < 1 or > 60 || item.Subject != item.Subject.Trim() ||
            item.Material is null || item.Material.Length is < 1 or > 120 || item.Material != item.Material.Trim() ||
            item.Topic is null || item.Topic.Length is < 1 or > 500 || item.Topic != item.Topic.Trim() ||
            item.Correction is null || item.Correction.Length is < 1 or > 1000 || item.Correction != item.Correction.Trim() ||
            item.Retries is < 0 or > 50 ||
            !DateOnly.TryParseExact(item.NextReview, "yyyy-MM-dd", CultureInfo.InvariantCulture,
                                    DateTimeStyles.None, out _))
            throw new InvalidDataException("오답의 필드, 길이, 복습일 또는 재풀이 횟수가 올바르지 않습니다.");
        return item;
    }

    public static WrongAnswersBackup Parse(byte[] bytes)
    {
        if (bytes.Length > 2_000_000) throw new InvalidDataException("오답 JSON은 2MB 이하이어야 합니다.");
        using JsonDocument doc = JsonDocument.Parse(bytes);
        JsonElement root = doc.RootElement;
        if (root.ValueKind != JsonValueKind.Object ||
            root.EnumerateObject().Count() != 3 ||
            !root.TryGetProperty("format", out var format) ||
            format.ValueKind != JsonValueKind.String ||
            format.GetString() != "studyone-wrong-answers" ||
            !root.TryGetProperty("schema", out var schema) ||
            schema.ValueKind != JsonValueKind.Number || schema.GetInt32() != 1 ||
            !root.TryGetProperty("items", out var list) ||
            list.ValueKind != JsonValueKind.Array || list.GetArrayLength() > 1000)
            throw new InvalidDataException("지원하지 않는 오답 JSON 형식입니다.");

        var required = new HashSet<string>(StringComparer.Ordinal) {
            "id", "subject", "material", "topic", "correction", "nextReview", "retries", "mastered"
        };
        foreach (var item in list.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object ||
                item.EnumerateObject().Count() != 8 ||
                !item.EnumerateObject().Select(x => x.Name).ToHashSet(StringComparer.Ordinal).SetEquals(required))
                throw new InvalidDataException("오답 JSON 필드가 올바르지 않습니다.");
        }
        WrongAnswersBackup parsed = JsonSerializer.Deserialize<WrongAnswersBackup>(bytes, StudyData.JsonOptions)
            ?? throw new InvalidDataException("오답 JSON을 읽지 못했습니다.");
        if (parsed.Format != "studyone-wrong-answers" || parsed.Schema != 1 ||
            parsed.Items is null || parsed.Items.Count > 1000)
            throw new InvalidDataException("오답 버전 오류");
        HashSet<string> ids = new(StringComparer.OrdinalIgnoreCase);
        foreach (var item in parsed.Items)
            if (!ids.Add(Validate(item).Id))
                throw new InvalidDataException("오답 ID가 중복되었습니다.");
        return parsed;
    }

    public static byte[] Serialize(WrongAnswersBackup value)
    {
        byte[] bytes = StudyData.Serialize(value);
        Parse(bytes);
        return bytes;
    }

    public static void Save(WrongAnswersBackup value) => StudyData.SaveAtomic(PathName, Serialize(value));

    /// <summary>Consent is collected in the UI. This method fails on stale source and backs up original bytes.</summary>
    public static string ImportWithBackup(byte[] incoming, byte[]? expected)
    {
        var parsed = Parse(incoming);
        byte[]? current = File.Exists(PathName) ? StudyData.ReadLimited(PathName) : null;
        if (current is not null) Parse(current);
        if ((current is null) != (expected is null) ||
            (current is not null && !current.AsSpan().SequenceEqual(expected!)))
            throw new InvalidDataException("승인 중 오답 기록이 변경됐습니다.");
        string folder = Path.Combine(StudyData.DataDirectory, "BeforeWrongAnswerImport");
        Directory.CreateDirectory(folder);
        string backup = Path.Combine(folder, "StudyOne-wrong-before-" + Guid.NewGuid().ToString("N") + ".bak");
        StudyData.SaveAtomic(backup, current ?? Array.Empty<byte>());
        Save(parsed);
        return backup;
    }
}
