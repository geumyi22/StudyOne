namespace StudyOne.Desktop;

/// <summary>Transient view options only; no persisted dates, categories or completion values change.</summary>
public sealed class StudyTaskFilters
{
    private readonly string[] terms;
    private readonly int status;
    private readonly string? category;
    public StudyTaskFilters(string query = "", int status = 0, string? category = null)
    {
        ArgumentNullException.ThrowIfNull(query);
        if (query.Length > 256 || status is < 0 or > 2)
            throw new ArgumentOutOfRangeException(nameof(query), "검색은 256자 이하, 상태는 전체·미완료·완료입니다.");
        terms = query.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        this.status = status; this.category = category;
    }
    public bool Matches(StudyTask task)
    {
        if (task is null || (status == 1 && task.Completed) || (status == 2 && !task.Completed) ||
            (category is not null && task.Type != category)) return false;
        return terms.All(term => task.Subject.Contains(term, StringComparison.OrdinalIgnoreCase) ||
            task.Title.Contains(term, StringComparison.OrdinalIgnoreCase) ||
            task.Type.Contains(term, StringComparison.OrdinalIgnoreCase) ||
            task.DueDate.Contains(term, StringComparison.Ordinal));
    }
}
