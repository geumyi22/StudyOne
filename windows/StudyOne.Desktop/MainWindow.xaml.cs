using System.IO;
using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;

namespace StudyOne.Desktop;

public partial class MainWindow : Window
{
    private readonly ObservableCollection<StudyTask> tasks = new();
    private readonly ObservableCollection<StudyProgressItem> progress = new();
    private readonly ObservableCollection<string> taskCategories = new(StudyData.Categories);
    private TaskBackup taskBackup = new();
    private ProgressBackup progressBackup = new();
    private bool tasksLocked;
    private bool progressLocked;
    private bool syncBusy;
    private long? selectedTask;
    private string? selectedProgress;

    public MainWindow()
    {
        InitializeComponent();
        TasksGrid.ItemsSource = tasks;
        ProgressGrid.ItemsSource = progress;
        TaskTypeCombo.ItemsSource = taskCategories;
        TaskImportanceCombo.ItemsSource = new[] { 1, 2, 3 };
        ProgressStatusCombo.ItemsSource = StudyData.Statuses;
        DataPathText.Text = "이 기기의 로컬 저장 위치: " + StudyData.DataDirectory;
        try { taskBackup = StudyData.LoadTasks(); }
        catch (Exception error)
        {
            tasksLocked = true;
            ShowError("기존 일정 데이터를 읽을 수 없습니다. 원본 파일은 보존됩니다.\n" + error.Message);
        }
        try { progressBackup = StudyData.LoadProgress(); }
        catch (Exception error)
        {
            progressLocked = true;
            ShowError("기존 진도 데이터를 읽을 수 없습니다. 원본 파일은 보존됩니다.\n" + error.Message);
        }
        RefreshTasks();
        RefreshProgress();
        ClearTaskForm();
        ClearProgressForm();
        HeaderStatus.Text = tasksLocked || progressLocked
            ? "로컬 데이터 확인 필요 · 오류 항목 저장 차단"
            : "로컬 저장 · 자동 기기 동기화 없음";
    }

    private static string Clean(string value, int max, string field)
    {
        string trimmed = value.Trim();
        if (trimmed.Length == 0 || trimmed.Length > max)
            throw new InvalidDataException(field + "은(는) 1~" + max + "자여야 합니다.");
        return trimmed;
    }

    private void ShowError(string message) =>
        MessageBox.Show(this, message, "StudyOne", MessageBoxButton.OK, MessageBoxImage.Warning);

    private bool ConfirmReplace(string label, int count) =>
        MessageBox.Show(this,
            $"{label} {count}개를 가져옵니다. 기존 {label}만 교체합니다.\n현재 데이터를 먼저 백업하셨나요?",
            "기존 데이터 교체 확인",
            MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes;

    private void RefreshTasks()
    {
        tasks.Clear();
        foreach (StudyTask item in taskBackup.Tasks.OrderBy(t => t.DueDate).ThenBy(t => t.Completed))
            tasks.Add(item);
        TasksSummary.Text = tasksLocked
            ? "일정 데이터 읽기 오류 · 원본 보존 중 · 유효한 JSON을 다시 가져와 복구하세요."
            : $"전체 일정 {tasks.Count}개 · 완료 {tasks.Count(t => t.Completed)}개 · 남음 {tasks.Count(t => !t.Completed)}개";
    }

    private void RefreshProgress()
    {
        progress.Clear();
        foreach (StudyProgressItem item in progressBackup.Items
                     .OrderBy(x => x.Subject).ThenBy(x => x.Material).ThenBy(x => x.Unit))
            progress.Add(item);
        int scoped = progress.Count(x => x.InExamScope);
        int done = progress.Count(x => x.InExamScope && x.Status == "완료");
        int unknown = progress.Count(x => x.InExamScope && x.Status == "확인 필요");
        ProgressSummary.Text = progressLocked
            ? "진도 데이터 읽기 오류 · 원본 보존 중 · 유효한 JSON을 다시 가져와 복구하세요."
            : $"시험범위 진도 {scoped}개 · 완료 {done}개 · 확인 필요 {unknown}개 · 시험범위 제외 {progress.Count - scoped}개";
        ExamProgressBar.Value = scoped == 0 ? 0 : 100.0 * done / scoped;
    }

    private void ClearTaskForm()
    {
        selectedTask = null;
        TasksGrid.SelectedItem = null;
        TaskTypeCombo.SelectedItem = "숙제 항목";
        TaskSubjectInput.Text = "";
        TaskTitleInput.Text = "";
        TaskDuePicker.SelectedDate = DateTime.Today.AddDays(1);
        TaskImportanceCombo.SelectedItem = 2;
        TaskCompletedCheck.IsChecked = false;
    }

    private void ClearProgressForm()
    {
        selectedProgress = null;
        ProgressGrid.SelectedItem = null;
        ProgressSubjectInput.Text = "";
        ProgressMaterialInput.Text = "";
        ProgressUnitInput.Text = "";
        ProgressStatusCombo.SelectedItem = "미완료";
        ProgressScopeCheck.IsChecked = true;
    }

    private void TasksGrid_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (TasksGrid.SelectedItem is not StudyTask item) return;
        selectedTask = item.Id;
        if (!taskCategories.Contains(item.Type)) taskCategories.Add(item.Type);
        TaskTypeCombo.SelectedItem = item.Type;
        TaskSubjectInput.Text = item.Subject;
        TaskTitleInput.Text = item.Title;
        TaskDuePicker.SelectedDate = DateTime.TryParseExact(
            item.DueDate, "yyyy-MM-dd", CultureInfo.InvariantCulture,
            DateTimeStyles.None, out DateTime due) ? due : DateTime.Today;
        TaskImportanceCombo.SelectedItem = item.Importance;
        TaskCompletedCheck.IsChecked = item.Completed;
    }

    private void ProgressGrid_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ProgressGrid.SelectedItem is not StudyProgressItem item) return;
        selectedProgress = item.Id;
        ProgressSubjectInput.Text = item.Subject;
        ProgressMaterialInput.Text = item.Material;
        ProgressUnitInput.Text = item.Unit;
        ProgressStatusCombo.SelectedItem = item.Status;
        ProgressScopeCheck.IsChecked = item.InExamScope;
    }

    private void SaveTask_Click(object sender, RoutedEventArgs e)
    {
        if (tasksLocked) { ShowError("손상된 일정 저장소를 덮어쓸 수 없습니다. 먼저 백업에서 복원하세요."); return; }
        try
        {
            string title = Clean(TaskTitleInput.Text, 500, "할 일 제목");
            string subject = TaskSubjectInput.Text.Trim();
            string type = (TaskTypeCombo.SelectedItem as string) ?? "숙제 항목";
            DateTime date = TaskDuePicker.SelectedDate
                            ?? throw new InvalidDataException("날짜를 선택하세요.");
            int importance = (int)(TaskImportanceCombo.SelectedItem ?? 2);
            var candidate = CloneTasks();
            int index = selectedTask is null ? -1 : candidate.Tasks.FindIndex(t => t.Id == selectedTask);
            long newId = index >= 0 ? candidate.Tasks[index].Id : MakeTaskId(candidate.Tasks);
            var item = new StudyTask
            {
                Id = newId, Type = type, Subject = subject, Title = title,
                DueDate = date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                Importance = importance, Completed = TaskCompletedCheck.IsChecked == true
            };
            if (index < 0) candidate.Tasks.Add(item);
            else candidate.Tasks[index] = item;
            StudyData.SaveTasks(candidate);
            taskBackup = candidate;
            RefreshTasks();
            ClearTaskForm();
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private static long MakeTaskId(IEnumerable<StudyTask> existing)
    {
        HashSet<long> used = existing.Select(t => t.Id).ToHashSet();
        long id;
        do { id = Random.Shared.NextInt64(1, long.MaxValue); }
        while (used.Contains(id));
        return id;
    }

    private TaskBackup CloneTasks() => new()
    {
        Format = "studyone-backup", Schema = 1,
        ExportedAt = DateTime.Today.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
        Grade = taskBackup.Grade, ClassName = taskBackup.ClassName,
        School = taskBackup.School, Tasks = taskBackup.Tasks.ToList()
    };

    private void DeleteTask_Click(object sender, RoutedEventArgs e)
    {
        if (selectedTask is null) return;
        if (tasksLocked) { ShowError("일정 저장소가 읽기 전용입니다."); return; }
        if (MessageBox.Show(this, "선택한 일정을 삭제할까요?", "삭제 확인",
            MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        try
        {
            var candidate = CloneTasks();
            candidate.Tasks.RemoveAll(t => t.Id == selectedTask.Value);
            StudyData.SaveTasks(candidate);
            taskBackup = candidate;
            RefreshTasks();
            ClearTaskForm();
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void ClearTask_Click(object sender, RoutedEventArgs e) => ClearTaskForm();

    private void SaveProgress_Click(object sender, RoutedEventArgs e)
    {
        if (progressLocked) { ShowError("손상된 진도 저장소를 덮어쓸 수 없습니다. 백업 복원을 이용하세요."); return; }
        try
        {
            string subject = Clean(ProgressSubjectInput.Text, 60, "과목");
            string material = Clean(ProgressMaterialInput.Text, 120, "교재");
            string unit = Clean(ProgressUnitInput.Text, 200, "진도 항목");
            string status = (ProgressStatusCombo.SelectedItem as string) ?? "미완료";
            var candidate = new ProgressBackup
            {
                Format = "studyone-progress", Schema = 1,
                Items = progressBackup.Items.ToList()
            };
            int index = selectedProgress is null
                ? -1 : candidate.Items.FindIndex(x => x.Id == selectedProgress);
            StudyProgressItem item;
            if (index >= 0)
            {
                StudyProgressItem old = candidate.Items[index];
                if (old.Revision == long.MaxValue || old.UpdatedAt == long.MaxValue)
                    throw new InvalidDataException("진도 수정 기록 한도를 초과했습니다.");
                item = new StudyProgressItem
                {
                    Id = old.Id, Subject = subject, Material = material, Unit = unit,
                    Status = status, InExamScope = ProgressScopeCheck.IsChecked == true,
                    Revision = old.Revision + 1,
                    UpdatedAt = Math.Max(StudyData.NowMillis(), old.UpdatedAt + 1)
                };
                candidate.Items[index] = item;
            }
            else
            {
                item = new StudyProgressItem
                {
                    Id = Guid.NewGuid().ToString("D"), Subject = subject, Material = material,
                    Unit = unit, Status = status, InExamScope = ProgressScopeCheck.IsChecked == true,
                    Revision = 1, UpdatedAt = StudyData.NowMillis()
                };
                candidate.Items.Add(item);
            }
            StudyData.SaveProgress(candidate);
            progressBackup = candidate;
            RefreshProgress();
            ClearProgressForm();
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void DeleteProgress_Click(object sender, RoutedEventArgs e)
    {
        if (selectedProgress is null) return;
        if (progressLocked) { ShowError("진도 저장소가 읽기 전용입니다."); return; }
        if (MessageBox.Show(this, "선택한 진도 기록을 삭제할까요?", "삭제 확인",
            MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        try
        {
            var candidate = new ProgressBackup
            {
                Format = "studyone-progress", Schema = 1,
                Items = progressBackup.Items.Where(x => x.Id != selectedProgress).ToList()
            };
            StudyData.SaveProgress(candidate);
            progressBackup = candidate;
            RefreshProgress();
            ClearProgressForm();
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void ClearProgress_Click(object sender, RoutedEventArgs e) => ClearProgressForm();

    private static string? ChooseSave(string suggested)
    {
        var picker = new SaveFileDialog
        {
            Title = "StudyOne JSON 내보내기",
            FileName = suggested, Filter = "JSON 파일 (*.json)|*.json",
            DefaultExt = ".json", AddExtension = true
        };
        return picker.ShowDialog() == true ? picker.FileName : null;
    }

    private static string? ChooseOpen()
    {
        var picker = new OpenFileDialog
        {
            Title = "StudyOne JSON 가져오기", Filter = "JSON 파일 (*.json)|*.json",
            CheckFileExists = true, Multiselect = false
        };
        return picker.ShowDialog() == true ? picker.FileName : null;
    }

    private void ExportTasks_Click(object sender, RoutedEventArgs e)
    {
        if (tasksLocked) { ShowError("일정 원본을 읽을 수 없어 백업을 만들지 않습니다."); return; }
        string? path = ChooseSave("StudyOne-backup.json");
        if (path == null) return;
        try
        {
            var bytes = StudyData.SerializeAndValidateTasks(CloneTasks());
            File.WriteAllBytes(path, bytes);
            MessageBox.Show(this, "일정 백업 파일을 저장했습니다.");
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void ImportTasks_Click(object sender, RoutedEventArgs e)
    {
        string? path = ChooseOpen();
        if (path == null) return;
        try
        {
            TaskBackup imported = StudyData.ParseTasks(StudyData.ReadLimited(path));
            if (!ConfirmReplace("일정", imported.Tasks.Count)) return;
            StudyData.SaveTasks(imported);
            taskBackup = imported;
            tasksLocked = false;
            RefreshTasks();
            ClearTaskForm();
            HeaderStatus.Text = "로컬 저장 · 자동 기기 동기화 없음";
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void ExportProgress_Click(object sender, RoutedEventArgs e)
    {
        if (progressLocked) { ShowError("진도 원본을 읽을 수 없어 백업을 만들지 않습니다."); return; }
        string? path = ChooseSave("StudyOne-progress.json");
        if (path == null) return;
        try
        {
            var bytes = StudyData.SerializeAndValidateProgress(progressBackup);
            File.WriteAllBytes(path, bytes);
            MessageBox.Show(this, "진도 백업 파일을 저장했습니다.");
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void ImportProgress_Click(object sender, RoutedEventArgs e)
    {
        string? path = ChooseOpen();
        if (path == null) return;
        try
        {
            ProgressBackup imported = StudyData.ParseProgress(StudyData.ReadLimited(path));
            if (!ConfirmReplace("진도", imported.Items.Count)) return;
            StudyData.SaveProgress(imported);
            progressBackup = imported;
            progressLocked = false;
            RefreshProgress();
            ClearProgressForm();
            HeaderStatus.Text = "로컬 저장 · 자동 기기 동기화 없음";
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private bool BeginSync()
    {
        if (syncBusy) return false;
        syncBusy = true;
        SyncPreviewButton.IsEnabled = false;
        SyncUploadButton.IsEnabled = false;
        SyncPullTasksButton.IsEnabled = false;
        SyncPullProgressButton.IsEnabled = false;
        SyncResultText.Text = "로컬 동기화 서버 확인 중…";
        return true;
    }

    private void EndSync()
    {
        syncBusy = false;
        SyncPreviewButton.IsEnabled = true;
        SyncUploadButton.IsEnabled = true;
        SyncPullTasksButton.IsEnabled = true;
        SyncPullProgressButton.IsEnabled = true;
    }

    private async void SyncPreview_Click(object sender, RoutedEventArgs e)
    {
        if (!BeginSync()) return;
        try
        {
            var endpoint = SyncClient.ParseEndpoint(SyncUrlInput.Text.Trim());
            var snapshot = await SyncClient.GetSnapshot(SyncTokenInput.Password,
                                                       endpoint: endpoint);
            int totalTasks = snapshot.Records.Count(x => x.Kind == "task");
            int totalProgress = snapshot.Records.Count(x => x.Kind == "progress");
            SyncResultText.Text =
                $"로컬 서버 연결 성공 · 서버 변경 cursor {snapshot.Cursor}\n" +
                $"서버에 저장된 일정 {totalTasks}개 · 진도 {totalProgress}개\n" +
                "이 기기의 데이터는 읽거나 수정하지 않았고, 서버에 아무것도 업로드하지 않았습니다.";
        }
        catch (Exception error)
        {
            SyncResultText.Text =
                "로컬 서버 읽기 실패. 127.0.0.1:8765에 서버가 실행 중인지, 토큰이 맞는지 확인하세요.";
            ShowError("동기화 연결을 확인하세요.\n" + error.Message);
        }
        finally { EndSync(); }
    }

    private async void SyncUpload_Click(object sender, RoutedEventArgs e)
    {
        if (!BeginSync()) return;
        try
        {
            if (tasksLocked || progressLocked)
                throw new InvalidDataException("로컬 일정/진도 원본 읽기에 문제가 있어 전송을 차단했습니다.");

            string token = SyncTokenInput.Password;
            var endpoint = SyncClient.ParseEndpoint(SyncUrlInput.Text.Trim());
            var snapshot = await SyncClient.GetSnapshot(token, endpoint: endpoint);
            var pending = SyncClient.NewOnly(CloneTasks(), progressBackup, snapshot);
            int localTotal = taskBackup.Tasks.Count + progressBackup.Items.Count;
            int skipped = localTotal - pending.Count;
            if (pending.Count == 0)
            {
                SyncResultText.Text =
                    $"전송할 새 ID가 없습니다. 서버 중복 ID {skipped}개를 보존했습니다.\n" +
                    "기존 기록이 같은지 비교하거나 갱신하는 기능은 아직 구현하지 않았습니다.";
                return;
            }
            var answer = MessageBox.Show(this,
                $"현재 PC의 일정·진도 중 서버에 없는 {pending.Count}개를 " +
                "로컬 StudyOne 동기화 서버로 보낼까요?\n\n" +
                $"서버에 이미 존재하는 ID {skipped}개는 건너뜁니다. " +
                "서버 기존 데이터를 수정·삭제하지 않으며, 현재 PC 데이터도 변경하지 않습니다.\n\n" +
                "교재·과목·개인 학습 기록이 서버의 SQLite DB에 저장됩니다.",
                "새 학습 기록 전송 승인",
                MessageBoxButton.YesNo, MessageBoxImage.Warning);
            if (answer != MessageBoxResult.Yes)
            {
                SyncResultText.Text = "전송을 취소했습니다. 개인 학습 데이터는 업로드되지 않았습니다.";
                return;
            }
            int sent = await SyncClient.UploadNew(token, pending, endpoint: endpoint);
            SyncResultText.Text =
                $"새 기록 {sent}개를 로컬 서버로 보냈습니다.\n" +
                $"기존 ID {skipped}개는 건너뛰었습니다.\n" +
                "이 PC의 기존 기록은 변경하지 않았습니다. 다른 기기의 자동 동기화는 아직 지원하지 않습니다.";
        }
        catch (Exception error)
        {
            SyncResultText.Text = "동기화 전송이 중단되었습니다. 일부 배치는 서버에 저장됐을 수 있습니다." +
                                  " 서버 목록을 다시 읽어 확인하세요.";
            ShowError("동기화 오류\n" + error.Message);
        }
        finally { EndSync(); }
    }


    private async void SyncPullTasks_Click(object sender, RoutedEventArgs e) =>
        await PullNewRemote(taskKind: true);

    private async void SyncPullProgress_Click(object sender, RoutedEventArgs e) =>
        await PullNewRemote(taskKind: false);

    /// <summary>
    /// Explicit add-only inbound merge. User approval, second server fetch,
    /// unchanged local bytes and a local backup are required before one file
    /// is atomically replaced. Never imports deletes/edits of existing IDs.
    /// </summary>
    private async Task PullNewRemote(bool taskKind)
    {
        if (!BeginSync()) return;
        try
        {
            if (tasksLocked || progressLocked)
                throw new InvalidDataException(
                    "로컬 저장 데이터가 손상된 상태에서는 가져올 수 없습니다.");

            string token = SyncTokenInput.Password;
            Uri endpoint = SyncClient.ParseEndpoint(SyncUrlInput.Text.Trim());
            // Read the actual persisted files rather than potentially stale UI state.
            TaskBackup originalTasks = StudyData.LoadTasks();
            ProgressBackup originalProgress = StudyData.LoadProgress();
            byte[] taskFingerprint = StudyData.SerializeAndValidateTasks(originalTasks);
            byte[] progressFingerprint = StudyData.SerializeAndValidateProgress(originalProgress);

            var first = await SyncClient.GetSnapshot(token, endpoint: endpoint);
            var proposed = SyncClient.PreviewNewRemote(originalTasks, originalProgress, first);
            int incoming = taskKind ? proposed.NewTasks.Count : proposed.NewProgress.Count;
            string kind = taskKind ? "일정" : "진도";
            if (incoming == 0)
            {
                SyncResultText.Text = $"가져올 새 {kind} ID가 없습니다.\n" +
                    $"기존 ID 충돌/차이 {proposed.Conflicts}개는 안전을 위해 건너뛰었습니다.";
                return;
            }
            if (MessageBox.Show(this,
                    $"서버에서 현재 PC에 없는 새 {kind} {incoming}개를 추가할까요?\n\n" +
                    $"서버에서 발견한 기존 ID 충돌 {proposed.Conflicts}개는 덮어쓰지 않습니다.\n" +
                    "현재 PC의 기존 데이터는 변경하지 않으며, 교체 전 백업 파일을 만듭니다.\n" +
                    "이 실행 이후에 다른 장치에서 서버를 수정했다면 가져오기를 취소합니다.",
                    "신규 항목 가져오기 승인", MessageBoxButton.YesNo,
                    MessageBoxImage.Warning) != MessageBoxResult.Yes)
            {
                SyncResultText.Text = "서버 신규 기록 가져오기를 취소했습니다.";
                return;
            }
            var second = await SyncClient.GetSnapshot(token, endpoint: endpoint);
            if (first.Cursor != second.Cursor)
                throw new InvalidOperationException(
                    "서버 기록이 승인 중 변경되었습니다. 다시 미리보기부터 확인하세요.");
            var latestTasks = StudyData.LoadTasks();
            var latestProgress = StudyData.LoadProgress();
            if (!StudyData.SerializeAndValidateTasks(latestTasks).SequenceEqual(taskFingerprint) ||
                !StudyData.SerializeAndValidateProgress(latestProgress).SequenceEqual(progressFingerprint))
                throw new InvalidOperationException(
                    "PC 저장 데이터가 승인 중 변경되었습니다. 처음부터 다시 확인하세요.");
            var verified = SyncClient.PreviewNewRemote(latestTasks, latestProgress, second);
            var initialIds = taskKind
                ? proposed.NewTasks.Select(t => t.Id.ToString()).OrderBy(x => x)
                : proposed.NewProgress.Select(p => p.Id).OrderBy(x => x);
            var confirmedIds = taskKind
                ? verified.NewTasks.Select(t => t.Id.ToString()).OrderBy(x => x)
                : verified.NewProgress.Select(p => p.Id).OrderBy(x => x);
            if (!initialIds.SequenceEqual(confirmedIds) ||
                proposed.Conflicts != verified.Conflicts)
                throw new InvalidOperationException("동기화 후보가 변경되어 저장하지 않았습니다.");

            string file = taskKind ? StudyData.TasksPath : StudyData.ProgressPath;
            string backupLocation = "";
            // No change occurs if backup creation fails.
            if (File.Exists(file))
            {
                backupLocation = file + ".before-sync-" +
                    DateTime.UtcNow.ToString("yyyyMMddHHmmss") + "-" +
                    Guid.NewGuid().ToString("N") + ".bak";
                File.Copy(file, backupLocation, overwrite: false);
            }
            if (taskKind)
            {
                var merged = SyncClient.MakeMergedTasks(latestTasks, verified);
                StudyData.SaveTasks(merged);
                taskBackup = merged;
                RefreshTasks();
                ClearTaskForm();
            }
            else
            {
                var merged = SyncClient.MakeMergedProgress(latestProgress, verified);
                StudyData.SaveProgress(merged);
                progressBackup = merged;
                RefreshProgress();
                ClearProgressForm();
            }
            SyncResultText.Text = $"서버의 신규 {kind} {incoming}개를 PC에 추가했습니다.\n" +
                $"기존 ID 충돌 {verified.Conflicts}개는 변경하지 않았습니다.\n" +
                (string.IsNullOrEmpty(backupLocation)
                    ? "기존 저장 파일이 없어 최초 데이터 파일로 기록했습니다."
                    : "기존 데이터의 안전 복사본: " + backupLocation);
        }
        catch (Exception error)
        {
            SyncResultText.Text = "서버 신규 기록 가져오기가 중단되었습니다. " +
                                  "기존 ID는 자동 변경하지 않습니다.";
            ShowError("가져오기 오류\n" + error.Message);
        }
        finally { EndSync(); }
    }

}
