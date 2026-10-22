using System.Text;
using StudyOne.Desktop;

static void Check(bool condition, string name)
{
    if (!condition) throw new InvalidOperationException(name);
}

static void ExpectInvalid(Action action, string name)
{
    try { action(); }
    catch (InvalidDataException) { return; }
    throw new InvalidOperationException("Invalid backup incorrectly accepted: " + name);
}

TaskBackup tasks = new()
{
    Grade = 3, ClassName = "1",
    Tasks =
    {
        new StudyTask { Id = 1700000000000, Type = "숙제 항목", Subject = "영어",
                        Title = "기출 복습", DueDate = "2026-10-22", Importance = 2,
                        Completed = false }
    }
};
byte[] originalTask = StudyData.SerializeAndValidateTasks(tasks);
TaskBackup parsedTask = StudyData.ParseTasks(originalTask);
Check(parsedTask.Tasks.Count == 1 && parsedTask.Tasks[0].Type == "숙제 항목",
      "Legacy task backup roundtrip");
Check(!Encoding.UTF8.GetString(originalTask).Contains("apiKey"), "No API secret serialized");

string reminders = StudyChecklistText.Daily(tasks.Tasks, new DateOnly(2026, 10, 22));
Check(reminders.Contains("☐ [영어] 기출 복습"), "Daily copy includes subject prefix");
for (int role = 0; role < 5; role++)
    Check(reminders.Contains((role + 1) + ". " + StudyData.Categories[role]),
          "All five roles retained in copy");
var carryover = new StudyTask {
    Id = 1700000000001, Type = "숙제 항목", Subject = "수학", Title = "내신 종이 숙제",
    DueDate = "2026-10-21", Importance = 3, Completed = false
};
var future = new StudyTask {
    Id = 1700000000002, Type = "숙제 항목", Subject = "역사", Title = "미래 숙제",
    DueDate = "2026-10-25", Importance = 2, Completed = false
};
string withCarryover = StudyChecklistText.Daily(
    new[] { carryover, future, tasks.Tasks[0] }, new DateOnly(2026, 10, 22));
Check(withCarryover.Contains("☐ [수학] 내신 종이 숙제 (전날 못 다 · 원래 마감 2026-10-21)"),
      "Overdue incomplete is shown without changing stored type or date");
Check(!withCarryover.Contains("미래 숙제"), "Future tasks excluded from today copy");
Check(carryover.Type == "숙제 항목" && carryover.DueDate == "2026-10-21",
      "Read-only export never reassigns the original item");


Guid id = Guid.NewGuid();
ProgressBackup progress = new()
{
    Items =
    {
        new StudyProgressItem {
            Id = id.ToString("D"), Subject = "과학", Material = "오투", Unit = "7-2",
            Status = "진행 중", InExamScope = true, Revision = 3, UpdatedAt = 1790000000000 }
    }
};
byte[] originalProgress = StudyData.SerializeAndValidateProgress(progress);
ProgressBackup parsedProgress = StudyData.ParseProgress(originalProgress);
Check(parsedProgress.Items.Count == 1 && parsedProgress.Items[0].Id == id.ToString("D"),
      "Progress ID roundtrip");
Check(parsedProgress.Items[0].Revision == 3 && parsedProgress.Items[0].UpdatedAt == 1790000000000,
      "Revision/timestamp roundtrip");
string progressList = StudyChecklistText.Progress(progress.Items);
Check(progressList.Contains("☐ [과학] 오투 · 7-2 — 진행 중 (시험범위 포함)"),
      "Progress copy preserves four-state status and subject");
Check(!Encoding.UTF8.GetString(StudyData.SerializeAndValidateProgress(progress))
          .Contains("isDone", StringComparison.OrdinalIgnoreCase),
      "UI-only IsDone projection excluded from JSON backup schema");

Check(StudyData.Statuses.SequenceEqual(new[] { "확인 필요", "미완료", "진행 중", "완료" }),
      "Four status values");

ProgressBackup invalidStatus = new()
{
    Items =
    {
        new StudyProgressItem {
            Id = id.ToString("D"), Subject = "과학", Material = "오투", Unit = "7-2",
            Status = "미확인", InExamScope = true, Revision = 3, UpdatedAt = 1790000000000 }
    }
};
ExpectInvalid(() => StudyData.ParseProgress(StudyData.Serialize(invalidStatus)), "Invalid status");
ProgressBackup wrongSchema = new() { Schema = 2, Items = { progress.Items[0] } };
ExpectInvalid(() => StudyData.ParseProgress(StudyData.Serialize(wrongSchema)), "Unknown schema");

ProgressBackup duplicate = new() { Items = { progress.Items[0], progress.Items[0] } };
ExpectInvalid(() => StudyData.SerializeAndValidateProgress(duplicate), "Duplicate ID");

string portableFromAndroid = "{\"format\":\"studyone-progress\",\"schema\":1," +
    "\"items\":[{\"id\":\"" + id.ToString("D") + "\",\"subject\":\"과학\",\"material\":\"오투\"," +
    "\"unit\":\"7-2\",\"status\":\"미완료\",\"inExamScope\":true," +
    "\"revision\":1,\"updatedAt\":1790000000000}]}";
Check(StudyData.ParseProgress(Encoding.UTF8.GetBytes(portableFromAndroid)).Items.Count == 1,
      "Cross-platform camelCase progress JSON");

string tempDir = Path.Combine(Path.GetTempPath(), "studyone-tests-" + Guid.NewGuid().ToString("N"));
try
{
    string file = Path.Combine(tempDir, "progress.json");
    StudyData.SaveAtomic(file, originalProgress);
    Check(File.Exists(file), "First atomic save");
    StudyData.SaveAtomic(file, StudyData.SerializeAndValidateProgress(new ProgressBackup()));
    Check(StudyData.ParseProgress(StudyData.ReadLimited(file)).Items.Count == 0, "Atomic replacement");
}
finally
{
    if (Directory.Exists(tempDir)) Directory.Delete(tempDir, recursive: true);
}
var emptyRemote = new SyncClient.Snapshot { Schema = 1, Cursor = 0 };
var pending = SyncClient.NewOnly(tasks, progress, emptyRemote);
Check(pending.Count == 2, "Two local records are staged for explicit create-only upload");
Check(pending.All(x => x.BaseRevision == 0 && !x.Deleted), "Never overwrite remote records");
var existingTask = new SyncClient.RemoteRecord {
    Kind = "task", Id = tasks.Tasks[0].Id.ToString(),
    Revision = 9, Deleted = false
};
var taskExists = new SyncClient.Snapshot {
    Schema = 1, Cursor = 3, Records = { existingTask }
};
var onlyProgress = SyncClient.NewOnly(tasks, progress, taskExists);
Check(onlyProgress.Count == 1 && onlyProgress[0].Kind == "progress",
      "Remote existing task ID is skipped, not overwritten");
taskExists.Records.Add(new SyncClient.RemoteRecord {
    Kind = "progress", Id = progress.Items[0].Id, Revision = 7, Deleted = false
});
Check(SyncClient.NewOnly(tasks, progress, taskExists).Count == 0,
      "Existing remote IDs never uploaded");
Console.WriteLine("StudyOne Windows local-only sync upload guard: PASS");

Check(SyncClient.ParseEndpoint("http://127.0.0.1:8765/").Scheme == "http",
      "Loopback test endpoint accepted");
Check(SyncClient.ParseEndpoint("https://studyone.example/").Scheme == "https",
      "Verified HTTPS remote endpoint accepted");
foreach (string badUrl in new [] {
    "http://studyone.example/", "http://192.168.0.4:8765/",
    "ftp://studyone.example/", "https://user:pass@studyone.example/",
    "https://studyone.example/not-root/", "https://studyone.example/?x=1"
}) {
    try {
        SyncClient.ParseEndpoint(badUrl);
        throw new InvalidOperationException("Insecure endpoint accepted: " + badUrl);
    } catch (InvalidOperationException error) {
        if (error.Message.StartsWith("Insecure endpoint accepted:")) throw;
    }
}

StudyTask remoteTask = new() {
    Id = 520110014, Type = "숙제 항목", Subject = "수학",
    Title = "추가 숙제", DueDate = "2026-10-23", Importance = 2,
    Completed = false
};
StudyProgressItem remoteProgress = new() {
    Id = Guid.NewGuid().ToString("D"), Subject = "국어", Material = "실전 모의고사",
    Unit = "중간고사 2회 2단원", Status = "미완료",
    InExamScope = true, Revision = 5, UpdatedAt = 1792512900000
};
SyncClient.RemoteRecord MakeTask(StudyTask value) => new() {
    Kind = "task", Id = value.Id.ToString(), Revision = 1,
    Payload = System.Text.Json.JsonSerializer.SerializeToElement(value, StudyData.JsonOptions)
};
SyncClient.RemoteRecord MakeProgress(StudyProgressItem value) => new() {
    Kind = "progress", Id = value.Id, Revision = value.Revision,
    Payload = System.Text.Json.JsonSerializer.SerializeToElement(value, StudyData.JsonOptions)
};

var remoteSnapshot = new SyncClient.Snapshot {
    Schema = 1, Cursor = 4,
    Records = { MakeTask(remoteTask), MakeProgress(remoteProgress),
                MakeTask(tasks.Tasks[0]), MakeProgress(progress.Items[0]) }
};
var inbound = SyncClient.PreviewNewRemote(tasks, progress, remoteSnapshot);
Check(inbound.NewTasks.Count == 1 && inbound.NewProgress.Count == 1,
      "Only server-side new IDs are staged");
Check(inbound.ExistingIdentical == 2 && inbound.Conflicts == 0,
      "Existing same IDs are skipped");
var mergedTasks = SyncClient.MakeMergedTasks(tasks, inbound);
var mergedProgress = SyncClient.MakeMergedProgress(progress, inbound);
Check(mergedTasks.Tasks.Count == 2 && mergedProgress.Items.Count == 2,
      "Additive imports preserve existing items");
Check(tasks.Tasks.Count == 1 && progress.Items.Count == 1,
      "Preview never changes original objects");

// Existing ID with differing content is a conflict; no overwrite.
var changed = new StudyTask {
    Id = tasks.Tasks[0].Id, Type = tasks.Tasks[0].Type,
    Subject = tasks.Tasks[0].Subject, Title = "Conflicting remote edit",
    DueDate = tasks.Tasks[0].DueDate, Importance = 2, Completed = true
};
remoteSnapshot.Records[2] = MakeTask(changed);
var conflicted = SyncClient.PreviewNewRemote(tasks, progress, remoteSnapshot);
Check(conflicted.Conflicts == 1 && conflicted.NewTasks.Count == 1,
      "Conflicting same ID is reported but not staged");
Check(SyncClient.MakeMergedTasks(tasks, conflicted).Tasks[0].Title == "기출 복습",
      "Local task cannot be overwritten by conflict");

// Forged or corrupted payloads must never reach the local save operation.
var forgedRecord = MakeTask(remoteTask);
forgedRecord.Id = "678901";
try {
    SyncClient.PreviewNewRemote(tasks, progress,
        new SyncClient.Snapshot { Schema = 1, Records = { forgedRecord } });
    throw new InvalidOperationException("Mismatched identity incorrectly accepted");
} catch (InvalidDataException) { /* expected */ }
var malformedProgress = new StudyProgressItem {
    Id = Guid.NewGuid().ToString("D"), Subject = "과학", Material = "오투",
    Unit = "7-2", Status = "bad-status", InExamScope = true,
    Revision = 1, UpdatedAt = 1792512983795
};
try {
    SyncClient.PreviewNewRemote(tasks, progress,
        new SyncClient.Snapshot { Schema = 1, Records = { MakeProgress(malformedProgress) } });
    throw new InvalidOperationException("Malformed progress accepted");
} catch (InvalidDataException) { /* expected */ }
Console.WriteLine("StudyOne Windows safe incoming merge and HTTPS endpoints: PASS");

var pendingPlan = new SyncClient.PendingPlan {
    Id = Guid.NewGuid().ToString("D"), Title = "2026년 10월 21일 공부 계획",
    State = "pending", TaskCount = 1, CreatedAt = 1792512000,
    ExpiresAt = 1792512000 + 7 * 86400,
    Tasks = { new StudyTask { Id = 9876543, Type = "숙제 항목",
              Subject = "과학", Title = "오투 7-2",
              DueDate = "2026-10-21", Importance = 2, Completed = false } }
};
var proposals = new SyncClient.PendingPlans {
    Schema = 1, Proposals = { pendingPlan }
};
SyncClient.ValidateProposals(proposals);
Check(pendingPlan.Display.Contains("승인 대기"), "AI proposal must remain pending");
try {
    SyncClient.GetPendingPlans("test-token-should-not-be-accepted-as-a-real-secret",
                               SyncClient.ParseEndpoint("http://127.0.0.1:8765/"))
        .GetAwaiter().GetResult();
    throw new InvalidOperationException("Proposals incorrectly supported on insecure HTTP");
} catch (InvalidOperationException error) {
    if (error.Message.StartsWith("Proposals incorrectly")) throw;
}
pendingPlan.State = "approved";
ExpectInvalid(() => SyncClient.ValidateProposals(proposals),
              "Previously approved proposal not displayed as pending");
pendingPlan.State = "pending";
pendingPlan.Tasks[0].Importance = 99;
ExpectInvalid(() => SyncClient.ValidateProposals(proposals),
              "Malformed AI plan task");
Console.WriteLine("StudyOne Windows ChatGPT proposal approval gate: PASS");

Console.WriteLine("StudyOne Windows portable JSON contract: PASS");
