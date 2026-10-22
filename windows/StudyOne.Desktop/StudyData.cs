using System.IO;
using System.Globalization;
using System.Text.Json;

namespace StudyOne.Desktop;

public sealed class StudyTask
{
    public long Id { get; set; }
    public string Type { get; set; } = "숙제 항목";
    public string Subject { get; set; } = "";
    public string Title { get; set; } = "";
    public string DueDate { get; set; } = "";
    public int Importance { get; set; } = 2;
    public bool Completed { get; set; }
}

public sealed class SchoolRecord
{
    public string EduCode { get; set; } = "";
    public string SchoolCode { get; set; } = "";
    public string Name { get; set; } = "";
    public string Kind { get; set; } = "";
    public string Address { get; set; } = "";
}

public sealed class TaskBackup
{
    public string Format { get; set; } = "studyone-backup";
    public int Schema { get; set; } = 1;
    public string ExportedAt { get; set; } = DateTime.Today.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    public int Grade { get; set; } = 1;
    public string ClassName { get; set; } = "1";
    public SchoolRecord? School { get; set; }
    public List<StudyTask> Tasks { get; set; } = new();
}

public sealed class StudyProgressItem
{
    public string Id { get; set; } = "";
    public string Subject { get; set; } = "";
    public string Material { get; set; } = "";
    public string Unit { get; set; } = "";
    public string Status { get; set; } = "미완료";
    [System.Text.Json.Serialization.JsonIgnore]
    public bool IsDone => Status == "완료";
    public bool InExamScope { get; set; } = true;
    public long Revision { get; set; } = 1;
    public long UpdatedAt { get; set; }
}

public sealed class ProgressBackup
{
    public string Format { get; set; } = "studyone-progress";
    public int Schema { get; set; } = 1;
    public List<StudyProgressItem> Items { get; set; } = new();
}

public static class StudyData
{
    public const int MaximumBytes = 2_000_000;
    public static readonly string[] Statuses = { "확인 필요", "미완료", "진행 중", "완료" };
    public static readonly string[] Categories = {
        "개별 항목 (전날 못 다)", "정리 및 복습 항목", "수행 및 암기 항목",
        "숙제 항목", "기타 항목", "과제", "수행평가", "시험", "준비물"
    };

    public static string DataDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "StudyOneCanary");

    public static string TasksPath => Path.Combine(DataDirectory, "tasks.json");
    public static string ProgressPath => Path.Combine(DataDirectory, "progress.json");

    public static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true
    };

    public static TaskBackup LoadTasks() =>
        File.Exists(TasksPath) ? ParseTasks(ReadLimited(TasksPath)) : new TaskBackup();

    public static ProgressBackup LoadProgress() =>
        File.Exists(ProgressPath) ? ParseProgress(ReadLimited(ProgressPath)) : new ProgressBackup();

    public static byte[] ReadLimited(string path)
    {
        if (new FileInfo(path).Length > MaximumBytes)
            throw new InvalidDataException("StudyOne JSON 파일 크기는 최대 2MB입니다.");
        return File.ReadAllBytes(path);
    }

    public static TaskBackup ParseTasks(byte[] data)
    {
        if (data.Length > MaximumBytes) throw new InvalidDataException("일정 백업 파일이 너무 큽니다.");
        TaskBackup value = JsonSerializer.Deserialize<TaskBackup>(data, JsonOptions)
                           ?? throw new InvalidDataException("일정 백업을 읽을 수 없습니다.");
        if (value.Format != "studyone-backup" || value.Schema != 1 ||
            value.Grade is < 1 or > 6 || string.IsNullOrEmpty(value.ClassName) ||
            value.ClassName.Length > 3 || !value.ClassName.All(ch => ch >= '0' && ch <= '9') ||
            value.Tasks is null || value.Tasks.Count > 5000)
            throw new InvalidDataException("지원되지 않는 StudyOne 일정 백업입니다.");
        if (value.School != null && (string.IsNullOrEmpty(value.School.EduCode) ||
                                    string.IsNullOrEmpty(value.School.SchoolCode)))
            throw new InvalidDataException("학교 정보가 올바르지 않습니다.");
        HashSet<long> taskIds = new();
        foreach (StudyTask task in value.Tasks)
        {
            if (task is null || string.IsNullOrWhiteSpace(task.Title) || task.Title.Length > 500 ||
                task.Type is null || task.Subject is null || task.Importance is < 1 or > 3 ||
                !DateOnly.TryParseExact(task.DueDate, "yyyy-MM-dd", CultureInfo.InvariantCulture,
                                        DateTimeStyles.None, out _) || !taskIds.Add(task.Id))
                throw new InvalidDataException("손상되거나 중복된 학습 일정이 있습니다.");
        }
        return value;
    }

    public static ProgressBackup ParseProgress(byte[] data)
    {
        if (data.Length > MaximumBytes) throw new InvalidDataException("진도 백업 파일이 너무 큽니다.");
        ProgressBackup value = JsonSerializer.Deserialize<ProgressBackup>(data, JsonOptions)
                               ?? throw new InvalidDataException("진도 백업을 읽을 수 없습니다.");
        if (value.Format != "studyone-progress" || value.Schema != 1 ||
            value.Items is null || value.Items.Count > 2000)
            throw new InvalidDataException("지원되지 않는 StudyOne 진도 백업입니다.");
        HashSet<string> ids = new(StringComparer.OrdinalIgnoreCase);
        foreach (StudyProgressItem item in value.Items)
        {
            if (item is null || !Guid.TryParse(item.Id, out _) || !ids.Add(item.Id) ||
                string.IsNullOrWhiteSpace(item.Subject) || item.Subject.Length > 60 ||
                string.IsNullOrWhiteSpace(item.Material) || item.Material.Length > 120 ||
                string.IsNullOrWhiteSpace(item.Unit) || item.Unit.Length > 200 ||
                !Statuses.Contains(item.Status) ||
                item.Revision < 1 || item.UpdatedAt < 0)
                throw new InvalidDataException("손상되거나 중복된 진도 항목이 있습니다.");
        }
        return value;
    }

    public static byte[] Serialize<T>(T value) => JsonSerializer.SerializeToUtf8Bytes(value, JsonOptions);

    public static void SaveTasks(TaskBackup backup) => SaveAtomic(TasksPath, SerializeAndValidateTasks(backup));

    public static void SaveProgress(ProgressBackup backup) =>
        SaveAtomic(ProgressPath, SerializeAndValidateProgress(backup));

    public static byte[] SerializeAndValidateTasks(TaskBackup backup)
    {
        byte[] bytes = Serialize(backup);
        ParseTasks(bytes);
        return bytes;
    }

    public static byte[] SerializeAndValidateProgress(ProgressBackup backup)
    {
        byte[] bytes = Serialize(backup);
        ParseProgress(bytes);
        return bytes;
    }

    public static void SaveAtomic(string path, byte[] data)
    {
        if (data.Length > MaximumBytes) throw new InvalidDataException("백업 크기 제한을 초과했습니다.");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        string temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllBytes(temporary, data);
            File.Move(temporary, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    public static long NowMillis() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
