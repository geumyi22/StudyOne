using System.Collections.Specialized;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Threading;

namespace StudyOne.Desktop;

public partial class MainWindow
{
    /// <summary>Adds compact tools to the existing header without changing stored data or the editor.</summary>
    internal void EnableLearningTools()
    {
        if (TaskDateFilterCombo.Parent is not WrapPanel dateRow || dateRow.Parent is not StackPanel header)
            throw new InvalidOperationException("일정 도구를 연결할 기존 화면을 찾지 못했습니다.");
        var row = new WrapPanel { Margin = new Thickness(0, 3, 0, 4) };
        var search = new TextBox { Width = 220, MaxLength = 256,
            ToolTip = "과목·제목·분류·날짜 검색 (공백으로 여러 검색어)" };
        System.Windows.Automation.AutomationProperties.SetName(search, "학습 일정 검색");
        var status = new ComboBox { Width = 105, ItemsSource = new[] { "전체 상태", "미완료", "완료" }, SelectedIndex = 0 };
        System.Windows.Automation.AutomationProperties.SetName(status, "완료 상태 필터");
        var category = new ComboBox { Width = 160,
            ItemsSource = new[] { "전체 분류" }.Concat(StudyData.Categories)
                .Concat(taskBackup.Tasks.Select(t => t.Type)).Distinct().ToArray(), SelectedIndex = 0 };
        System.Windows.Automation.AutomationProperties.SetName(category, "학습 분류 필터");
        var reset = new Button { Content = "검색 초기화", ToolTip = "검색·상태·분류만 초기화합니다. 날짜나 기록은 바뀌지 않습니다." };
        var paper = new Button { Content = "시험지 · 학습지 배분", ToolTip = "과목별 분량 미리보기 → 별도 승인 후 숙제 추가" };
        var hint = new TextBlock { Margin = new Thickness(0, 0, 0, 4), TextWrapping = TextWrapping.Wrap,
            FontSize = 12 };
        row.Children.Add(search); row.Children.Add(status); row.Children.Add(category);
        row.Children.Add(reset); row.Children.Add(paper); header.Children.Add(row); header.Children.Add(hint);
        var view = CollectionViewSource.GetDefaultView(tasks);
        var filter = new StudyTaskFilters();
        view.Filter = value => value is StudyTask task && filter.Matches(task);
        bool pending = false;
        void QueueSummary()
        {
            if (pending) return;
            pending = true;
            Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() =>
            {
                pending = false;
                int count = view.Cast<StudyTask>().Count();
                hint.Text = count == 0 ? "현재 날짜·검색 조건에 맞는 일정이 없습니다. 원본 기록은 그대로 보존됩니다."
                    : $"검색 결과 {count}개 · 날짜 범위 내 원본 {tasks.Count}개 · 오늘 할꺼 복사는 검색과 무관하게 전체 대상 사용";
            }));
        }
        void Apply()
        {
            filter = new StudyTaskFilters(search.Text, Math.Max(0, status.SelectedIndex),
                category.SelectedIndex <= 0 ? null : category.SelectedItem as string);
            view.Refresh(); QueueSummary();
        }
        search.TextChanged += (_, _) => Apply(); status.SelectionChanged += (_, _) => Apply();
        category.SelectionChanged += (_, _) => Apply();
        reset.Click += (_, _) => { search.Clear(); status.SelectedIndex = 0; category.SelectedIndex = 0; };
        paper.Click += (_, _) => new StudyPaperPlannerWindow(ApprovePaperTasks) { Owner = this }.ShowDialog();
        ((INotifyCollectionChanged)view).CollectionChanged += (_, _) => QueueSummary();
        QueueSummary();
    }

    private bool ApprovePaperTasks(string subject, StudyPaperSchedule.Preview preview, Window owner)
    {
        if (tasksLocked || syncBusy)
        {
            MessageBox.Show(owner, "일정 저장소가 잠겼거나 동기화 중입니다. 미리보기는 가능하지만 추가는 할 수 없습니다.", "StudyOne");
            return false;
        }
        try
        {
            byte[]? original = File.Exists(StudyData.TasksPath) ? StudyData.ReadLimited(StudyData.TasksPath) : null;
            var disk = original is null ? new TaskBackup() : StudyData.ParseTasks(original);
            // The visible model and disk must agree before the explicit confirmation.
            if (!StudyData.SerializeAndValidateTasks(disk).SequenceEqual(StudyData.SerializeAndValidateTasks(taskBackup)))
                throw new InvalidDataException("화면을 연 뒤 일정이 변경됐습니다. 원본 백업을 확인한 뒤 앱을 다시 여세요.");
            var batch = StudyPaperTaskBatch.Prepare(original, subject, preview);
            if (MessageBox.Show(owner, $"'{subject}' 시험지 숙제 {batch.Count}개를 추가할까요?\n\n"
                + "미리보기의 날짜·단원·회차만 새 숙제로 추가합니다.\n"
                + "기존 숙제·완료 상태·진도는 수정하지 않습니다.\n"
                + "복습 출처 안내는 별도 숙제로 추가하지 않습니다.\n"
                + "교체 전 일정 원본은 Recovery 폴더에 보존합니다.",
                "시험지 숙제 추가 · 별도 승인", MessageBoxButton.YesNo,
                MessageBoxImage.Question, MessageBoxResult.No) != MessageBoxResult.Yes) return false;
            string backup = batch.Commit(StudyData.TasksPath, userApproved: true);
            taskBackup = StudyData.LoadTasks(); RefreshTasks();
            MessageBox.Show(owner, $"숙제 {batch.Count}개를 추가했습니다. 자동 기기 동기화는 수행하지 않았습니다.\n원본 보관: {backup}",
                "StudyOne", MessageBoxButton.OK, MessageBoxImage.Information);
            return true;
        }
        catch (Exception error) { MessageBox.Show(owner, error.Message, "StudyOne", MessageBoxButton.OK, MessageBoxImage.Warning); return false; }
    }
}
