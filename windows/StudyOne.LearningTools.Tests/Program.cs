using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using StudyOne.Desktop;

int assertions = 0;
void Check(bool condition, string message) { assertions++; if (!condition) throw new Exception(message); }
void Reject(Action action, string message)
{
    bool rejected = false;
    try { action(); } catch (InvalidDataException) { rejected = true; }
    catch (ArgumentException) { rejected = true; } catch (InvalidOperationException) { rejected = true; }
    Check(rejected, message);
}
var start = new DateOnly(2026, 11, 13);
var lanes = new[] { new StudyPaperSchedule.Lane("단원", new[] { new StudyPaperSchedule.Paper("시험지", 3, 0) }) };
var preview = StudyPaperSchedule.Calculate(start, start.AddDays(2), 1, Array.Empty<DateOnly>(), "학습지", lanes);
Check(preview.Days.Count == 3 && preview.Days[0].Papers[0].Title == "시험지 ①", "Pinned 3-day preview");
Check(preview.Days[1].Review == "교재", "Alternating work-day review");
Check(lanes[0].Papers[0].Completed == 0, "No source mutation");
Check(preview.ClipboardText().Contains("실제 숙제·진도에 반영되지"), "No false saved claim");
Reject(() => StudyPaperSchedule.Calculate(start, start, 1, Array.Empty<DateOnly>(), "학습지", lanes), "Capacity rejects whole preview");
Reject(() => new StudyPaperSchedule.Paper("bad\ud800", 1, 0), "Malformed UTF-16 rejected");
Check(StudyTaskViews.Select(Array.Empty<StudyTask>(), DateOnly.MaxValue, 0).Count == 0, "Calendar upper boundary safe");

var task = new StudyTask { Id = 1, Subject = "수학", Title = "공통수학 개념 복습", Type = "정리 및 복습 항목", DueDate = "2026-11-13" };
Check(new StudyTaskFilters("수학 개념 2026").Matches(task), "AND search across subject/title/date");
Check(!new StudyTaskFilters("영어").Matches(task), "Missing term filtered");
Check(new StudyTaskFilters("", 1).Matches(task) && !new StudyTaskFilters("", 2).Matches(task), "Completion filters");
Check(new StudyTaskFilters("", 0, task.Type).Matches(task), "Role filter");
Check(!new StudyTaskFilters("", 0, "숙제 항목").Matches(task), "Other role filtered");
Check(task.Id == 1 && !task.Completed && task.DueDate == "2026-11-13", "Filtering leaves source intact");
Reject(() => new StudyTaskFilters(new string('x',257)), "Bounded search input");

string directory = Path.Combine(Path.GetTempPath(), "studyone-learning-test-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(directory);
try
{
    string path = Path.Combine(directory, "tasks.json");
    string json = """
    {"format":"studyone-backup","schema":1,"grade":3,"className":"2","exportedAt":"2026-11-01","custom":{"keep":true},"tasks":[{"id":17,"type":"기타 항목","subject":"영어","title":"원본 완료 기록","dueDate":"2026-11-01","importance":3,"completed":true,"futureField":42}]}
    """;
    byte[] original = Encoding.UTF8.GetBytes(json);
    File.WriteAllBytes(path, original);
    var batch = StudyPaperTaskBatch.Prepare(original, "수학", preview);
    Check(batch.Count == 3 && File.ReadAllBytes(path).SequenceEqual(original), "Prepare is read-only");
    Reject(() => batch.Commit(path, false), "No approval rejects write");
    Check(!Directory.Exists(Path.Combine(directory,"Recovery")), "No approval creates no backup or mutation");
    byte[] inputCopy = original.ToArray();
    var copied = StudyPaperTaskBatch.Prepare(inputCopy, "수학", preview);
    inputCopy[0] = 0; // Constructor must hold independent snapshots.
    string recovery = copied.Commit(path, true);
    Check(File.ReadAllBytes(recovery).SequenceEqual(original), "Exact original backup");
    byte[] next = File.ReadAllBytes(path);
    var result = StudyData.ParseTasks(next);
    Check(result.Tasks.Count == 4 && result.Tasks[0].Id == 17 && result.Tasks[0].Completed, "Append preserves original and completion");
    Check(result.Grade == 3 && result.ClassName == "2", "School settings preserved");
    using (var document = JsonDocument.Parse(next))
    {
        Check(document.RootElement.GetProperty("custom").GetProperty("keep").GetBoolean(), "Unknown root fields preserved");
        Check(document.RootElement.GetProperty("tasks")[0].GetProperty("futureField").GetInt32() == 42, "Unknown task fields preserved");
    }
    Check(result.Tasks.Skip(1).All(t=>t.Id>0 && t.Id<=9_007_199_254_740_991L && !t.Completed), "Safe unique IDs and uncompleted state");
    Reject(() => copied.Commit(path,true), "Replaying old approval rejected");
    Reject(() => StudyPaperTaskBatch.Prepare(next,"수학",preview), "Duplicate batch rejected");
    result.Tasks.Last().Completed = true;
    Reject(() => StudyPaperTaskBatch.Prepare(StudyData.SerializeAndValidateTasks(result),"수학",preview), "Completed originals still prevent duplicates");
    var aggregate = new TaskBackup { Tasks = { new StudyTask { Id = 31, Subject = "수학", Title = "인쇄 시험지 3회", DueDate = "2026-11-13" } } };
    Reject(() => StudyPaperTaskBatch.Prepare(StudyData.SerializeAndValidateTasks(aggregate), "수학", preview), "Aggregate tasks block guessed duplicate append");
    var crowded = new TaskBackup { Tasks = Enumerable.Range(1, 4999).Select(i => new StudyTask { Id = i,
        Subject = "영어", Title = "기존 " + i, DueDate = "2026-11-01" }).ToList() };
    Reject(() => StudyPaperTaskBatch.Prepare(StudyData.SerializeAndValidateTasks(crowded), "수학", preview), "Task limit rejects whole batch before disk writes");
    File.WriteAllBytes(path, original);
    var stale = StudyPaperTaskBatch.Prepare(original,"수학",preview);
    File.AppendAllText(path," ");
    byte[] modified = File.ReadAllBytes(path);
    Reject(() => stale.Commit(path,true), "Stale bytes block all writes");
    Check(File.ReadAllBytes(path).SequenceEqual(modified), "Changed original preserved");
    File.WriteAllBytes(path,original);
    string blockedDirectory = Path.Combine(directory,"blocked"); Directory.CreateDirectory(blockedDirectory);
    string blockedPath = Path.Combine(blockedDirectory,"tasks.json"); File.WriteAllBytes(blockedPath,original);
    File.WriteAllText(Path.Combine(blockedDirectory,"Recovery"),"block directory creation");
    bool failed = false;
    try { batch.Commit(blockedPath,true); } catch (IOException) { failed = true; }
    Check(failed && File.ReadAllBytes(blockedPath).SequenceEqual(original), "Backup failure leaves original intact");
    string missing = Path.Combine(directory,"new","tasks.json");
    var fresh = StudyPaperTaskBatch.Prepare(null,"수학",preview);
    string emptyBackup = fresh.Commit(missing,true);
    Check(StudyData.ParseTasks(File.ReadAllBytes(missing)).Tasks.Count==3, "New store explicit creation");
    Check(StudyData.ParseTasks(File.ReadAllBytes(emptyBackup)).Tasks.Count==0, "Missing original recovery represented by empty schema");
}
finally { Directory.Delete(directory,recursive:true); }

if (args.Length != 1) throw new ArgumentException("Provide synthetic golden fixture JSON path.");
using var corpus = JsonDocument.Parse(File.ReadAllBytes(args[0]));
int cases = 0;
foreach (var fixture in corpus.RootElement.GetProperty("cases").EnumerateArray())
{
    cases++;
    var p = fixture.GetProperty("plan");
    var expected = fixture.GetProperty("expected");
    string? hash = null;
    StudyPaperSchedule.Preview? value = null;
    try
    {
        value = StudyPaperSchedule.Calculate(
            StudyPaperSchedule.ParseDate(p.GetProperty("startDate").GetString()!),
            StudyPaperSchedule.ParseDate(p.GetProperty("endDate").GetString()!),
            p.GetProperty("maxPerDay").GetInt32(),
            p.GetProperty("skipDates").EnumerateArray().Select(d=>StudyPaperSchedule.ParseDate(d.GetString()!)),
            p.GetProperty("firstReview").GetString()!,
            p.GetProperty("lanes").EnumerateArray().Select(l=>new StudyPaperSchedule.Lane(l.GetProperty("label").GetString()!,
                l.GetProperty("items").EnumerateArray().Select(i=>new StudyPaperSchedule.Paper(i.GetProperty("title").GetString()!,
                    i.GetProperty("total").GetInt32(),i.TryGetProperty("completed",out var c)?c.GetInt32():0)))));
        var text = new StringBuilder($"{value.RemainingPapers}|{value.WorkDays}|{value.DailyLimit}\n");
        foreach (var day in value.Days)
        {
            text.Append(StudyPaperSchedule.Format(day.Date)).Append('|').Append(day.Review).Append('\n');
            foreach(var paper in day.Papers)text.Append(paper.Lane).Append('\t').Append(paper.Title).Append('\n');
        }
        hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text.ToString()))).ToLowerInvariant();
    }
    catch (InvalidDataException) { } catch (ArgumentException) { }
    Check(hash == (expected.ValueKind==JsonValueKind.Null ? null : expected.GetProperty("sha256").GetString()),
        "Golden fixture: "+fixture.GetProperty("name").GetString());
}
Console.WriteLine($"LEARNING_TOOLS_PASSED: {assertions} assertions, {cases} golden fixtures, temporary synthetic stores only");
