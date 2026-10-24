using System.IO;

namespace StudyOne.Desktop;

/// <summary>
/// Local-only suggestions; never overwrites an existing subject + unit record
/// regardless of its material, revision or completion status.
/// </summary>
public static class StudyOnlinePractice
{
    public const string Unit = "온라인 내신 문제";
    public const string Material = "온라인 문제풀이";
    public static readonly string[] Subjects = { "국어", "사회", "역사", "과학", "영어", "수학" };

    public static IReadOnlyList<string> MissingSubjects(IEnumerable<StudyProgressItem> existing)
    {
        if (existing is null) throw new InvalidDataException("진도 목록이 없습니다.");
        var known = new HashSet<string>(StringComparer.Ordinal);
        foreach (StudyProgressItem item in existing)
        {
            if (item is null) throw new InvalidDataException("손상된 진도 항목이 있습니다.");
            if (item.Unit.Trim() == Unit) known.Add(item.Subject.Trim());
        }
        return Subjects.Where(subject => !known.Contains(subject)).ToArray();
    }

    public static IReadOnlyList<StudyProgressItem> CreateMissing(IEnumerable<StudyProgressItem> existing)
    {
        return MissingSubjects(existing).Select(subject => new StudyProgressItem
        {
            Id = Guid.NewGuid().ToString("D"), Subject = subject, Material = Material,
            Unit = Unit, Status = "미완료", InExamScope = true,
            Revision = 1, UpdatedAt = StudyData.NowMillis()
        }).ToList();
    }
}
