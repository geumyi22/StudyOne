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
Console.WriteLine("StudyOne Windows portable JSON contract: PASS");
