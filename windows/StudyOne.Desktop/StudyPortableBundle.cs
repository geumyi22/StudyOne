using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace StudyOne.Desktop;

/// <summary>
/// Windows implementation of Android StudyPortableBundle's schema-1 four-store
/// wire format. No credentials or hidden local settings are included.
/// Four files cannot be replaced atomically: recovery journals provide an
/// explicit restart repair path, not an ACID transaction guarantee.
/// </summary>
public static class StudyPortableBundle
{
    public const string Format = "studyone-transfer-bundle";
    private const int Limit = 8_200_000;
    private static readonly string[] Keys = { "tasks", "progress", "details", "wrongAnswers" };
    private static readonly string[] Filenames = {
        "tasks.json", "progress.json", "task-details.json", "wrong-answers.json"
    };

    public sealed record Snapshot(byte[]? Tasks, byte[]? Progress, byte[]? Details,
                                  byte[]? WrongAnswers)
    {
        public byte[]?[] Values => new[] { Tasks, Progress, Details, WrongAnswers };
        public bool Same(Snapshot other) =>
            Values.Zip(other.Values).All(pair =>
                pair.First is null ? pair.Second is null :
                pair.Second is not null && pair.First.SequenceEqual(pair.Second));
    }

    public sealed record Checked(Snapshot Original, TaskBackup Tasks,
        ProgressBackup Progress, TaskDetailsBackup Details, WrongAnswersBackup Wrong)
    {
        public string Summary => $"일정 {Tasks.Tasks.Count}개 · 진도 {Progress.Items.Count}개"
            + $" · 학습 상세 {Details.Items.Count}개 · 오답 {Wrong.Items.Count}개";
    }

    private static string Digest(byte[] data) =>
        Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();

    private static byte[] RequireBytes(byte[]? data, int index) => data ??
        (index switch {
            0 => StudyData.SerializeAndValidateTasks(new TaskBackup()),
            1 => StudyData.SerializeAndValidateProgress(new ProgressBackup()),
            2 => StudyTaskDetailsData.Serialize(new TaskDetailsBackup()),
            3 => StudyWrongAnswersData.Serialize(new WrongAnswersBackup()),
            _ => throw new InvalidDataException("알 수 없는 백업 항목")
        });

    private static Checked Validate(Snapshot raw)
    {
        var values = raw.Values.Select(RequireBytes).ToArray();
        return new Checked(raw, StudyData.ParseTasks(values[0]),
            StudyData.ParseProgress(values[1]), StudyTaskDetailsData.Parse(values[2]),
            StudyWrongAnswersData.Parse(values[3]));
    }

    public static Snapshot Capture(string directory)
    {
        byte[]?[] values = Filenames.Select(name => {
            string path = Path.Combine(directory, name);
            return File.Exists(path) ? StudyData.ReadLimited(path) : null;
        }).ToArray();
        Snapshot result = new(values[0], values[1], values[2], values[3]);
        Validate(result); // Corruption blocks export/import instead of silently erasing it.
        return result;
    }

    public static string Encode(Snapshot raw)
    {
        Checked valid = Validate(raw);
        var values = valid.Original.Values.Select(RequireBytes).ToArray();
        var sections = new Dictionary<string, string>(StringComparer.Ordinal);
        var hashes = new Dictionary<string, string>(StringComparer.Ordinal);
        for (int i = 0; i < Keys.Length; i++)
        {
            sections.Add(Keys[i], Encoding.UTF8.GetString(values[i]));
            hashes.Add(Keys[i], Digest(values[i]));
        }
        var document = new { format = Format, schema = 1, sections, sha256 = hashes };
        string encoded = JsonSerializer.Serialize(document, StudyData.JsonOptions);
        if (Encoding.UTF8.GetByteCount(encoded) > Limit)
            throw new InvalidDataException("통합 백업은 8.2MB 이하이어야 합니다.");
        return encoded;
    }

    public static Checked Parse(string encoded)
    {
        if (encoded is null || Encoding.UTF8.GetByteCount(encoded) > Limit)
            throw new InvalidDataException("통합 백업의 크기가 올바르지 않습니다.");
        using JsonDocument doc = JsonDocument.Parse(encoded);
        JsonElement root = doc.RootElement;
        if (root.ValueKind != JsonValueKind.Object ||
            root.EnumerateObject().Count() != 4 ||
            !root.TryGetProperty("format", out var format) ||
            format.ValueKind != JsonValueKind.String || format.GetString() != Format ||
            !root.TryGetProperty("schema", out var schema) ||
            schema.ValueKind != JsonValueKind.Number || schema.GetInt32() != 1 ||
            !root.TryGetProperty("sections", out var sections) ||
            sections.ValueKind != JsonValueKind.Object ||
            !root.TryGetProperty("sha256", out var checksums) ||
            checksums.ValueKind != JsonValueKind.Object ||
            sections.EnumerateObject().Count() != 4 ||
            checksums.EnumerateObject().Count() != 4)
            throw new InvalidDataException("지원하지 않는 4종 통합 백업입니다.");

        byte[][] bytes = new byte[4][];
        for (int i = 0; i < Keys.Length; i++)
        {
            if (!sections.TryGetProperty(Keys[i], out var section) ||
                !checksums.TryGetProperty(Keys[i], out var checksum) ||
                section.ValueKind != JsonValueKind.String ||
                checksum.ValueKind != JsonValueKind.String)
                throw new InvalidDataException("통합 백업의 필수 데이터가 누락됐습니다.");
            bytes[i] = Encoding.UTF8.GetBytes(section.GetString()!);
            if (bytes[i].Length > StudyData.MaximumBytes ||
                !string.Equals(Digest(bytes[i]), checksum.GetString(), StringComparison.Ordinal))
                throw new InvalidDataException("통합 백업 SHA-256이 일치하지 않습니다.");
        }
        // Verify exact property sets (unknown keys or duplicates fail closed).
        foreach (JsonElement obj in new[] { sections, checksums })
        {
            if (obj.EnumerateObject().Select(p => p.Name).Distinct().Count() != 4 ||
                obj.EnumerateObject().Any(p => !Keys.Contains(p.Name)))
                throw new InvalidDataException("통합 백업에 알 수 없는 필드가 있습니다.");
        }
        return Validate(new Snapshot(bytes[0], bytes[1], bytes[2], bytes[3]));
    }

    private static string CreateRecovery(string directory, Snapshot original)
    {
        string folder = Path.Combine(directory, "Recovery",
            "StudyOne-fourstore-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        Dictionary<string, string?> index = new(StringComparer.Ordinal);
        for (int i = 0; i < Keys.Length; i++)
        {
            byte[]? originalBytes = original.Values[i];
            index[Keys[i]] = originalBytes is null ? null : Digest(originalBytes);
            if (originalBytes is not null)
                StudyData.SaveAtomic(Path.Combine(folder, Keys[i] + ".bak"), originalBytes);
        }
        StudyData.SaveAtomic(Path.Combine(folder, "manifest.json"),
            JsonSerializer.SerializeToUtf8Bytes(index));
        // A marker appears only after every original section has been backed up.
        StudyData.SaveAtomic(Path.Combine(folder, ".pending"), Encoding.UTF8.GetBytes("1"));
        return folder;
    }

    public static IReadOnlyList<string> Pending(string directory)
    {
        string parent = Path.Combine(directory, "Recovery");
        if (!Directory.Exists(parent)) return Array.Empty<string>();
        return Directory.GetDirectories(parent, "StudyOne-fourstore-*")
            .Where(path => File.Exists(Path.Combine(path, ".pending")))
            .OrderBy(path => path, StringComparer.Ordinal).ToArray();
    }

    /// <summary>
    /// Restores all four ORIGINALS from a prepared journal. The caller must
    /// explicitly obtain user approval to recover an interrupted transaction.
    /// Complete integrity verification happens BEFORE any live file is changed.
    /// </summary>
    public static void Recover(string folder, string directory)
    {
        string parent = Path.GetFullPath(Path.Combine(directory, "Recovery")) +
            Path.DirectorySeparatorChar;
        if (!Path.GetFullPath(folder).StartsWith(parent, StringComparison.OrdinalIgnoreCase)
            || !Path.GetFileName(folder).StartsWith("StudyOne-fourstore-", StringComparison.Ordinal)
            || !File.Exists(Path.Combine(folder, ".pending")))
            throw new InvalidDataException("복구 승인이 필요한 유효한 작업 기록이 아닙니다.");

        string manifest = Path.Combine(folder, "manifest.json");
        if (!File.Exists(manifest)) throw new InvalidDataException("복구 목록이 없습니다.");
        var index = JsonSerializer.Deserialize<Dictionary<string, string?>>(
            StudyData.ReadLimited(manifest))
            ?? throw new InvalidDataException("복구 목록이 손상됐습니다.");
        if (index.Count != 4 || index.Keys.Any(key => !Keys.Contains(key)))
            throw new InvalidDataException("복구 대상이 잘못됐습니다.");

        byte[]?[] originals = new byte[4][];
        for (int i = 0; i < 4; i++)
        {
            if (!index.TryGetValue(Keys[i], out string? sha))
                throw new InvalidDataException("복구 정보가 누락됐습니다.");
            string file = Path.Combine(folder, Keys[i] + ".bak");
            if (sha is null)
            {
                if (File.Exists(file)) throw new InvalidDataException("복구 파일과 목록이 다릅니다.");
            }
            else
            {
                if (!File.Exists(file)) throw new InvalidDataException("복구 파일이 없습니다.");
                originals[i] = StudyData.ReadLimited(file);
                if (Digest(originals[i]!) != sha)
                    throw new InvalidDataException("원본 복구 파일의 무결성이 일치하지 않습니다.");
            }
        }
        Validate(new Snapshot(originals[0], originals[1], originals[2], originals[3]));
        for (int i = 0; i < 4; i++)
        {
            string live = Path.Combine(directory, Filenames[i]);
            if (originals[i] is null) { if (File.Exists(live)) File.Delete(live); }
            else StudyData.SaveAtomic(live, originals[i]!);
        }
        File.Delete(Path.Combine(folder, ".pending"));
    }

    /// <summary>
    /// Caller performs explicit review and consent; source changes abort before
    /// writing. On error, compensating rollback is attempted. Process termination
    /// cannot be atomic across files; pending recovery is available on restart.
    /// </summary>
    public static string Apply(Checked incoming, Snapshot approved,
                               string directory, Action<int>? testFault = null)
    {
        if (Pending(directory).Count > 0)
            throw new InvalidDataException("미완료 4종 복구가 있습니다. 먼저 이전 작업을 복구하세요.");
        // Re-validate the untrusted incoming snapshot before any local write.
        Checked verified = Parse(Encode(incoming.Original));
        Snapshot latest = Capture(directory);
        if (!latest.Same(approved))
            throw new InvalidDataException("검토 이후 원본이 변경됐습니다. 다시 미리보기를 확인하세요.");
        string folder = CreateRecovery(directory, latest);
        byte[][] values = verified.Original.Values.Select(RequireBytes).ToArray();
        try
        {
            for (int i = 0; i < 4; i++)
            {
                StudyData.SaveAtomic(Path.Combine(directory, Filenames[i]), values[i]);
                testFault?.Invoke(i); // only synthetic test injection; never supplied by UI
            }
            File.Delete(Path.Combine(folder, ".pending"));
        }
        catch (Exception error)
        {
            try { Recover(folder, directory); }
            catch (Exception rollbackError)
            {
                throw new IOException("통합 복구 중 오류가 발생했습니다. 남겨진 복구 파일을 확인하세요: "
                    + folder, new AggregateException(error, rollbackError));
            }
            throw new IOException("통합 가져오기에 실패하여 기존 원본 4종을 복구했습니다: "
                + folder, error);
        }
        return folder; // Retain original bytes for later manual recovery.
    }
}
