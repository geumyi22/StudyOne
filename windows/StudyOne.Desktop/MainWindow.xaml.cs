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
    private readonly ObservableCollection<StudyWrongAnswer> wrongAnswers = new();
    private readonly ObservableCollection<string> taskCategories = new(StudyData.Categories);
    private TaskBackup taskBackup = new();
    private ProgressBackup progressBackup = new();
    private TaskDetailsBackup detailBackup = new();
    private WrongAnswersBackup wrongBackup = new();
    private bool wrongLocked;
    private string? selectedWrong;
    private string? writingTarget;
    private bool detailsLocked;
    private bool tasksLocked;
    private bool progressLocked;
    private bool syncBusy;
    private long? selectedTask;
    private string? selectedProgress;
    private int taskDisplayMode; // 0=today + overdue & tomorrow homework, 1=tomorrow, 2=all

    public MainWindow()
    {
        InitializeComponent();
        TasksGrid.ItemsSource = tasks;
        ProgressGrid.ItemsSource = progress;
        WrongGrid.ItemsSource = wrongAnswers;
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
        try { detailBackup = StudyTaskDetailsData.Load(); }
        catch (Exception error)
        {
            detailsLocked = true;
            ShowError("기존 학습 상세 정보를 읽지 못했습니다. 원본은 보존하며 변경을 차단합니다.\n" + error.Message);
        }
        try { wrongBackup = StudyWrongAnswersData.Load(); }
        catch (Exception error)
        {
            wrongLocked = true;
            ShowError("오답 원본을 읽지 못했습니다. 기존 원본을 보존합니다.\n" + error.Message);
        }
        RefreshTasks();
        TaskDateFilterCombo.SelectedIndex = 0;
        RefreshProgress();
        RefreshWrong();
        ClearWrongForm();
        ClearTaskForm();
        ClearProgressForm();
        HeaderStatus.Text = tasksLocked || progressLocked || detailsLocked || wrongLocked
            ? "로컬 데이터 확인 필요 · 오류 항목 저장 차단"
            : "로컬 저장 · 자동 기기 동기화 없음";
        // A process could have stopped halfway through replacing four files.
        // Block local edits until the user explicitly repairs the original snapshot.
        if (StudyPortableBundle.Pending(StudyData.DataDirectory).Count > 0)
        {
            tasksLocked = progressLocked = detailsLocked = wrongLocked = true;
            HeaderStatus.Text = "4종 통합 가져오기 중단 감지 · 백업 탭에서 원본 복구 필요";
            ShowError("이전 4종 통합 가져오기가 중단됐습니다. 기존 기록을 보호하려면 '백업 · 가져오기' 탭에서 원본 복구를 실행하세요.");
        }
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

    private void TaskDateFilter_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (TaskDateFilterCombo is null || TasksGrid is null || TasksSummary is null) return;
        taskDisplayMode = Math.Clamp(TaskDateFilterCombo.SelectedIndex, 0, 2);
        RefreshTasks();
    }

    private void RefreshTasks()
    {
        IReadOnlyList<StudyTask> visible = StudyTaskViews.Select(
            taskBackup.Tasks, DateOnly.FromDateTime(DateTime.Today), taskDisplayMode);
        tasks.Clear();
        foreach (StudyTask item in visible)
            tasks.Add(item);
        string scope = taskDisplayMode == 0 ? "오늘·이월·내일 숙제 미리 하기"
                     : taskDisplayMode == 1 ? "내일 마감" : "전체 날짜";
        TasksSummary.Text = tasksLocked
            ? "일정 데이터 읽기 오류 · 원본 보존 중 · 유효한 JSON을 다시 가져와 복구하세요."
            : $"{scope} · 표시 {tasks.Count}개 / 원본 {taskBackup.Tasks.Count}개"
              + $" · 완료 {tasks.Count(t => t.Completed)}개 · 남음 {tasks.Count(t => !t.Completed)}개";
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
        int started = progress.Count(x => x.InExamScope && x.Status == "진행 중");
        int todo = progress.Count(x => x.InExamScope && x.Status == "미완료");
        ProgressSummary.Text = progressLocked
            ? "진도 데이터 읽기 오류 · 원본 보존 중 · 유효한 JSON을 다시 가져와 복구하세요."
            : $"시험범위 {scoped}개 · 완료 {done} · 진행 중 {started} · 미완료 {todo} · 확인 필요 {unknown} · 시험범위 제외 {progress.Count - scoped}";
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
        TaskDetailMinutesInput.Text = "";
        TaskDetailStepsInput.Text = "";
        TaskDetailCriteriaInput.Text = "";
    }

    private void LoadTaskDetailFields(long taskId)
    {
        StudyTaskDetail? detail = detailBackup.Items.FirstOrDefault(x => x.TaskId == taskId);
        TaskDetailMinutesInput.Text = detail?.EstimatedMinutes?.ToString(CultureInfo.InvariantCulture) ?? "";
        TaskDetailStepsInput.Text = detail is null ? "" : string.Join(Environment.NewLine, detail.Steps);
        TaskDetailCriteriaInput.Text = detail?.CompletionCriteria ?? "";
    }

    private void RefreshWrong()
    {
        wrongAnswers.Clear();
        foreach (var record in wrongBackup.Items.OrderBy(x => x.NextReview).ThenBy(x => x.Subject))
            wrongAnswers.Add(record);
        int due = wrongAnswers.Count(x => !x.Mastered && string.CompareOrdinal(x.NextReview,
            DateOnly.FromDateTime(DateTime.Today).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)) <= 0);
        WrongSummary.Text = wrongLocked ? "오답 저장소 확인 오류 · 데이터 보존 중" :
            $"등록된 오답 {wrongAnswers.Count}개 · 오늘까지 복습 대상 {due}개";
    }

    private void ClearWrongForm()
    {
        selectedWrong = null;
        WrongGrid.SelectedItem = null;
        WrongSubjectInput.Text = "";
        WrongMaterialInput.Text = "";
        WrongTopicInput.Text = "";
        WrongCorrectionInput.Text = "";
        WrongReviewPicker.SelectedDate = DateTime.Today.AddDays(1);
    }

    private void WrongGrid_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (WrongGrid.SelectedItem is not StudyWrongAnswer item) return;
        selectedWrong = item.Id;
        WrongSubjectInput.Text = item.Subject;
        WrongMaterialInput.Text = item.Material;
        WrongTopicInput.Text = item.Topic;
        WrongCorrectionInput.Text = item.Correction;
        WrongReviewPicker.SelectedDate = DateTime.TryParseExact(item.NextReview,"yyyy-MM-dd",
            CultureInfo.InvariantCulture, DateTimeStyles.None, out DateTime d) ? d : DateTime.Today;
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
        LoadTaskDetailFields(item.Id);
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

    private void CopyToday_Click(object sender, RoutedEventArgs e)
    {
        if (tasksLocked || detailsLocked) {
            ShowError("일정 또는 상세 정보를 읽지 못해 복사를 차단했습니다."); return;
        }
        try
        {
            Clipboard.SetText(StudyChecklistText.Daily(
                taskBackup.Tasks, DateOnly.FromDateTime(DateTime.Today),
                detailBackup.Items.ToDictionary(x => x.TaskId)));
            HeaderStatus.Text = "오늘 할꺼를 클립보드로 복사했습니다 · 로컬 작업";
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void CopyProgress_Click(object sender, RoutedEventArgs e)
    {
        if (progressLocked) { ShowError("진도 데이터를 읽지 못해 복사를 차단했습니다."); return; }
        try
        {
            Clipboard.SetText(StudyChecklistText.Progress(progressBackup.Items));
            HeaderStatus.Text = "과목별 진도를 클립보드로 복사했습니다 · 로컬 작업";
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void QuickAddOnlinePractice_Click(object sender, RoutedEventArgs e)
    {
        if (progressLocked) {
            ShowError("손상된 진도 저장소에는 새 항목을 추가하지 않습니다."); return;
        }
        try
        {
            byte[]? original = File.Exists(StudyData.ProgressPath)
                ? StudyData.ReadLimited(StudyData.ProgressPath) : null;
            ProgressBackup onDisk = original is null ? new ProgressBackup()
                : StudyData.ParseProgress(original);
            // Avoid overwriting records updated outside this view.
            if (!StudyData.SerializeAndValidateProgress(onDisk).SequenceEqual(
                    StudyData.SerializeAndValidateProgress(progressBackup)))
                throw new InvalidDataException("앱 밖에서 진도가 변경됐습니다. 다시 읽어 확인하세요.");

            var missing = StudyOnlinePractice.MissingSubjects(onDisk.Items);
            if (missing.Count == 0)
            {
                MessageBox.Show(this,"6과목 모두 이미 온라인 내신 문제가 있습니다. 기존 상태는 유지했습니다.");
                return;
            }
            if (MessageBox.Show(this,
                $"신규 {missing.Count}개: {string.Join(", ",missing)}\n" +
                "새 항목은 미완료·시험범위 포함으로 저장합니다.\n" +
                "현재 데이터는 Recovery 폴더에 먼저 백업하며 기존 상태는 바꾸지 않습니다. 계속할까요?",
                "온라인 내신 문제 빠른 추가", MessageBoxButton.YesNo,MessageBoxImage.Question)
                != MessageBoxResult.Yes) return;
            var additions = StudyOnlinePractice.CreateMissing(onDisk.Items);
            var proposed = new ProgressBackup
            {
                Items = onDisk.Items.Concat(additions).ToList()
            };
            string recovery = StudyData.SaveProgressWithRecovery(proposed, original);
            progressBackup = proposed;
            RefreshProgress(); ClearProgressForm();
            HeaderStatus.Text = $"온라인 내신 문제 {additions.Count}개 추가 · 기존 원본 보관: {recovery}";
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void ToggleTaskRow_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not CheckBox { DataContext: StudyTask original }) return;
        if (tasksLocked) { ShowError("손상된 일정은 수정할 수 없습니다."); RefreshTasks(); return; }
        try
        {
            TaskBackup next = CloneTasks();
            int index = next.Tasks.FindIndex(t => t.Id == original.Id);
            if (index < 0) throw new InvalidDataException("선택한 일정을 찾을 수 없습니다.");
            StudyTask current = next.Tasks[index];
            // Copy the value: never mutate the shared object before durable validation.
            next.Tasks[index] = new StudyTask
            {
                Id = current.Id, Type = current.Type, Subject = current.Subject,
                Title = current.Title, DueDate = current.DueDate,
                Importance = current.Importance, Completed = !current.Completed
            };
            StudyData.SaveTasks(next);
            taskBackup = next;
            RefreshTasks();
        }
        catch (Exception error) { ShowError(error.Message); RefreshTasks(); }
    }

    private void ToggleProgressRow_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not CheckBox { DataContext: StudyProgressItem original }) return;
        if (progressLocked) { ShowError("손상된 진도는 수정할 수 없습니다."); RefreshProgress(); return; }
        try
        {
            int index = progressBackup.Items.FindIndex(x => x.Id == original.Id);
            if (index < 0) throw new InvalidDataException("선택한 진도를 찾을 수 없습니다.");
            StudyProgressItem current = progressBackup.Items[index];
            if (current.Revision == long.MaxValue || current.UpdatedAt == long.MaxValue)
                throw new InvalidDataException("진도 수정 기록 한도를 초과했습니다.");
            var next = new ProgressBackup
            {
                Format = "studyone-progress", Schema = 1, Items = progressBackup.Items.ToList()
            };
            next.Items[index] = new StudyProgressItem
            {
                Id = current.Id, Subject = current.Subject, Material = current.Material,
                Unit = current.Unit, InExamScope = current.InExamScope,
                Status = current.Status == "완료" ? "미완료" : "완료",
                Revision = current.Revision + 1,
                UpdatedAt = Math.Max(StudyData.NowMillis(), current.UpdatedAt + 1)
            };
            StudyData.SaveProgress(next);
            progressBackup = next;
            RefreshProgress();
        }
        catch (Exception error) { ShowError(error.Message); RefreshProgress(); }
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

    private void ExportTransfer_Click(object sender, RoutedEventArgs e)
    {
        if (tasksLocked || progressLocked || detailsLocked || wrongLocked ||
            StudyPortableBundle.Pending(StudyData.DataDirectory).Count != 0)
        {
            ShowError("4종 데이터 중 손상 또는 미완료 복구가 있어 통합 내보내기를 차단했습니다.");
            return;
        }
        string? path = ChooseSave("StudyOne-fourstore-transfer.json");
        if (path is null) return;
        try
        {
            string localRoot = Path.GetFullPath(StudyData.DataDirectory)
                .TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            if (Path.GetFullPath(path).StartsWith(localRoot, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("StudyOne 내부 데이터 경로에는 외부 통합 백업을 저장할 수 없습니다.");
            string serialized = StudyPortableBundle.Encode(
                StudyPortableBundle.Capture(StudyData.DataDirectory));
            StudyData.SaveAtomic(path, System.Text.Encoding.UTF8.GetBytes(serialized));
            HeaderStatus.Text = "4종 통합 백업 저장 완료 · 비밀키는 포함되지 않음";
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void ImportTransfer_Click(object sender, RoutedEventArgs e)
    {
        if (tasksLocked || progressLocked || detailsLocked || wrongLocked)
        {
            ShowError("현재 원본이 손상되었거나 복구가 필요합니다. 통합 가져오기를 차단합니다.");
            return;
        }
        string? path = ChooseOpen();
        if (path is null) return;
        try
        {
            var info = new FileInfo(path);
            if (info.Length > 8_200_000)
                throw new InvalidDataException("4종 통합 백업은 최대 8.2MB입니다.");
            string source = File.ReadAllText(path, System.Text.Encoding.UTF8);
            var incoming = StudyPortableBundle.Parse(source);
            var approved = StudyPortableBundle.Capture(StudyData.DataDirectory);
            if (MessageBox.Show(this,
                incoming.Summary + "\n4종 기록 모두 이 파일의 내용으로 교체합니다.\n"
                + "원본 4종을 Recovery 폴더에 저장한 후 진행합니다.\n"
                + "기록을 외부 파일에도 따로 백업했는지 확인하세요. 계속할까요?",
                "4종 통합 가져오기", MessageBoxButton.YesNo, MessageBoxImage.Warning)
                != MessageBoxResult.Yes) return;
            string recovery = StudyPortableBundle.Apply(
                incoming, approved, StudyData.DataDirectory);
            taskBackup = StudyData.LoadTasks();
            progressBackup = StudyData.LoadProgress();
            detailBackup = StudyTaskDetailsData.Load();
            wrongBackup = StudyWrongAnswersData.Load();
            RefreshTasks(); RefreshProgress(); RefreshWrong();
            ClearTaskForm(); ClearProgressForm(); ClearWrongForm();
            HeaderStatus.Text = "4종 통합 복구 완료 · 이전 원본 보존: " + recovery;
        }
        catch (Exception error)
        {
            if (StudyPortableBundle.Pending(StudyData.DataDirectory).Count > 0)
                tasksLocked = progressLocked = detailsLocked = wrongLocked = true;
            ShowError(error.Message);
        }
    }

    private void RecoverTransfer_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var pending = StudyPortableBundle.Pending(StudyData.DataDirectory);
            if (pending.Count == 0)
            {
                MessageBox.Show(this, "중단된 4종 통합 가져오기 작업이 없습니다.");
                return;
            }
            if (pending.Count != 1)
                throw new InvalidDataException("미완료 복구 작업이 여러 개입니다. 관리자가 원본을 확인해야 합니다.");
            if (MessageBox.Show(this,
                "중단된 통합 가져오기 이전의 4종 원본 파일로 복원합니다.\n"
                + "현재 일부 파일에 반영된 가져오기 결과는 덮어써집니다.\n"
                + pending[0] + "\n계속할까요?",
                "원본 4종 복구 확인", MessageBoxButton.YesNo, MessageBoxImage.Warning)
                != MessageBoxResult.Yes) return;
            StudyPortableBundle.Recover(pending[0], StudyData.DataDirectory);
            taskBackup = StudyData.LoadTasks();
            progressBackup = StudyData.LoadProgress();
            detailBackup = StudyTaskDetailsData.Load();
            wrongBackup = StudyWrongAnswersData.Load();
            tasksLocked = progressLocked = detailsLocked = wrongLocked = false;
            RefreshTasks(); RefreshProgress(); RefreshWrong();
            ClearTaskForm(); ClearProgressForm(); ClearWrongForm();
            HeaderStatus.Text = "중단된 4종 가져오기 이전 원본 복구 완료";
        }
        catch (Exception error) { ShowError(error.Message); }
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

    private void SaveTaskDetail_Click(object sender, RoutedEventArgs e)
    {
        if (tasksLocked || detailsLocked) {
            ShowError("일정 또는 상세 저장소가 손상되어 수정을 차단했습니다."); return;
        }
        if (selectedTask is not long id || !taskBackup.Tasks.Any(t => t.Id == id)) {
            ShowError("먼저 기존 일정을 한 개 선택하세요."); return;
        }
        try
        {
            string raw = TaskDetailMinutesInput.Text.Trim();
            int? minutes = null;
            if (raw.Length != 0)
            {
                if (!int.TryParse(raw, NumberStyles.None, CultureInfo.InvariantCulture, out int value))
                    throw new InvalidDataException("예상 시간은 1~600분 정수여야 합니다.");
                minutes = value;
            }
            var steps = TaskDetailStepsInput.Text.Replace("\r\n", "\n").Replace('\r', '\n')
                .Split('\n').Select(x => x.Trim()).Where(x => x.Length > 0).ToList();
            var entry = StudyTaskDetailsData.Validate(new StudyTaskDetail {
                TaskId = id, EstimatedMinutes = minutes, Steps = steps,
                CompletionCriteria = TaskDetailCriteriaInput.Text.Trim()
            });
            var proposed = new TaskDetailsBackup {
                Items = detailBackup.Items.Where(t => t.TaskId != id).ToList()
            };
            if (!entry.IsEmpty) proposed.Items.Add(entry);
            StudyTaskDetailsData.Save(proposed);
            detailBackup = proposed;
            HeaderStatus.Text = "선택한 일정의 학습 상세를 로컬에 저장했습니다.";
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void ExportTaskDetails_Click(object sender, RoutedEventArgs e)
    {
        if (detailsLocked) { ShowError("상세 원본 오류로 내보내기를 차단했습니다."); return; }
        string? path = ChooseSave("StudyOne-task-details.json");
        if (path == null) return;
        try
        {
            File.WriteAllBytes(path, StudyTaskDetailsData.Serialize(detailBackup));
            HeaderStatus.Text = "학습 상세 JSON 백업을 저장했습니다.";
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void ImportTaskDetails_Click(object sender, RoutedEventArgs e)
    {
        if (detailsLocked) { ShowError("기존 상세 원본에 오류가 있습니다. 원본을 보존한 채 복구 절차를 확인하세요."); return; }
        string? path = ChooseOpen();
        if (path == null) return;
        try
        {
            byte[] incoming = StudyData.ReadLimited(path);
            TaskDetailsBackup parsed = StudyTaskDetailsData.Parse(incoming);
            byte[]? original = File.Exists(StudyTaskDetailsData.PathName)
                ? StudyData.ReadLimited(StudyTaskDetailsData.PathName) : null;
            if (original is not null) StudyTaskDetailsData.Parse(original);
            if (MessageBox.Show(this,
                    $"학습 상세 {parsed.Items.Count}개를 가져옵니다. 기존 학습 상세만 교체하며 " +
                    "현재 원본 바이트를 앱 내부에 백업합니다.\n학교·일정·진도·서버 계정은 변경하지 않습니다. 계속할까요?",
                    "학습 상세 교체 확인", MessageBoxButton.YesNo,
                    MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
            StudyTaskDetailsData.ImportWithBackup(incoming, original);
            detailBackup = parsed;
            if (selectedTask is long id) LoadTaskDetailFields(id);
            HeaderStatus.Text = "학습 상세 JSON 복원 완료 · 기존 원본 백업 보존";
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void WritingStart_Click(object sender, RoutedEventArgs e)
    {
        string target = WritingTargetInput.Text.Trim();
        if (target.Length is < 1 or > 1500) {
            ShowError("암기할 문장을 1~1500자로 입력하세요."); return;
        }
        writingTarget = target;
        WritingTargetInput.Text = "";
        WritingTargetInput.Visibility = Visibility.Collapsed;
        WritingAttemptInput.Text = "";
        WritingResultText.Text = "정답을 가렸습니다. 문장을 입력한 뒤 채점하세요.";
    }

    private void WritingCheck_Click(object sender, RoutedEventArgs e)
    {
        if (writingTarget is null) { ShowError("먼저 암기 문장을 입력하고 가리세요."); return; }
        string answer = WritingAttemptInput.Text.Trim();
        if (answer.Length == 0) { ShowError("기억나는 문장을 입력하세요."); return; }
        static string Normalized(string text) =>
            string.Join(" ", text.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
        WritingResultText.Text = Normalized(answer) == Normalized(writingTarget)
            ? "정확하게 작성했습니다."
            : "다른 부분이 있습니다.\n정답: " + writingTarget + "\n입력한 문장: " + answer +
              "\n대문자·구두점까지 구분합니다.";
    }

    private void WritingReset_Click(object sender, RoutedEventArgs e)
    {
        writingTarget = null;
        WritingTargetInput.Text = "";
        WritingTargetInput.Visibility = Visibility.Visible;
        WritingAttemptInput.Text = "";
        WritingResultText.Text = "";
    }

    private void SaveWrong_Click(object sender, RoutedEventArgs e)
    {
        if (wrongLocked) { ShowError("오답 원본이 손상되어 수정을 차단했습니다."); return; }
        try
        {
            string id = selectedWrong ?? Guid.NewGuid().ToString("D");
            StudyWrongAnswer? previous = wrongBackup.Items.FirstOrDefault(x => x.Id == id);
            var item = StudyWrongAnswersData.Validate(new StudyWrongAnswer {
                Id = id,
                Subject = WrongSubjectInput.Text.Trim(),
                Material = WrongMaterialInput.Text.Trim(),
                Topic = WrongTopicInput.Text.Trim(),
                Correction = WrongCorrectionInput.Text.Trim(),
                NextReview = (WrongReviewPicker.SelectedDate ?? DateTime.Today.AddDays(1))
                    .ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                Retries = previous?.Retries ?? 0,
                Mastered = previous?.Mastered ?? false
            });
            var next = new WrongAnswersBackup {
                Items = wrongBackup.Items.Where(x => x.Id != id).ToList()
            };
            next.Items.Add(item);
            StudyWrongAnswersData.Save(next);
            wrongBackup = next; RefreshWrong(); ClearWrongForm();
            HeaderStatus.Text = "오답을 기기에 저장했습니다.";
        }
        catch (Exception error) { ShowError(error.Message); }
    }

    private void ClearWrong_Click(object sender, RoutedEventArgs e) => ClearWrongForm();

    private void UpdateWrong(string action)
    {
        if (wrongLocked || selectedWrong is null) { ShowError("기존 오답을 선택하세요."); return; }
        try
        {
            var next = new WrongAnswersBackup { Items = wrongBackup.Items.Select(x => {
                if (x.Id != selectedWrong) return x;
                return StudyWrongAnswersData.Validate(new StudyWrongAnswer {
                    Id = x.Id, Subject = x.Subject, Material = x.Material,
                    Topic = x.Topic, Correction = x.Correction,
                    NextReview = action == "retry"
                        ? DateOnly.FromDateTime(DateTime.Today.AddDays(1)).ToString("yyyy-MM-dd")
                        : x.NextReview,
                    Retries = action == "retry" ? Math.Min(50,x.Retries + 1) : x.Retries,
                    Mastered = action == "retry" ? false : !x.Mastered
                });
            }).ToList() };
            StudyWrongAnswersData.Save(next);
            wrongBackup = next;
            RefreshWrong(); ClearWrongForm();
        } catch (Exception error) { ShowError(error.Message); }
    }
    private void RetryWrong_Click(object sender, RoutedEventArgs e) => UpdateWrong("retry");
    private void MasterWrong_Click(object sender, RoutedEventArgs e) => UpdateWrong("master");

    private void ExportWrong_Click(object sender, RoutedEventArgs e)
    {
        if (wrongLocked) { ShowError("손상된 오답은 백업할 수 없습니다."); return; }
        string? file = ChooseSave("StudyOne-wrong-answers.json");
        if (file is null) return;
        try {
            File.WriteAllBytes(file, StudyWrongAnswersData.Serialize(wrongBackup));
            HeaderStatus.Text = "오답 JSON 백업을 저장했습니다.";
        } catch(Exception error) { ShowError(error.Message); }
    }
    private void ImportWrong_Click(object sender, RoutedEventArgs e)
    {
        if (wrongLocked) { ShowError("손상된 오답 원본을 덮어쓸 수 없습니다."); return; }
        string? file = ChooseOpen();
        if (file is null) return;
        try {
            byte[] raw = StudyData.ReadLimited(file);
            WrongAnswersBackup incoming = StudyWrongAnswersData.Parse(raw);
            byte[]? current = File.Exists(StudyWrongAnswersData.PathName)
                ? StudyData.ReadLimited(StudyWrongAnswersData.PathName) : null;
            if (current is not null) StudyWrongAnswersData.Parse(current);
            if (MessageBox.Show(this,
                $"오답 {incoming.Items.Count}개로 기존 오답을 교체합니다. 이전 원본은 앱 내부 백업을 생성합니다.\n기존 일정·진도·계정 설정은 변경하지 않습니다.",
                "오답 데이터 교체",MessageBoxButton.YesNo,MessageBoxImage.Warning)
                != MessageBoxResult.Yes) return;
            StudyWrongAnswersData.ImportWithBackup(raw,current);
            wrongBackup = incoming; RefreshWrong(); ClearWrongForm();
            HeaderStatus.Text = "오답 JSON 가져오기 완료 · 이전 원본 백업 보존";
        } catch(Exception error) { ShowError(error.Message); }
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
            byte[]? original = File.Exists(StudyData.ProgressPath)
                ? StudyData.ReadLimited(StudyData.ProgressPath) : null;
            if (!ConfirmReplace("진도", imported.Items.Count)) return;
            string recovery = StudyData.SaveProgressWithRecovery(imported, original);
            progressBackup = imported;
            progressLocked = false;
            RefreshProgress();
            ClearProgressForm();
            HeaderStatus.Text = "진도 JSON 복원 완료 · 기존 원본: " + recovery;
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
        SyncLoadPlansButton.IsEnabled = false;
        SyncApprovePlanButton.IsEnabled = false;
        SyncRejectPlanButton.IsEnabled = false;
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
        SyncLoadPlansButton.IsEnabled = true;
        SyncApprovePlanButton.IsEnabled = true;
        SyncRejectPlanButton.IsEnabled = true;
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


    private void PendingPlansList_SelectionChanged(object sender,
                                                     SelectionChangedEventArgs e)
    {
        if (PendingPlansList.SelectedItem is not SyncClient.PendingPlan plan)
        {
            PendingPlanDetailsText.Text = "초안을 선택하면 세부 공부와 시험범위를 확인할 수 있습니다.";
            return;
        }
        PendingPlanDetailsText.Text =
            $"{plan.Title}\n승인 전: 실제 일정에 등록되지 않습니다.\n\n" +
            string.Join("\n", plan.Tasks.Select((t, i) =>
                $"{i + 1}. [{t.DueDate}] {t.Type} · {t.Subject} · {t.Title} (중요도 {t.Importance})"));
    }

    private async void SyncLoadPlans_Click(object sender, RoutedEventArgs e)
    {
        if (!BeginSync()) return;
        try
        {
            Uri endpoint = SyncClient.ParseEndpoint(SyncUrlInput.Text.Trim());
            var received = await SyncClient.GetPendingPlans(SyncTokenInput.Password, endpoint);
            PendingPlansList.ItemsSource = received.Proposals;
            PendingPlanDetailsText.Text = received.Proposals.Count == 0
                ? "현재 승인 대기 중인 공부 계획이 없습니다."
                : "계획을 선택해서 내용을 먼저 확인하세요. 승인하기 전에는 실제 일정이 바뀌지 않습니다.";
            SyncResultText.Text = $"서버의 승인 대기 공부 계획 {received.Proposals.Count}개를 확인했습니다.";
        }
        catch (Exception error)
        {
            SyncResultText.Text = "승인 대기 계획 조회 실패. 서버 주소·권한을 확인하세요.";
            ShowError(error.Message);
        }
        finally { EndSync(); }
    }

    private async void SyncApprovePlan_Click(object sender, RoutedEventArgs e) =>
        await DecideSelectedPlan(approve: true);

    private async void SyncRejectPlan_Click(object sender, RoutedEventArgs e) =>
        await DecideSelectedPlan(approve: false);

    private async Task DecideSelectedPlan(bool approve)
    {
        if (PendingPlansList.SelectedItem is not SyncClient.PendingPlan selected)
        {
            ShowError("먼저 승인 대기 목록에서 공부 계획을 선택해 주세요.");
            return;
        }
        string action = approve ? "승인" : "거부";
        if (!BeginSync()) return;
        try
        {
            Uri endpoint = SyncClient.ParseEndpoint(SyncUrlInput.Text.Trim());
            if (selected.State != "pending")
                throw new InvalidDataException("이미 처리된 공부 계획입니다. 목록을 다시 읽어 주세요.");
            if (MessageBox.Show(this,
                    $"선택한 AI 공부 계획: {selected.Title}\n" +
                    $"항목 {selected.TaskCount}개를 {action}하시겠습니까?\n\n" +
                    (approve
                        ? "승인하면 HTTPS 서버에 실제 일정이 생성됩니다. " +
                          "현재 PC에 자동으로 가져오지는 않으므로 이후 신규 일정 받기를 실행하세요."
                        : "거부하면 이 계획은 실제 일정으로 추가되지 않습니다."),
                    $"AI 공부 계획 {action} 최종 확인",
                    MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes)
            {
                SyncResultText.Text = "AI 계획 결정을 취소했습니다. 기존 일정은 그대로입니다.";
                return;
            }
            string finalState = await SyncClient.DecideProposal(
                SyncTokenInput.Password, endpoint, selected.Id, approve);
            var remaining = await SyncClient.GetPendingPlans(SyncTokenInput.Password, endpoint);
            PendingPlansList.ItemsSource = remaining.Proposals;
            PendingPlanDetailsText.Text = "결정 내용을 저장했습니다. 다른 계획을 선택해 확인하세요.";
            SyncResultText.Text = approve
                ? $"계획 '{selected.Title}' 승인: 서버 일정 {selected.TaskCount}개가 등록됐습니다.\n" +
                  "현재 PC에서 내용을 확인하려면 '서버의 신규 일정만 가져오기' 버튼을 사용하세요."
                : $"계획 '{selected.Title}'을(를) 거부했습니다. 실제 일정이 변경되지 않았습니다.";
        }
        catch (Exception error)
        {
            SyncResultText.Text = "계획 결정을 확인하지 못했습니다. 서버의 대기 목록을 새로고침하세요.";
            ShowError(error.Message);
        }
        finally { EndSync(); }
    }

}
