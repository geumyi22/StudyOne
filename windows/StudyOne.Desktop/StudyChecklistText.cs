using System.Globalization;
using System.Text;

namespace StudyOne.Desktop;

/// <summary>Read-only clipboard formatter for the Windows preview; never changes saved records.</summary>
public static class StudyChecklistText
{
    private static readonly string[] Roles = StudyData.Categories.Take(5).ToArray();

    public static string Daily(IEnumerable<StudyTask> items, DateOnly today,
                               IReadOnlyDictionary<long, StudyTaskDetail>? details = null)
    {
        string date = today.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        var selected = items.Where(t => string.CompareOrdinal(t.DueDate, date) == 0 ||
                            (!t.Completed && string.CompareOrdinal(t.DueDate, date) < 0))
            .OrderBy(t => t.DueDate, StringComparer.Ordinal)
            .ThenByDescending(t => t.Importance).ThenBy(t => t.Id).ToList();
        var output = new StringBuilder("StudyOne 할꺼 · ").Append(date);
        for (int i = 0; i < Roles.Length; i++)
        {
            output.Append("\n\n").Append(i + 1).Append(". ").Append(Roles[i]).Append('\n');
            int count = 0;
            foreach (var task in selected)
            {
                bool carried = !task.Completed && string.CompareOrdinal(task.DueDate, date) < 0;
                bool selectedRole = i == 0
                    ? carried || task.Type == Roles[i] : !carried && task.Type == Roles[i];
                if (!selectedRole) continue;
                AppendTask(output, task, carried, details);
                count++;
            }
            if (count == 0) output.Append("(없음)\n");
        }
        var legacy = selected.Where(t => string.CompareOrdinal(t.DueDate, date) >= 0 &&
                                  !Roles.Contains(t.Type, StringComparer.Ordinal)).ToList();
        if (legacy.Count > 0)
        {
            output.Append("\n기존 분류 일정\n");
            foreach (var task in legacy) AppendTask(output, task, carried: false, details);
        }
        return output.ToString().Trim();
    }

    public static string Progress(IEnumerable<StudyProgressItem> items)
    {
        var output = new StringBuilder("StudyOne 교재·시험범위 진도 체크리스트\n");
        var ordered = items.OrderBy(p => p.Subject, StringComparer.Ordinal)
            .ThenBy(p => p.Material, StringComparer.Ordinal)
            .ThenBy(p => p.Unit, StringComparer.Ordinal).ToList();
        if (ordered.Count == 0) return output.Append("등록된 진도가 없습니다.").ToString();
        foreach (var item in ordered)
            output.Append(item.Status == "완료" ? "☑ " : "☐ ")
                .Append('[').Append(item.Subject).Append("] ")
                .Append(item.Material).Append(" · ").Append(item.Unit)
                .Append(" — ").Append(item.Status)
                .Append(item.InExamScope ? " (시험범위 포함)" : " (시험범위 제외)")
                .Append('\n');
        return output.ToString().Trim();
    }

    private static void AppendTask(StringBuilder output, StudyTask task, bool carried,
                                   IReadOnlyDictionary<long, StudyTaskDetail>? details)
    {
        string subject = string.IsNullOrWhiteSpace(task.Subject) ? "기타" : task.Subject.Trim();
        output.Append(task.Completed ? "☑ " : "☐ ")
            .Append('[').Append(subject).Append("] ").Append(task.Title.Trim());
        if (carried) output.Append(" (전날 못 다 · 원래 마감 ").Append(task.DueDate).Append(')');
        output.Append('\n');
        if (details != null && details.TryGetValue(task.Id, out var extra))
        {
            if (extra.TaskId != task.Id) throw new InvalidDataException("상세 정보 ID 불일치");
            foreach (string line in extra.DisplayText().Split('\n'))
                if (line.Length != 0) output.Append("    ").Append(line).Append('\n');
        }
    }
}
