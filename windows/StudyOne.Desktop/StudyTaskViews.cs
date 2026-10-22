using System.Globalization;

namespace StudyOne.Desktop;

/// <summary>
/// Read-only selectors for the same stored tasks across Today/Tomorrow/All.
/// A task in two views has one persistent ID and one completion value.
/// </summary>
public static class StudyTaskViews
{
    public static bool IsHomework(StudyTask task) =>
        task.Type is "숙제 항목" or "과제"
        || task.Title.Contains("숙제", StringComparison.Ordinal);

    public static IReadOnlyList<StudyTask> Select(
        IEnumerable<StudyTask> source, DateOnly today, int displayMode)
    {
        ArgumentNullException.ThrowIfNull(source);
        if (displayMode < 0 || displayMode > 2)
            throw new ArgumentOutOfRangeException(nameof(displayMode));
        string date = today.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        string tomorrow = today.AddDays(1).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        return source.Where(task => task is not null && (
                displayMode == 2
                || (displayMode == 1 && task.DueDate == tomorrow)
                || (displayMode == 0 && (
                       task.DueDate == date
                    || (string.CompareOrdinal(task.DueDate, date) < 0 && !task.Completed)
                    || (task.DueDate == tomorrow && IsHomework(task))))))
            .OrderBy(task => task.DueDate)
            .ThenBy(task => task.Completed)
            .ThenByDescending(task => task.Importance)
            .ThenBy(task => task.Id)
            .ToArray();
    }
}
