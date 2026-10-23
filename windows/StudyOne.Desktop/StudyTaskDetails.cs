using System.IO;
using System.Text;
using System.Text.Json;

namespace StudyOne.Desktop;

/// <summary>Optional local-only task guidance; never part of the legacy task/sync payload.</summary>
public sealed class StudyTaskDetail
{
    public long TaskId { get; set; }
    public int? EstimatedMinutes { get; set; }
    public List<string> Steps { get; set; } = new();
    public string CompletionCriteria { get; set; } = "";

    [System.Text.Json.Serialization.JsonIgnore]
    public bool IsEmpty => EstimatedMinutes is null && Steps.Count == 0 && CompletionCriteria.Length == 0;

    public string DisplayText()
    {
        List<string> lines = new();
        if (EstimatedMinutes is int duration) lines.Add($"예상 {duration}분");
        for (int i = 0; i < Steps.Count; i++) lines.Add($"{i + 1}. {Steps[i]}");
        if (CompletionCriteria.Length > 0) lines.Add("완료 기준: " + CompletionCriteria);
        return string.Join("\n", lines);
    }
}

public sealed class TaskDetailsBackup
{
    public string Format { get; set; } = "studyone-task-details";
    public int Schema { get; set; } = 1;
    public List<StudyTaskDetail> Items { get; set; } = new();
}

/// <summary>Android/iOS-compatible isolated JSON transfer and protected replacement.</summary>
public static class StudyTaskDetailsData
{
    public static string PathName => Path.Combine(StudyData.DataDirectory, "task-details.json");

    public static TaskDetailsBackup Load() =>
        File.Exists(PathName) ? Parse(StudyData.ReadLimited(PathName)) : new TaskDetailsBackup();

    public static StudyTaskDetail Validate(StudyTaskDetail item)
    {
        if (item is null || item.TaskId <= 0 ||
            item.EstimatedMinutes is < 1 or > 600 ||
            item.Steps is null || item.Steps.Count > 20 ||
            item.CompletionCriteria is null || item.CompletionCriteria.Length > 500 ||
            item.CompletionCriteria != item.CompletionCriteria.Trim() ||
            item.Steps.Any(s => string.IsNullOrWhiteSpace(s) || s.Length > 200 || s != s.Trim()))
            throw new InvalidDataException("학습 상세 필드·시간·단계·완료 기준이 올바르지 않습니다.");
        return item;
    }

    public static TaskDetailsBackup Parse(byte[] bytes)
    {
        if (bytes.Length > StudyData.MaximumBytes)
            throw new InvalidDataException("학습 상세 백업은 최대 2MB입니다.");
        using JsonDocument doc = JsonDocument.Parse(bytes);
        JsonElement root = doc.RootElement;
        if (root.ValueKind != JsonValueKind.Object ||
            root.EnumerateObject().Count() != 3 ||
            !root.TryGetProperty("format", out var format) ||
            format.ValueKind != JsonValueKind.String ||
            format.GetString() != "studyone-task-details" ||
            !root.TryGetProperty("schema", out var schema) ||
            schema.ValueKind != JsonValueKind.Number || schema.GetInt32() != 1 ||
            !root.TryGetProperty("items", out var array) ||
            array.ValueKind != JsonValueKind.Array || array.GetArrayLength() > 5000)
            throw new InvalidDataException("지원하지 않거나 손상된 학습 상세 백업입니다.");

        foreach (JsonElement record in array.EnumerateArray())
        {
            if (record.ValueKind != JsonValueKind.Object ||
                record.EnumerateObject().Count() != 4 ||
                !record.TryGetProperty("taskId", out var id) ||
                id.ValueKind != JsonValueKind.Number ||
                !record.TryGetProperty("estimatedMinutes", out var minutes) ||
                (minutes.ValueKind != JsonValueKind.Null && minutes.ValueKind != JsonValueKind.Number) ||
                !record.TryGetProperty("steps", out var steps) ||
                steps.ValueKind != JsonValueKind.Array ||
                !record.TryGetProperty("completionCriteria", out var criteria) ||
                criteria.ValueKind != JsonValueKind.String)
                throw new InvalidDataException("학습 상세 필드 형식이 올바르지 않습니다.");
        }
        TaskDetailsBackup value = JsonSerializer.Deserialize<TaskDetailsBackup>(bytes, StudyData.JsonOptions)
            ?? throw new InvalidDataException("학습 상세 백업을 읽을 수 없습니다.");
        HashSet<long> ids = new();
        if (value.Format != "studyone-task-details" || value.Schema != 1 ||
            value.Items is null || value.Items.Count > 5000)
            throw new InvalidDataException("지원되지 않는 학습 상세 백업입니다.");
        foreach (var item in value.Items)
            if (!ids.Add(Validate(item).TaskId) || item.IsEmpty)
                throw new InvalidDataException("학습 상세 ID 중복 또는 빈 항목입니다.");
        return value;
    }

    public static byte[] Serialize(TaskDetailsBackup value)
    {
        byte[] bytes = StudyData.Serialize(value);
        Parse(bytes);
        return bytes;
    }

    public static void Save(TaskDetailsBackup value) =>
        StudyData.SaveAtomic(PathName, Serialize(value));

    /// <summary>Backup exact original bytes first and refuse stale or corrupt local data.</summary>
    public static string ImportWithBackup(byte[] incoming, byte[]? expectedOriginal)
    {
        TaskDetailsBackup parsed = Parse(incoming);
        byte[]? original = File.Exists(PathName) ? StudyData.ReadLimited(PathName) : null;
        if (original is not null) Parse(original);
        if ((original is null) != (expectedOriginal is null) ||
            (original is not null && !original.AsSpan().SequenceEqual(expectedOriginal!)))
            throw new InvalidDataException("확인하는 동안 상세 원본이 변경됐습니다. 다시 가져오세요.");

        string folder = Path.Combine(StudyData.DataDirectory, "BeforeTaskDetailsImport");
        Directory.CreateDirectory(folder);
        string backup = Path.Combine(folder, "StudyOne-details-before-import-" + Guid.NewGuid().ToString("N") + ".bak");
        StudyData.SaveAtomic(backup, original ?? Array.Empty<byte>());
        Save(parsed);
        return backup;
    }
}
