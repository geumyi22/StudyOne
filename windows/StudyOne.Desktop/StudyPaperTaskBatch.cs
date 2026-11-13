using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace StudyOne.Desktop;

/// <summary>Prepared append-only proposal. Preview is pure; Commit requires the caller's separate approval.</summary>
public sealed class StudyPaperTaskBatch
{
    private readonly byte[]? original;
    private readonly byte[] proposed;
    public int Count { get; }
    private StudyPaperTaskBatch(byte[]? original, byte[] proposed, int count)
    {
        this.original = original?.ToArray(); this.proposed = proposed.ToArray(); Count = count;
    }
    private static string Canonical(string text) => Regex.Replace(text.Trim().Normalize(), @"\s+", " ");
    private static (string, string, string, string) Key(StudyTask task) =>
        (task.DueDate, Canonical(task.Type), Canonical(task.Subject), Canonical(task.Title));

    public static StudyPaperTaskBatch Prepare(byte[]? original, string subject, StudyPaperSchedule.Preview preview)
    {
        ArgumentNullException.ThrowIfNull(preview);
        StudyPaperSchedule.Label(subject, 60);
        if (subject.Length > 60) throw new InvalidDataException("모바일 호환 과목 이름은 UTF-16 기준 60자 이하여야 합니다.");
        byte[] source = original?.ToArray() ?? StudyData.SerializeAndValidateTasks(new TaskBackup());
        var parsed = StudyData.ParseTasks(source);
        // Retain unknown root and task properties, school settings, existing IDs and completion values.
        var root = JsonNode.Parse(source) as JsonObject ?? throw new InvalidDataException("일정 JSON 객체가 필요합니다.");
        var taskKeys = root.Select(p => p.Key).Where(k => k.Equals("tasks", StringComparison.OrdinalIgnoreCase)).ToArray();
        if (taskKeys.Length > 1) throw new InvalidDataException("모호한 일정 키가 있습니다.");
        string taskKey = taskKeys.SingleOrDefault() ?? "tasks";
        var entries = root[taskKey] as JsonArray;
        if (entries is null)
        {
            if (root.ContainsKey(taskKey)) throw new InvalidDataException("일정 배열이 필요합니다.");
            root[taskKey] = entries = new JsonArray();
        }
        var keys = parsed.Tasks.Select(Key).ToHashSet();
        var ids = parsed.Tasks.Select(t => t.Id).ToHashSet();
        var originals = parsed.Tasks.ToLookup(t => (t.DueDate, Canonical(t.Subject)), t => Canonical(t.Title));
        int count = 0;
        foreach (var day in preview.Days)
            foreach (var paper in day.Papers)
            {
                long id;
                do { id = Random.Shared.NextInt64(1, 9_007_199_254_740_992L); } while (!ids.Add(id));
                var task = new StudyTask { Id = id, Type = "숙제 항목", Subject = subject,
                    Title = $"[{paper.Lane}] {paper.Title}", DueDate = StudyPaperSchedule.Format(day.Date),
                    Importance = 2, Completed = false };
                // Existing aggregate paper homework may cover these rounds. Never guess it is unrelated.
                if (originals[(task.DueDate, Canonical(subject))].Any(title =>
                    title.Contains("인쇄 문제", StringComparison.Ordinal) ||
                    title.Contains("인쇄 시험지", StringComparison.Ordinal) ||
                    title.Contains(Canonical(paper.Title), StringComparison.Ordinal)))
                    throw new InvalidDataException("같은 날짜·과목에 겹칠 수 있는 시험지 숙제가 있습니다. 전체 추가를 취소합니다.");
                // Completed originals count as duplicates too. Reject the WHOLE batch.
                if (!keys.Add(Key(task))) throw new InvalidDataException("동일 날짜·분류·과목·제목의 숙제가 이미 있습니다. 전체 추가를 취소합니다.");
                entries.Add(JsonSerializer.SerializeToNode(task, StudyData.JsonOptions)); count++;
            }
        if (count == 0 || count != preview.RemainingPapers)
            throw new InvalidDataException("추가할 시험지가 없거나 미리보기가 불완전합니다.");
        byte[] next = JsonSerializer.SerializeToUtf8Bytes(root, StudyData.JsonOptions);
        StudyData.ParseTasks(next); // Also checks count/size/schema before ANY write.
        return new StudyPaperTaskBatch(original, next, count);
    }

    private void CheckUnchanged(string path)
    {
        byte[]? current = File.Exists(path) ? StudyData.ReadLimited(path) : null;
        if (original is null ? current is not null : current is null || !original.SequenceEqual(current))
            throw new InvalidDataException("검토 중 일정 원본이 변경됐습니다. 다시 미리보기를 만드세요.");
    }

    public string Commit(string path, bool userApproved)
    {
        if (!userApproved) throw new InvalidOperationException("별도 사용자 승인이 필요합니다.");
        path = Path.GetFullPath(path);
        CheckUnchanged(path);
        string folder = Path.GetDirectoryName(path)!;
        if (StudyPortableBundle.Pending(folder).Count != 0)
            throw new InvalidDataException("중단된 4종 복구를 먼저 완료하세요.");
        string recovery = Path.Combine(folder, "Recovery", "StudyOne-tasks-before-paper-" + Guid.NewGuid().ToString("N") + ".json");
        // Recovery bytes must be durable before replacing the task file. Failure leaves original untouched.
        StudyData.SaveAtomic(recovery, original ?? StudyData.SerializeAndValidateTasks(new TaskBackup()));
        CheckUnchanged(path);
        StudyData.SaveAtomic(path, proposed);
        return recovery;
    }
}
