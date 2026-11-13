using System.Collections;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using StudyOne.Desktop;

namespace StudyOne.LearningUi.Tests;

internal static class Program
{
    private static int assertions;
    private static void Check(bool ok, string message)
    { assertions++; if (!ok) throw new InvalidOperationException(message); }
    private static IEnumerable<DependencyObject> Walk(DependencyObject root)
    {
        yield return root;
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
            foreach (var child in Walk(VisualTreeHelper.GetChild(root, i))) yield return child;
    }
    private static void Pump()
    { Dispatcher.CurrentDispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle); }
    private static T Named<T>(Window window, string name) where T : DependencyObject =>
        Walk(window).OfType<T>().Single(control => System.Windows.Automation.AutomationProperties.GetName(control) == name);
    private static Button Button(Window window, string text) =>
        Walk(window).OfType<Button>().Single(b => b.Content as string == text);
    private static void Click(Button button)
    {
        Check(button.IsEnabled, "Button enabled before click: " + button.Content);
        button.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Primitives.ButtonBase.ClickEvent)); Pump();
    }
    private static void Capture(Window window, string path)
    {
        window.UpdateLayout(); Pump();
        var bitmap = new RenderTargetBitmap((int)Math.Ceiling(window.ActualWidth),
            (int)Math.Ceiling(window.ActualHeight), 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(window);
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var output = File.Create(path); encoder.Save(output);
    }
    private static void Cell(DataGrid grid, int row, int column, string value)
    {
        grid.SelectedIndex = row;
        grid.CurrentCell = new DataGridCellInfo(grid.Items[row], grid.Columns[column]);
        grid.ScrollIntoView(grid.Items[row], grid.Columns[column]); grid.UpdateLayout();
        grid.Focus();
        (grid.Columns[column].GetCellContent(grid.Items[row])?.Parent as DataGridCell)?.Focus();
        Check(grid.BeginEdit(), "Grid begins edit"); Pump();
        var editor = grid.Columns[column].GetCellContent(grid.Items[row]) as TextBox;
        Check(editor is not null, "Actual WPF cell editor exists");
        editor!.Text = value;
        Check(grid.CommitEdit(DataGridEditingUnit.Cell, true), "Cell commit");
        Check(grid.CommitEdit(DataGridEditingUnit.Row, true), "Row commit"); Pump();
    }

    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Length != 1) throw new ArgumentException("Specify screenshot output directory");
        Directory.CreateDirectory(args[0]);
        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        int approvals = 0;
        var window = new StudyPaperPlannerWindow((subject, preview, owner) =>
        {
            Check(subject == "검증용 과목", "Subject reaches confirmation callback");
            Check(preview.RemainingPapers == 3 && owner is StudyPaperPlannerWindow,
                "Exact preview and modal owner reach callback");
            approvals++; return false; // Simulate user cancellation. No store access.
        });
        try
        {
            window.Show(); Pump();
            var subject = Named<TextBox>(window, "과목"); subject.Text = "검증용 과목";
            Named<DatePicker>(window,"시작").SelectedDate = new DateTime(2026,11,13);
            Named<DatePicker>(window,"마감").SelectedDate = new DateTime(2026,11,20);
            var grid = Walk(window).OfType<DataGrid>().Single();
            Cell(grid,0,0,"검증 단원"); Cell(grid,0,1,"합성 시험지"); Cell(grid,0,2,"3");
            Check(!Button(window,"검토 후 숙제 추가…").IsEnabled,"No addition before preview");
            Click(Button(window,"배분 미리보기"));
            var output = Walk(window).OfType<TextBox>().Single(t => t.IsReadOnly);
            Check(output.Text.Contains("남은 시험지 3회") && output.Text.Contains("합성 시험지 ③"),"Preview renders all rounds");
            Capture(window,Path.Combine(args[0],"paper-plan-preview.png"));
            Click(Button(window,"검토 후 숙제 추가…"));
            Check(approvals==1,"Explicit click invokes approval exactly once");
            subject.Text = "입력 변경 검증"; Pump();
            Check(!Button(window,"검토 후 숙제 추가…").IsEnabled && !Button(window,"미리보기 복사").IsEnabled,
                "Editing subject invalidates old approval and copy");
            Check(output.Text.Contains("입력이 바뀌었습니다"),"Stale message visible");
            subject.Text = "검증용 과목";
            Click(Button(window,"배분 미리보기"));
            Cell(grid,0,3,"1");
            Check(!Button(window,"검토 후 숙제 추가…").IsEnabled,"Actual grid edit invalidates preview");
            Click(Button(window,"배분 미리보기"));
            Check(output.Text.Contains("남은 시험지 2회") && !output.Text.Contains("합성 시험지 ①"),"Completed prefix excluded in rendered output");
            window.Width=740;window.Height=600;window.UpdateLayout();Pump();
            Capture(window,Path.Combine(args[0],"paper-plan-minimum-size.png"));
            Check(window.ActualWidth>=740 && window.ActualHeight>=600,"Minimum layout renders");
            Console.WriteLine($"LEARNING_UI_PASSED: {assertions} WPF interaction assertions; no data stores or network used");
            return 0;
        }
        catch (Exception error) { Console.Error.WriteLine(error);return 1; }
        finally { window.Close();app.Shutdown(); }
    }
}
