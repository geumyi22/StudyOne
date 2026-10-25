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

// Same persisted task objects/IDs feed all date screens. Never change original due date.
var dueTomorrow = new StudyTask { Id = 1700000000091, Type = "숙제 항목",
    Subject = "수학", Title = "백발백중", DueDate = "2026-10-23",
    Importance = 3, Completed = false };
var tomorrowExam = new StudyTask { Id = 1700000000092, Type = "시험",
    Subject = "과학", Title = "시험", DueDate = "2026-10-23",
    Importance = 2, Completed = false };
var oldUnfinished = new StudyTask { Id = 1700000000093, Type = "기타 항목",
    Subject = "국어", Title = "지난 숙제", DueDate = "2026-10-21",
    Importance = 1, Completed = false };
var records = new[] { dueTomorrow, tomorrowExam, oldUnfinished, tasks.Tasks[0] };
var day = new DateOnly(2026, 10, 22);
var todayPreview = StudyTaskViews.Select(records, day, 0);
var nextDay = StudyTaskViews.Select(records, day, 1);
var everyDay = StudyTaskViews.Select(records, day, 2);
Check(todayPreview.Select(x => x.Id).OrderBy(x => x).SequenceEqual(
    new long[] { oldUnfinished.Id, tasks.Tasks[0].Id, dueTomorrow.Id }.OrderBy(x => x)),
    "Today shows due-now, overdue unfinished, and tomorrow homework only");
Check(nextDay.Count == 2 && nextDay.Any(x => x.Id == dueTomorrow.Id)
      && nextDay.Any(x => x.Id == tomorrowExam.Id),
    "Tomorrow shows homework and assessments");
Check(everyDay.Count == 4 && records.Length == 4,
    "All view retains four original records without duplication");
Check(ReferenceEquals(todayPreview.Single(x => x.Id == dueTomorrow.Id),
    nextDay.Single(x => x.Id == dueTomorrow.Id)),
    "Today preview and Tomorrow page share original object identity");
Check(dueTomorrow.DueDate == "2026-10-23" && !dueTomorrow.Completed,
    "Read-only filtering never rewrites homework status or date");

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


TaskDetailsBackup details = new() {
    Items = {
        new StudyTaskDetail {
            TaskId = tasks.Tasks[0].Id, EstimatedMinutes = 35,
            Steps = new() { "본문 가리고 쓰기", "정답 확인" },
            CompletionCriteria = "틀린 문장 수정 완료"
        }
    }
};
byte[] detailBytes = StudyTaskDetailsData.Serialize(details);
TaskDetailsBackup readDetails = StudyTaskDetailsData.Parse(detailBytes);
Check(readDetails.Items.Count == 1 && readDetails.Items[0].Steps.Count == 2,
      "Android/iOS-compatible task guidance JSON roundtrip");
Check(Encoding.UTF8.GetString(detailBytes).Contains("\"estimatedMinutes\""),
      "Android duration key retained in Windows backup");
Check(!Encoding.UTF8.GetString(originalTask).Contains("estimatedMinutes"),
      "Historical task schema unchanged");
string withDetails = StudyChecklistText.Daily(
    tasks.Tasks, new DateOnly(2026, 10, 22),
    readDetails.Items.ToDictionary(x => x.TaskId));
Check(withDetails.Contains("예상 35분") && withDetails.Contains("완료 기준: 틀린 문장 수정 완료"),
      "Windows reminder copy includes optional study instructions");
Check(!reminders.Contains("예상 35분"), "Legacy daily copy unchanged without details");
ExpectInvalid(() => StudyTaskDetailsData.Parse(
    StudyData.Serialize(new TaskDetailsBackup { Items = { details.Items[0], details.Items[0] } })),
    "Duplicate detail IDs");
ExpectInvalid(() => StudyTaskDetailsData.Parse(
    StudyData.Serialize(new TaskDetailsBackup {
        Items = { new StudyTaskDetail {
            TaskId = 1, EstimatedMinutes = 601, Steps = new(), CompletionCriteria = ""
        } }
    })), "Study duration must be 1-600 minutes");
ExpectInvalid(() => StudyTaskDetailsData.Parse(
    Encoding.UTF8.GetBytes("{\"format\":\"studyone-task-details\",\"schema\":1,\"items\":[{\"taskId\":9,\"steps\":[],\"completionCriteria\":\"\"}]}")),
    "Missing nullable minutes key rejected");

Guid wrongId = Guid.NewGuid();
WrongAnswersBackup wrongBook = new() {
    Items = { new StudyWrongAnswer {
        Id = wrongId.ToString("D"), Subject = "과학", Material = "오투",
        Topic = "7-2 성운 분류", Correction = "관측 특징과 정의를 혼동함",
        NextReview = "2026-10-23", Retries = 1, Mastered = false
    } }
};
byte[] wrongBytes = StudyWrongAnswersData.Serialize(wrongBook);
WrongAnswersBackup checkedWrong = StudyWrongAnswersData.Parse(wrongBytes);
Check(checkedWrong.Items.Count == 1 && checkedWrong.Items[0].Retries == 1,
      "Wrong-answer Android/iOS/Windows JSON roundtrip");
string wrongJson = Encoding.UTF8.GetString(wrongBytes);
Check(wrongJson.Contains("\"nextReview\"") && wrongJson.Contains("\"correction\"") &&
      !wrongJson.Contains("\"apiKey\""), "Portable wrong-answer field names; no API secrets");
Check(!Encoding.UTF8.GetString(originalTask).Contains("nextReview"),
      "Historical task schema unchanged by wrong answers");
ExpectInvalid(() => StudyWrongAnswersData.Parse(
    Encoding.UTF8.GetBytes("{\"format\":\"studyone-wrong-answers\",\"schema\":1,\"items\":[{}]}")),
    "Invalid partial wrong-answer rejected");
ExpectInvalid(() => StudyWrongAnswersData.Parse(
    StudyData.Serialize(new WrongAnswersBackup {
        Items = { wrongBook.Items[0], wrongBook.Items[0] }
    })), "Duplicate wrong answer IDs");
ExpectInvalid(() => StudyWrongAnswersData.Serialize(new WrongAnswersBackup {
    Items = { new StudyWrongAnswer {
        Id = Guid.NewGuid().ToString("D"), Subject = "사회", Material = "평가문제집",
        Topic = "계산", Correction = "정답 근거", NextReview = "2026-10-23", Retries = 51
    } }
}), "Excessive retry count");

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

// Fast-add parity: existing social progress is authoritative even with another material.
var onlineSocial = new StudyProgressItem {
    Id = Guid.NewGuid().ToString("D"), Subject = "사회", Material = "학교 학습지",
    Unit = StudyOnlinePractice.Unit, Status = "진행 중", InExamScope = true,
    Revision = 7, UpdatedAt = 1790000000000
};
var onlineKnown = new List<StudyProgressItem> { progress.Items[0], onlineSocial };
var onlineMissing = StudyOnlinePractice.MissingSubjects(onlineKnown);
Check(onlineMissing.SequenceEqual(new[] { "국어", "역사", "과학", "영어", "수학" }),
      "Online practice skips any existing subject and unit, regardless of material");
var onlineCreated = StudyOnlinePractice.CreateMissing(onlineKnown);
Check(onlineCreated.Count == 5 && onlineCreated.All(x => x.Status == "미완료"
      && x.InExamScope && x.Revision == 1), "Five new pending online practice entries");
Check(onlineSocial.Status == "진행 중" && onlineKnown.Count == 2,
      "Preview never changes existing progress or its status");
Check(StudyOnlinePractice.CreateMissing(onlineKnown.Concat(onlineCreated)).Count == 0,
      "Repeat online practice request must be idempotent");

string tempDir = Path.Combine(Path.GetTempPath(), "studyone-tests-" + Guid.NewGuid().ToString("N"));
try
{
    string file = Path.Combine(tempDir, "progress.json");
    StudyData.SaveAtomic(file, originalProgress);
    Check(File.Exists(file), "First atomic save");
    StudyData.SaveAtomic(file, StudyData.SerializeAndValidateProgress(new ProgressBackup()));
    Check(StudyData.ParseProgress(StudyData.ReadLimited(file)).Items.Count == 0, "Atomic replacement");

    // Recovery is written BEFORE a confirmed replacement and stale review is rejected.
    byte[] approved = StudyData.ReadLimited(file);
    var proposal = new ProgressBackup { Items = onlineKnown.Concat(onlineCreated).ToList() };
    string recoveryFile = StudyData.SaveProgressWithRecovery(proposal, approved, file);
    Check(File.Exists(recoveryFile), "Import/quick-add recovery file exists");
    Check(StudyData.ParseProgress(StudyData.ReadLimited(recoveryFile)).Items.Count == 0,
          "Recovery contains the exact pre-replacement progress");
    Check(StudyData.ParseProgress(StudyData.ReadLimited(file)).Items.Count == 7,
          "Replacement stores the six subjects plus original progress");
    byte[] after = StudyData.ReadLimited(file);
    ExpectInvalid(() => StudyData.SaveProgressWithRecovery(new ProgressBackup(), approved, file),
                  "Stale original backup must reject replacement");
    Check(StudyData.ReadLimited(file).SequenceEqual(after),
          "Stale import never changes confirmed progress");
    Check(StudyData.ParseProgress(StudyData.ReadLimited(file)).Items
          .Any(x => x.Id == onlineSocial.Id && x.Status == "진행 중"),
          "Existing in-progress social status preserved after recovery-aware save");
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
// Phase30: every server-applied mutation MUST appear in the receipt by ID/revision/order.
var ackRequests = SyncClient.NewOnly(tasks, progress,
    new SyncClient.Snapshot { Schema = 1, Cursor = 0 });
Check(ackRequests.Count == 2, "Synthetic ACK fixture has two new records");
byte[] MakeReceipt(string firstId, string secondId, long finalCursor = 2,
                   long firstRevision = 1, long secondSequence = 2) =>
    System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(new {
        schema = 1, cursor = finalCursor,
        changes = new object[] {
            new { seq = 1L, kind = ackRequests[0].Kind, id = firstId,
                  revision = firstRevision, deleted = false, updatedAt = 1790000000000L },
            new { seq = secondSequence, kind = ackRequests[1].Kind, id = secondId,
                  revision = 1L, deleted = false, updatedAt = 1790000000000L }
        }
    });
var receipt = MakeReceipt(ackRequests[0].Id, ackRequests[1].Id);
SyncClient.VerifyChangeAck(receipt, ackRequests);
ExpectInvalid(() => SyncClient.VerifyChangeAck(
    MakeReceipt(ackRequests[1].Id, ackRequests[0].Id), ackRequests),
    "Swapped IDs must not count as successful sync");
ExpectInvalid(() => SyncClient.VerifyChangeAck(
    MakeReceipt(ackRequests[0].Id, ackRequests[1].Id, firstRevision: 9), ackRequests),
    "Forged revision must be rejected");
ExpectInvalid(() => SyncClient.VerifyChangeAck(
    MakeReceipt(ackRequests[0].Id, ackRequests[1].Id, finalCursor: 77), ackRequests),
    "Cursor mismatch must be rejected");
ExpectInvalid(() => SyncClient.VerifyChangeAck(
    MakeReceipt(ackRequests[0].Id, ackRequests[1].Id, secondSequence: 1), ackRequests),
    "Duplicate sequence must be rejected");
Console.WriteLine("StudyOne Windows complete server receipt verification: PASS");

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
// Phase32: explicit, server-revision guarded single-ID update and deletion.
// These synthetic checks create NO real study data and perform NO network call.
var editedTask = new StudyTask {
    Id = tasks.Tasks[0].Id, Type = tasks.Tasks[0].Type,
    Subject = tasks.Tasks[0].Subject, Title = "직접 승인한 수정 과제",
    DueDate = tasks.Tasks[0].DueDate, Importance = 2, Completed = true
};
var editableRemote = new SyncClient.Snapshot {
    Schema = 1, Cursor = 11,
    Records = { MakeTask(tasks.Tasks[0]) }
};
editableRemote.Records[0].Revision = 7;
var proposedTaskEdit = SyncClient.PrepareReviewedChange(
    new TaskBackup { Tasks = { editedTask } }, new ProgressBackup(),
    editableRemote, "task", editedTask.Id.ToString(), delete: false);
Check(proposedTaskEdit.Mutation.BaseRevision == 7 &&
      proposedTaskEdit.Mutation.Payload is StudyTask &&
      ((StudyTask)proposedTaskEdit.Mutation.Payload).Title == "직접 승인한 수정 과제" &&
      !proposedTaskEdit.Mutation.Deleted && proposedTaskEdit.Cursor == 11,
      "Single-ID update must carry actual authoritative server revision");
var updatedTaskReceipt = System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(
    new { schema = 1, cursor = 25, changes = new[] {
        new { seq = 25, kind = "task", id = editedTask.Id.ToString(),
              revision = 8, deleted = false, updatedAt = 1790000000000L } } });
SyncClient.VerifyChangeAck(updatedTaskReceipt,
    new[] { proposedTaskEdit.Mutation });
ExpectInvalid(() => SyncClient.VerifyChangeAck(
    System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(
        new { schema = 1, cursor = 25, changes = new[] {
            new { seq = 25, kind = "task", id = editedTask.Id.ToString(),
                  revision = 7, deleted = false, updatedAt = 1790000000000L } } }),
    new[] { proposedTaskEdit.Mutation }),
    "Update must refuse an ACK that did not increment server revision");

var proposedDeletion = SyncClient.PrepareReviewedChange(
    new TaskBackup(), new ProgressBackup(), editableRemote,
    "task", editedTask.Id.ToString(), delete: true);
Check(proposedDeletion.Mutation.Deleted &&
      proposedDeletion.Mutation.BaseRevision == 7 &&
      proposedDeletion.Mutation.Payload is null,
      "Explicit server deletion creates a tombstone only for absent local ID");
try {
    SyncClient.PrepareReviewedChange(new TaskBackup { Tasks = { editedTask } },
        new ProgressBackup(), editableRemote, "task",
        editedTask.Id.ToString(), delete: true);
    throw new Exception("Remote deletion accepted while local record still exists");
} catch (InvalidOperationException) { /* safe guard */ }
try {
    SyncClient.PrepareReviewedChange(tasks, new ProgressBackup(),
        editableRemote, "task", editedTask.Id.ToString(), delete: false);
    throw new Exception("Identical server and local payload accepted as modification");
} catch (InvalidOperationException) { /* no-op cannot overwrite */ }
ExpectInvalid(() => SyncClient.PrepareReviewedChange(
    new TaskBackup { Tasks = { editedTask } }, new ProgressBackup(),
    new SyncClient.Snapshot { Schema = 1, Cursor = 12 },
    "task", editedTask.Id.ToString(), delete: false),
    "Existing-ID change must be refused when the server record has disappeared");

var remoteProgressEdit = new SyncClient.Snapshot {
    Schema = 1, Cursor = 18, Records = { MakeProgress(progress.Items[0]) }
};
remoteProgressEdit.Records[0].Revision = progress.Items[0].Revision;
var editedProgress = new StudyProgressItem {
    Id = progress.Items[0].Id, Subject = progress.Items[0].Subject,
    Material = progress.Items[0].Material, Unit = progress.Items[0].Unit,
    Status = "완료", InExamScope = progress.Items[0].InExamScope,
    Revision = 1, UpdatedAt = 1790000000000L
};
var progressEdit = SyncClient.PrepareReviewedChange(
    new TaskBackup(), new ProgressBackup { Items = { editedProgress } },
    remoteProgressEdit, "progress", editedProgress.Id, delete: false);
Check(progressEdit.Mutation.Kind == "progress" &&
      progressEdit.Mutation.BaseRevision == remoteProgressEdit.Records[0].Revision &&
      progressEdit.Mutation.Payload is StudyProgressItem changedProgressPayload &&
      changedProgressPayload.Status == "완료",
      "Progress edits use SERVER revision rather than local field revision");
var progressDeletion = SyncClient.PrepareReviewedChange(
    new TaskBackup(), new ProgressBackup(), remoteProgressEdit,
    "progress", editedProgress.Id, delete: true);
Check(progressDeletion.Mutation.Deleted && progressDeletion.Mutation.Payload is null,
      "Progress tombstone never includes a payload");
Console.WriteLine("StudyOne approved remote edit/delete CAS review guards: PASS");

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

// Phase23: four-store Android wire compatibility and guarded recovery on
// synthetic files; never reads the developer's real StudyOne profile.
string phase23 = Path.Combine(Path.GetTempPath(), "studyone-fourstore-qa-" +
    Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(phase23);
try
{
    byte[] fourTasks = StudyData.SerializeAndValidateTasks(tasks);
    byte[] fourProgress = StudyData.SerializeAndValidateProgress(progress);
    byte[] fourDetails = StudyTaskDetailsData.Serialize(details);
    byte[] fourWrong = StudyWrongAnswersData.Serialize(wrongBook);
    string[] paths = { "tasks.json", "progress.json", "task-details.json", "wrong-answers.json" };
    byte[][] originals = { fourTasks, fourProgress, fourDetails, fourWrong };
    for (int i = 0; i < 4; i++)
        StudyData.SaveAtomic(Path.Combine(phase23, paths[i]), originals[i]);

    var reviewed = StudyPortableBundle.Capture(phase23);
    string encoded = StudyPortableBundle.Encode(reviewed);
    var checkedBundle = StudyPortableBundle.Parse(encoded);
    Check(checkedBundle.Summary.Contains("일정 1개") &&
          checkedBundle.Summary.Contains("진도 1개") &&
          checkedBundle.Summary.Contains("학습 상세 1개") &&
          checkedBundle.Summary.Contains("오답 1개"), "Four-store count preview");
    Check(checkedBundle.Original.Same(reviewed), "Android four-store envelope roundtrip");

    var tampered = System.Text.Json.Nodes.JsonNode.Parse(encoded)!;
    tampered["sections"]!["progress"] = Encoding.UTF8.GetString(StudyData.SerializeAndValidateProgress(new ProgressBackup()));
    ExpectInvalid(() => StudyPortableBundle.Parse(tampered.ToJsonString()),
                  "Checksum tampering must be rejected");
    var unsupported = System.Text.Json.Nodes.JsonNode.Parse(encoded)!;
    unsupported["schema"] = 2;
    ExpectInvalid(() => StudyPortableBundle.Parse(unsupported.ToJsonString()),
                  "Unknown bundle schema must be rejected");

    var changedProgress = new ProgressBackup { Items = onlineKnown.Concat(onlineCreated).ToList() };
    var incoming = StudyPortableBundle.Parse(StudyPortableBundle.Encode(
        new StudyPortableBundle.Snapshot(fourTasks,
            StudyData.SerializeAndValidateProgress(changedProgress),
            fourDetails, fourWrong)));
    string recoveryFolder = StudyPortableBundle.Apply(incoming, reviewed, phase23);
    Check(StudyPortableBundle.Pending(phase23).Count == 0, "Successful import clears pending");
    Check(File.Exists(Path.Combine(recoveryFolder, "manifest.json")),
          "Successful import retains original recovery journal");
    Check(StudyPortableBundle.Capture(phase23).Progress is byte[] savedProgress &&
          StudyData.ParseProgress(savedProgress).Items.Count == 7,
          "Approved four-store import writes all sections");

    var now = StudyPortableBundle.Capture(phase23);
    // This stale review is rejected without any local write, journal, or rollback.
    ExpectInvalid(() => StudyPortableBundle.Apply(checkedBundle, reviewed, phase23),
                  "Stale approved four-store preview");
    Check(StudyPortableBundle.Capture(phase23).Same(now),
          "Rejected stale import preserves all original bytes");
    // Simulated failure immediately after writing section 2; compensating
    // rollback should restore all four originally captured sections.
    try
    {
        StudyPortableBundle.Apply(checkedBundle, now, phase23, i => {
            if (i == 1) throw new IOException("Synthetic section-2 failure");
        });
        throw new InvalidOperationException("Injected four-store failure was swallowed");
    }
    catch (IOException error)
    {
        Check(error.Message.Contains("복구했습니다"),
              "Expected automatic compensating rollback");
    }
    Check(StudyPortableBundle.Capture(phase23).Same(now),
          "Partial import rolls all four files back to exact bytes");
    Check(StudyPortableBundle.Pending(phase23).Count == 0,
          "Successful rollback clears incomplete journal");

    // Rehearse power-loss recovery: a trusted journal already exists from
    // a previous operation, and one target has been partially replaced.
    string crashFolder = Path.Combine(phase23, "Recovery",
        "StudyOne-fourstore-" + Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(crashFolder);
    var digests = new Dictionary<string, string?>();
    string[] keys = { "tasks", "progress", "details", "wrongAnswers" };
    byte[][] nowValues = now.Values.Select((x, i) => x!).ToArray();
    for (int i = 0; i < 4; i++)
    {
        digests.Add(keys[i], Convert.ToHexString(
            System.Security.Cryptography.SHA256.HashData(nowValues[i])).ToLowerInvariant());
        StudyData.SaveAtomic(Path.Combine(crashFolder, keys[i] + ".bak"), nowValues[i]);
    }
    StudyData.SaveAtomic(Path.Combine(crashFolder, "manifest.json"),
        System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(digests));
    StudyData.SaveAtomic(Path.Combine(crashFolder, ".pending"), Encoding.UTF8.GetBytes("1"));
    StudyData.SaveAtomic(Path.Combine(phase23, "progress.json"), fourProgress);
    Check(StudyPortableBundle.Pending(phase23).Count == 1, "Crash journal detected");
    ExpectInvalid(() => StudyPortableBundle.Apply(incoming,
        StudyPortableBundle.Capture(phase23), phase23), "Incomplete transaction blocks new import");
    StudyPortableBundle.Recover(crashFolder, phase23);
    Check(StudyPortableBundle.Capture(phase23).Same(now),
          "Explicit interrupted journal recovery restores all 4 original stores");
    Check(StudyPortableBundle.Pending(phase23).Count == 0,
          "Explicit recovery clears pending marker");
    Console.WriteLine("StudyOne Windows four-store checksums, stale guard, rollback and crash recovery: PASS");
}
finally { if (Directory.Exists(phase23)) Directory.Delete(phase23, recursive: true); }

// Phase26: durable, credential-free local HTTPS outbox. Synthetic data only.
string outboxDir = Path.Combine(Path.GetTempPath(),
    "studyone-outbox-qa-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(outboxDir);
try
{
    var endpoint = new Uri("https://sync.example.org/");
    string account = Guid.NewGuid().ToString("D");
    TaskBackup sourceTasks = new()
    {
        Grade = 3, ClassName = "1",
        Tasks = { new StudyTask
        {
            Id = 1790000000001, Type = "숙제 항목", Subject = "영어",
            Title = "합성 영어 문제", DueDate = "2026-11-01",
            Importance = 2, Completed = false
        }}
    };
    ProgressBackup sourceProgress = new() {
        Items = { new StudyProgressItem {
            Id = Guid.NewGuid().ToString("D"), Subject = "과학",
            Material = "오투", Unit = "5단원", Status = "미완료",
            InExamScope = true, Revision = 1, UpdatedAt = 1790000000000
        }}
    };
    int added = StudySyncOutbox.Enqueue(sourceTasks, sourceProgress,
                                          account, endpoint, outboxDir);
    Check(added == 2, "Create-only queue stores two new records");
    var queued = StudySyncOutbox.Read(outboxDir);
    Check(queued.Batches.Count == 1 && queued.Batches[0].Mutations.Count == 2,
          "Queued mutations survive strict portable JSON read");
    string requestId = queued.Batches[0].RequestId;
    Check(Guid.TryParse(requestId, out _), "Stable idempotency UUID saved");
    string localJson = File.ReadAllText(StudySyncOutbox.FilePath(outboxDir));
    Check(!localJson.Contains("Bearer") && !localJson.Contains("password") &&
          !localJson.Contains("token", StringComparison.OrdinalIgnoreCase),
          "Queued payload never includes bearer credentials");
    Check(StudySyncOutbox.Enqueue(sourceTasks, sourceProgress,
                                  account, endpoint, outboxDir) == 0 &&
          StudySyncOutbox.Read(outboxDir).Batches[0].RequestId == requestId,
          "Repeated offline enqueue is idempotent");
    StudySyncOutbox.ValidateCurrent(queued.Batches[0], sourceTasks, sourceProgress);
    var verified = new SyncClient.Session {
        Schema = 1, AccountId = account, DeviceId = Guid.NewGuid().ToString("D"),
        Scope = "read-write", Audience = "device", ServerTime = 100, ExpiresAt = 200
    };
    StudySyncOutbox.ConfirmAccount(queued.Batches[0], verified, endpoint);
    try {
        StudySyncOutbox.ConfirmAccount(queued.Batches[0],
            new SyncClient.Session {
                Schema = 1, AccountId = Guid.NewGuid().ToString("D"),
                Scope = "read-write", Audience = "device", ServerTime = 100, ExpiresAt = 200
            }, endpoint);
        throw new InvalidOperationException("Wrong account accepted");
    } catch (InvalidOperationException e) when (e.Message.Contains("일치하지")) {}
    try {
        StudySyncOutbox.ConfirmAccount(queued.Batches[0],
            new SyncClient.Session {
                Schema = 1, AccountId = account, Scope = "read",
                Audience = "device", ServerTime = 100, ExpiresAt = 200
            }, endpoint);
        throw new InvalidOperationException("Read-only token accepted");
    } catch (InvalidOperationException e) when (e.Message.Contains("일치하지")) {}
    try {
        var edit = new TaskBackup {
            Tasks = { new StudyTask {
                Id = sourceTasks.Tasks[0].Id, Type = "숙제 항목", Subject = "영어",
                Title = "사용자가 오프라인에서 제목 변경", DueDate = "2026-11-01",
                Importance = 2, Completed = false
            }}
        };
        StudySyncOutbox.ValidateCurrent(queued.Batches[0], edit, sourceProgress);
        throw new InvalidOperationException("Stale local record accepted");
    } catch (InvalidDataException) {}
    Check(StudySyncOutbox.Read(outboxDir).Batches[0].RequestId == requestId,
          "Stale source preserves original request and queue");
    string originalQueue = File.ReadAllText(StudySyncOutbox.FilePath(outboxDir));
    var forged = System.Text.Json.Nodes.JsonNode.Parse(originalQueue)!;
    forged["batches"]![0]!["mutations"]![0]!["deleted"] = true;
    File.WriteAllText(StudySyncOutbox.FilePath(outboxDir), forged.ToJsonString());
    ExpectInvalid(() => StudySyncOutbox.Read(outboxDir), "Deleted remote operation rejected");
    File.WriteAllText(StudySyncOutbox.FilePath(outboxDir), originalQueue);
    StudySyncOutbox.Acknowledge(outboxDir, requestId);
    Check(StudySyncOutbox.Read(outboxDir).Batches.Count == 0,
          "Only confirmed request removes durable queue entry");
    Check(sourceTasks.Tasks[0].Title == "합성 영어 문제" &&
          sourceProgress.Items[0].Status == "미완료",
          "Outbox tests never modify local study sources");
    // Chunk 55 records into bounded requests with immutable UUIDs.
    var many = new TaskBackup { Grade = 3, ClassName = "1" };
    for (int i = 0; i < 55; i++)
        many.Tasks.Add(new StudyTask {
            Id = 1790000100000 + i, Type = "숙제 항목", Subject = "수학",
            Title = "합성 " + i, DueDate = "2026-11-02",
            Importance = 2, Completed = false
        });
    added = StudySyncOutbox.Enqueue(many, new ProgressBackup(),
                                      account, endpoint, outboxDir);
    var batched = StudySyncOutbox.Read(outboxDir);
    Check(added == 55 && batched.Batches.Count == 2 &&
          batched.Batches[0].Mutations.Count == 50 &&
          batched.Batches[1].Mutations.Count == 5,
          "Queue splits writes into server-safe 50 record chunks");
    Console.WriteLine("StudyOne Windows offline outbox, idempotency, stale-account and tamper QA: PASS");
}
finally { if (Directory.Exists(outboxDir)) Directory.Delete(outboxDir, recursive: true); }

// Phase28: subject-first study action, distinct from transport/backup scenarios.
var focusData = new[] {
    new StudyProgressItem { Id=Guid.NewGuid().ToString("D"), Subject="역사",
        Material="체크체크", Unit="시험대비 10장", Status="진행 중", InExamScope=true },
    new StudyProgressItem { Id=Guid.NewGuid().ToString("D"), Subject="역사",
        Material="온라인 문제풀이", Unit=StudyOnlinePractice.Unit, Status="미완료", InExamScope=true },
    new StudyProgressItem { Id=Guid.NewGuid().ToString("D"), Subject="국어",
        Material="자습서", Unit="전체 개념 복습", Status="완료", InExamScope=true },
    new StudyProgressItem { Id=Guid.NewGuid().ToString("D"), Subject="국어",
        Material="온라인 문제풀이", Unit=StudyOnlinePractice.Unit, Status="미완료", InExamScope=true },
    new StudyProgressItem { Id=Guid.NewGuid().ToString("D"), Subject="과학",
        Material="오투", Unit="시험 대비 자료", Status="미완료", InExamScope=true },
    new StudyProgressItem { Id=Guid.NewGuid().ToString("D"), Subject="과학",
        Material="기출", Unit="시험범위 외", Status="완료", InExamScope=false }
};
var focus = StudyExamFocus.Summarize(focusData);
Check(focus.Count == 3 && focus[0].Name == "국어" &&
      focus[1].Name == "역사" && focus[2].Name == "과학",
      "Subjects always use common StudyOne exam order");
Check(focus[0].Total == 2 && focus[0].Done == 1 &&
      focus[0].Percent == 50 && focus[0].Next.Contains(StudyOnlinePractice.Unit),
      "Online practice recommended only when no other unfinished exam records");
Check(focus[1].Total == 2 && focus[1].Started == 1 &&
      focus[1].OnlineWaiting == 1 && focus[1].Next.Contains("10장") &&
      !focus[1].Next.Contains(StudyOnlinePractice.Unit),
      "History real 10-page exam packet suggested before online problems");
Check(focus[2].Total == 1 && focus[2].Todo == 1 &&
      focus[2].Next.Contains("시험 대비 자료"),
      "Exclude non-exam scope from science dashboard");
Check(focusData[0].Status == "진행 중" && focusData[1].Status == "미완료",
      "Exam dashboard must never silently mark work completed");
Check(StudyExamFocus.Summarize(Array.Empty<StudyProgressItem>()).Count == 0,
      "No fake completion count for empty progress");
Console.WriteLine("StudyOne subject exam dashboard prioritization: PASS");

// Phase31 opt-in: run the real portable .NET SyncClient against the SAME
// ephemeral loopback SQLite server that the real iOS Simulator app wrote to.
// Not a fake .NET HTTP layer. No WPF UI, personal records or stored credentials.
if (Environment.GetEnvironmentVariable("STUDYONE_PHASE31_MAC_CROSSCLIENT") == "1")
{
    string raw = Environment.GetEnvironmentVariable("STUDYONE_E2E_BASE") ??
        throw new InvalidOperationException("Synthetic test server address missing");
    string token = Environment.GetEnvironmentVariable("STUDYONE_SYNC_TOKEN") ??
        throw new InvalidOperationException("Synthetic test token missing");
    var address = SyncClient.ParseEndpoint(raw);
    Check(address.Host == "127.0.0.1" && address.Port == 8765 &&
          address.Scheme == "http", "Only disposable loopback server allowed");
    var iOSResult = await SyncClient.GetSnapshot(token, endpoint: address);
    Check(iOSResult.Cursor == 2 && iOSResult.Records.Count == 2,
          "Actual iOS app mutations must be visible to Windows .NET client");
    var original = SyncClient.PreviewNewRemote(
        new TaskBackup { Grade = 3, ClassName = "1" },
        new ProgressBackup(), iOSResult);
    Check(original.NewTasks.Count == 1 && original.NewProgress.Count == 1,
          "Portable Windows inbound parser must decode real iOS app records");
    Check(original.NewTasks[0].Id == 1790093110001 &&
          original.NewProgress[0].Material == "합성 시험 교재",
          "iOS app-origin data must preserve identity and payload");
    var fromWindows = new StudyTask {
        Id = 1790093110002, Type = "숙제 항목",
        Subject = "수학", Title = "Windows .NET 실제 클라이언트 합성 과제",
        DueDate = "2026-11-06", Importance = 3, Completed = false
    };
    var outbound = SyncClient.NewOnly(
        new TaskBackup { Grade = 3, ClassName = "1", Tasks = { fromWindows } },
        new ProgressBackup(), iOSResult);
    Check(outbound.Count == 1 && outbound[0].Id == fromWindows.Id.ToString(),
          "Windows sends only its new synthetic record");
    Check(await SyncClient.UploadNew(token, outbound, endpoint: address) == 1,
          "Windows HTTP request must receive validated server acknowledgement");
    var shared = await SyncClient.GetSnapshot(token, endpoint: address);
    Check(shared.Cursor == 3 && shared.Records.Count == 3,
          "Server has both iOS and .NET records with no duplicates");
    var different = new StudyTask {
        Id = fromWindows.Id, Type = fromWindows.Type, Subject = "수학",
        Title = "로컬 충돌 가상 텍스트", DueDate = fromWindows.DueDate,
        Importance = 3, Completed = false
    };
    var guarded = SyncClient.PreviewNewRemote(
        new TaskBackup { Grade = 3, ClassName = "1", Tasks = { different } },
        new ProgressBackup(), shared);
    Check(guarded.Conflicts == 1 &&
          guarded.NewTasks.Count == 1 && guarded.NewProgress.Count == 1,
          "Existing Windows same-ID divergence remains conflict, not overwrite");
    Console.WriteLine("DOTNET_ACTUAL_CLIENT_CROSS_IOS_E2E_PASSED " +
        "inbound=2 outbound=1 server_cursor=3 conflicts=1");
}

// Phase31 on Ubuntu Android emulator runner: the actual StudyOne Android app
// sends TWO synthetic rows. The real .NET SyncClient decodes those exact wire
// records, uploads a third, and the restarted Android app must decode that
// .NET-origin task. The test never reads or modifies user stores.
if (Environment.GetEnvironmentVariable("STUDYONE_PHASE31_ANDROID_CROSSCLIENT") == "1")
{
    var raw = Environment.GetEnvironmentVariable("STUDYONE_E2E_BASE")
        ?? throw new InvalidOperationException("Missing disposable loopback server");
    var token = Environment.GetEnvironmentVariable("STUDYONE_SYNC_TOKEN")
        ?? throw new InvalidOperationException("Missing synthetic bearer");
    var endpoint = SyncClient.ParseEndpoint(raw);
    Check(endpoint.Scheme == "http" && endpoint.Host == "127.0.0.1"
          && endpoint.Port == 8765, "Only fixed localhost test server is allowed");
    var received = await SyncClient.GetSnapshot(token, endpoint: endpoint);
    Check(received.Cursor == 2 && received.Records.Count == 2,
          "Actual Android app must have sent two records to SQLite server");
    var androidIncoming = SyncClient.PreviewNewRemote(
        new TaskBackup { Grade = 3, ClassName = "1" },
        new ProgressBackup(), received);
    Check(androidIncoming.NewTasks.Count == 1 && androidIncoming.NewProgress.Count == 1 &&
          androidIncoming.NewTasks[0].Id == 1730000001234L &&
          androidIncoming.NewTasks[0].Title == "CI 실험 문제" &&
          androidIncoming.NewProgress[0].Unit == "7-2",
          "Windows .NET client must decode Android-origin study records");
    var fromDotnet = new StudyTask
    {
        Id = 1730000005678L, Type = "숙제 항목", Subject = "수학",
        Title = "Windows .NET Android 공유 테스트", DueDate = "2026-11-05",
        Importance = 2, Completed = false
    };
    var outgoing = SyncClient.NewOnly(
        new TaskBackup { Grade = 3, ClassName = "1", Tasks = { fromDotnet } },
        new ProgressBackup(), received);
    Check(outgoing.Count == 1 && outgoing[0].Id == fromDotnet.Id.ToString(),
          "Windows queues exactly one genuinely new task");
    Check(await SyncClient.UploadNew(token, outgoing, endpoint: endpoint) == 1,
          "Windows .NET HTTP client must validate complete server receipt");
    var final = await SyncClient.GetSnapshot(token, endpoint: endpoint);
    Check(final.Cursor == 3 && final.Records.Count == 3 &&
          final.Records.Count(r => r.Kind == "task") == 2 &&
          final.Records.Count(r => r.Kind == "progress") == 1,
          "Both real client transports must persist exactly three records");
    Console.WriteLine("DOTNET_ACTUAL_CLIENT_CROSS_ANDROID_E2E_PASSED " +
        "android_inbound=2 dotnet_outbound=1 cursor=3");
}

Console.WriteLine("StudyOne Windows portable JSON contract: PASS");
