using System.Collections.ObjectModel;
using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;

namespace StudyOne.Desktop;

/// <summary>Generic user-entered paper template. No implicit progress inference or background writes.</summary>
public sealed class StudyPaperPlannerWindow : Window
{
    public sealed class Row
    {
        public string Unit { get; set; } = "";
        public string Title { get; set; } = "";
        public string Total { get; set; } = "1";
        public string Completed { get; set; } = "0";
    }
    private readonly ObservableCollection<Row> rows = new();
    private readonly DatePicker start = new() { SelectedDate = DateTime.Today, Width = 130 };
    private readonly DatePicker end = new() { SelectedDate = DateTime.Today.AddDays(13), Width = 130 };
    private readonly TextBox subject = new() { Width = 110, MaxLength = 60 };
    private readonly TextBox limit = new() { Width = 55, Text = "2", MaxLength = 2 };
    private readonly TextBox skips = new() { MinWidth = 250, MaxLength = 1500 };
    private readonly ComboBox review = new() { ItemsSource = new[] { "학습지", "교재" }, SelectedIndex = 0, Width = 90 };
    private readonly TextBox output = new() { IsReadOnly = true, AcceptsReturn = true,
        TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, MinHeight = 120 };
    private readonly Button copy = new() { Content = "미리보기 복사", IsEnabled = false };
    private readonly Button add = new() { Content = "검토 후 숙제 추가…", IsEnabled = false };
    private readonly DataGrid grid;
    private StudyPaperSchedule.Preview? preview;
    private readonly Func<string, StudyPaperSchedule.Preview, Window, bool> approve;
    private bool stale = true;

    public StudyPaperPlannerWindow(Func<string, StudyPaperSchedule.Preview, Window, bool> approve)
    {
        this.approve = approve;
        Title = "시험지 · 학습지 배분 — 미리보기";
        Width = 940; Height = 760; MinWidth = 740; MinHeight = 600;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        FontSize = 14;
        var panel = new Grid { Margin = new Thickness(18) };
        foreach (var height in new[] { GridLength.Auto, GridLength.Auto, new GridLength(1, GridUnitType.Star),
                                      GridLength.Auto, new GridLength(1, GridUnitType.Star), GridLength.Auto })
            panel.RowDefinitions.Add(new RowDefinition { Height = height });
        Content = panel;
        var description = new TextBlock { Text = "과목·단원별 시험지를 날짜에 나눕니다. 완료 횟수는 1회부터 연속 완료한 회차만 입력하세요.\n"
            + "미리보기만으로는 기록이 저장되지 않습니다. 합계 완료만으로 개별 진도를 추정하지 않습니다.",
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 12) };
        panel.Children.Add(description);
        var inputs = new WrapPanel { Margin = new Thickness(0, 0, 0, 10) };
        AddInput(inputs, "과목", subject); AddInput(inputs, "시작", start); AddInput(inputs, "마감", end);
        AddInput(inputs, "하루 최대", limit); AddInput(inputs, "첫 복습", review);
        AddInput(inputs, "쉬는 날 (YYYY-MM-DD, 쉼표로 구분)", skips);
        Grid.SetRow(inputs, 1); panel.Children.Add(inputs);
        grid = new DataGrid { ItemsSource = rows, AutoGenerateColumns = false,
            CanUserAddRows = false, CanUserDeleteRows = false, IsReadOnly = false,
            SelectionMode = DataGridSelectionMode.Single, Margin = new Thickness(0, 5, 0, 8) };
        foreach (var column in new[] { ("단원", "Unit", 150.0), ("시험지 종류", "Title", 260.0),
                                      ("전체 회차", "Total", 100.0), ("연속 완료 회차", "Completed", 140.0) })
            grid.Columns.Add(new DataGridTextColumn { Header = column.Item1, Width = column.Item3,
                Binding = new Binding(column.Item2) { UpdateSourceTrigger = UpdateSourceTrigger.PropertyChanged } });
        Grid.SetRow(grid, 2); panel.Children.Add(grid);
        var actions = new WrapPanel();
        var append = Button("종류 추가", (_, _) => { rows.Add(new Row()); Invalidate(); });
        var remove = Button("선택 종류 제거", (_, _) =>
        {
            if (grid.SelectedItem is Row row) { rows.Remove(row); Invalidate(); }
        });
        var calculate = Button("배분 미리보기", (_, _) => Calculate());
        actions.Children.Add(append); actions.Children.Add(remove); actions.Children.Add(calculate);
        Grid.SetRow(actions, 3); panel.Children.Add(actions);
        Grid.SetRow(output, 4); panel.Children.Add(output);
        var bottom = new WrapPanel { Margin = new Thickness(0, 10, 0, 0) };
        bottom.Children.Add(copy); bottom.Children.Add(add);
        bottom.Children.Add(Button("닫기", (_, _) => Close()));
        Grid.SetRow(bottom, 5); panel.Children.Add(bottom);
        foreach (var button in bottom.Children.OfType<Button>())
        { button.Padding = new Thickness(12, 7, 12, 7); button.Margin = new Thickness(0, 0, 8, 0); }
        copy.Click += (_, _) =>
        {
            if (stale || preview is null) return;
            try { Clipboard.SetText(preview.ClipboardText()); }
            catch (Exception error) { Error(error.Message); }
        };
        add.Click += (_, _) =>
        {
            if (stale || preview is null) return;
            if (this.approve(subject.Text, preview, this))
            { Invalidate(); output.Text = "숙제로 추가했습니다. 다시 추가하려면 새 미리보기를 검토하세요."; }
        };
        subject.TextChanged += (_, _) => Invalidate(); limit.TextChanged += (_, _) => Invalidate();
        skips.TextChanged += (_, _) => Invalidate(); review.SelectionChanged += (_, _) => Invalidate();
        start.SelectedDateChanged += (_, _) => Invalidate(); end.SelectedDateChanged += (_, _) => Invalidate();
        grid.BeginningEdit += (_, _) => Invalidate();
        rows.Add(new Row());
        output.Text = "과목과 시험지 종류를 입력하고 '배분 미리보기'를 누르세요. 자동 등록되지 않습니다.";
    }
    private static Button Button(string title, RoutedEventHandler handler)
    {
        var button = new Button { Content = title, Margin = new Thickness(0, 0, 8, 6), Padding = new Thickness(12, 7, 12, 7) };
        button.Click += handler; return button;
    }
    private static void AddInput(Panel parent, string label, FrameworkElement input)
    {
        var stack = new StackPanel { Margin = new Thickness(0, 0, 12, 8) };
        stack.Children.Add(new TextBlock { Text = label, FontSize = 12 }); stack.Children.Add(input);
        System.Windows.Automation.AutomationProperties.SetName(input, label);
        parent.Children.Add(stack);
    }
    private void Invalidate()
    {
        if (!stale) output.Text = "입력이 바뀌었습니다. 배분 미리보기를 다시 만드세요.";
        stale = true; preview = null; copy.IsEnabled = add.IsEnabled = false;
    }
    private void Error(string message) => MessageBox.Show(this, message, "StudyOne", MessageBoxButton.OK, MessageBoxImage.Warning);
    private static int Number(string value)
    {
        if (!int.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out int result))
            throw new InvalidDataException("회차와 하루 분량은 음수가 아닌 정수로 입력하세요.");
        return result;
    }
    private void Calculate()
    {
        Invalidate();
        try
        {
            if (!grid.CommitEdit(DataGridEditingUnit.Cell, true) || !grid.CommitEdit(DataGridEditingUnit.Row, true))
                throw new InvalidDataException("시험지 입력을 먼저 완료하세요.");
            StudyPaperSchedule.Label(subject.Text, 60);
            if (start.SelectedDate is null || end.SelectedDate is null)
                throw new InvalidDataException("시작일과 마감일을 선택하세요.");
            if (rows.Count is < 1 or > 160) throw new InvalidDataException("시험지 종류를 1~160개 입력하세요.");
            var lanes = rows.GroupBy(row => row.Unit, StringComparer.Ordinal)
                .Select(group => new StudyPaperSchedule.Lane(group.Key,
                    group.Select(row => new StudyPaperSchedule.Paper(row.Title, Number(row.Total), Number(row.Completed)))))
                .ToArray();
            string[] skipValues = string.IsNullOrWhiteSpace(skips.Text) ? Array.Empty<string>() : skips.Text.Split(',');
            var skipDates = skipValues.Select(value => StudyPaperSchedule.ParseDate(value.Trim())).ToArray();
            preview = StudyPaperSchedule.Calculate(DateOnly.FromDateTime(start.SelectedDate.Value),
                DateOnly.FromDateTime(end.SelectedDate.Value), Number(limit.Text), skipDates,
                (string)review.SelectedItem, lanes);
            output.Text = preview.ClipboardText(); stale = false; copy.IsEnabled = true;
            add.IsEnabled = preview.RemainingPapers > 0;
        }
        catch (Exception error) { output.Text = "미리보기를 만들지 못했습니다. 원본 데이터는 변경하지 않았습니다."; Error(error.Message); }
    }
}
