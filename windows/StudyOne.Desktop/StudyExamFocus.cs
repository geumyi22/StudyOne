using System.IO;

namespace StudyOne.Desktop;

/// <summary>
/// Read-only exam-scope subject dashboard. No server, reminder or progress
/// mutation. Printed work is considered before online practice suggestions.
/// </summary>
public static class StudyExamFocus
{
    private static readonly string[] Subjects = { "국어", "사회", "역사", "과학", "영어", "수학" };
    public sealed record Subject(string Name, int Total, int Done, int Started,
        int Todo, int Unknown, int OnlineWaiting, string Next)
    {
        public int Percent => Total == 0 ? 0 :
            (int)Math.Round(100.0 * Done / Total, MidpointRounding.AwayFromZero);
        public string Summary => $"[{Name}] 완료 {Done}/{Total} ({Percent}%) · 진행 {Started}"
            + $" · 미완료 {Todo} · 확인 {Unknown}"
            + (OnlineWaiting > 0 ? $" · 온라인 대기 {OnlineWaiting}" : "");
        public string NextText => string.IsNullOrEmpty(Next)
            ? "시험범위 학습 완료" : "우선: " + Next;
    }

    private static int Rank(string status) => status switch
    {
        "진행 중" => 0, "미완료" => 1, "확인 필요" => 2, _ => 3
    };

    public static IReadOnlyList<Subject> Summarize(IEnumerable<StudyProgressItem> entries)
    {
        if (entries is null) throw new InvalidDataException("진도 자료가 없습니다.");
        var items = entries.ToList();
        if (items.Any(item => item is null ||
            string.IsNullOrWhiteSpace(item.Subject) ||
            !StudyData.Statuses.Contains(item.Status)))
            throw new InvalidDataException("진도 원본에 손상된 항목이 있습니다.");
        return items.Where(item => item.InExamScope)
            .GroupBy(item => item.Subject.Trim(), StringComparer.Ordinal)
            .OrderBy(group =>
            {
                int index = Array.IndexOf(Subjects, group.Key);
                return index < 0 ? Subjects.Length : index;
            })
            .ThenBy(group => group.Key, StringComparer.Ordinal)
            .Select(group =>
            {
                var list = group.ToList();
                var unfinished = list.Where(item => item.Status != "완료").ToList();
                var regular = unfinished.Where(item => item.Unit.Trim() != StudyOnlinePractice.Unit);
                var online = unfinished.Where(item => item.Unit.Trim() == StudyOnlinePractice.Unit);
                var candidates = regular.Any() ? regular : online;
                var next = candidates.OrderBy(item => Rank(item.Status))
                    .ThenBy(item => item.Material, StringComparer.Ordinal)
                    .ThenBy(item => item.Unit, StringComparer.Ordinal)
                    .FirstOrDefault();
                return new Subject(group.Key, list.Count,
                    list.Count(item => item.Status == "완료"),
                    list.Count(item => item.Status == "진행 중"),
                    list.Count(item => item.Status == "미완료"),
                    list.Count(item => item.Status == "확인 필요"),
                    online.Count(),
                    next is null ? "" :
                        $"{next.Material} · {next.Unit} ({next.Status})");
            }).ToList();
    }
}
