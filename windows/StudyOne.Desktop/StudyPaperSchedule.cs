using System.Globalization;
using System.IO;
using System.Text;

namespace StudyOne.Desktop;

/// <summary>Offline, deterministic calculation only. Never reads or writes app stores.</summary>
public static class StudyPaperSchedule
{
    public sealed class Paper
    {
        public string Title { get; }
        public int Total { get; }
        public int Completed { get; }
        public Paper(string title, int total, int completed)
        {
            Title = Label(title);
            if (total is < 1 or > 60 || completed < 0 || completed > total)
                throw new InvalidDataException("총 회차는 1~60, 완료 회차는 0~전체 회차입니다.");
            Total = total; Completed = completed;
        }
    }

    public sealed class Lane
    {
        public string Name { get; }
        public IReadOnlyList<Paper> Papers { get; }
        public Lane(string name, IEnumerable<Paper> papers)
        {
            Name = Label(name);
            ArgumentNullException.ThrowIfNull(papers);
            var copy = papers.Take(21).ToArray();
            if (copy.Length is < 1 or > 20 || copy.Any(p => p is null) ||
                copy.Select(p => p.Title).Distinct(StringComparer.Ordinal).Count() != copy.Length)
                throw new InvalidDataException("단원별 시험지 종류는 중복 없이 1~20개입니다.");
            Papers = Array.AsReadOnly(copy);
        }
    }

    public sealed record Slot(string Lane, string Title);
    public sealed record Day(DateOnly Date, string Review, IReadOnlyList<Slot> Papers);
    public sealed class Preview
    {
        public IReadOnlyList<Day> Days { get; }
        public int RemainingPapers { get; }
        public int WorkDays { get; }
        public int DailyLimit { get; }
        internal Preview(List<Day> days, int remaining, int workDays, int dailyLimit)
        {
            Days = days.AsReadOnly(); RemainingPapers = remaining;
            WorkDays = workDays; DailyLimit = dailyLimit;
        }
        public string ClipboardText()
        {
            var text = new StringBuilder("StudyOne · 시험지·학습지 분량 미리보기 (저장하지 않음)\n")
                .Append($"남은 시험지 {RemainingPapers}회 · 하루 최대 {DailyLimit}회\n");
            if (Days.Count == 0) text.Append("배정할 남은 시험지가 없습니다.\n");
            foreach (var day in Days)
            {
                text.Append('\n').Append(Format(day.Date)).Append(" · ")
                    .Append(day.Review).Append(" 개념 복습\n");
                foreach (var paper in day.Papers)
                    text.Append("☐ [").Append(paper.Lane).Append("] ").Append(paper.Title).Append('\n');
            }
            return text.Append("\n읽기 전용 추천안입니다. 실제 숙제·진도에 반영되지 않았습니다.").ToString();
        }
    }

    public static string Format(DateOnly date) => date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    public static DateOnly ParseDate(string value)
    {
        if (!DateOnly.TryParseExact(value, "yyyy-MM-dd", CultureInfo.InvariantCulture,
            DateTimeStyles.None, out var result))
            throw new InvalidDataException("날짜는 실제 존재하는 YYYY-MM-DD 형식이어야 합니다.");
        return result;
    }

    public static string Label(string value, int limit = 80)
    {
        if (value is null || value.Length == 0 || value != value.Trim() ||
            value.EnumerateRunes().Count() > limit)
            throw new InvalidDataException($"이름은 앞뒤 공백 없이 1~{limit}자로 입력하세요.");
        foreach (var rune in value.EnumerateRunes())
        {
            var category = Rune.GetUnicodeCategory(rune);
            if (category is UnicodeCategory.Control or UnicodeCategory.Format or
                UnicodeCategory.Surrogate or UnicodeCategory.OtherNotAssigned or
                UnicodeCategory.LineSeparator or UnicodeCategory.ParagraphSeparator ||
                (category == UnicodeCategory.SpaceSeparator && rune.Value != 32))
                throw new InvalidDataException("이름에 숨은 제어 문자나 줄바꿈을 넣을 수 없습니다.");
        }
        // EnumerateRunes substitutes malformed UTF-16; reject unpaired surrogates explicitly.
        for (int i = 0; i < value.Length; i++)
            if (char.IsSurrogate(value[i]))
            {
                if (!char.IsHighSurrogate(value[i]) || i + 1 >= value.Length ||
                    !char.IsLowSurrogate(value[i + 1]))
                    throw new InvalidDataException("이름의 문자 인코딩이 올바르지 않습니다.");
                i++;
            }
        return value;
    }

    private sealed class Cursor
    {
        internal readonly Lane Lane;
        internal readonly int[] Next;
        internal int Index;
        internal Cursor(Lane lane) { Lane = lane; Next = lane.Papers.Select(p => p.Completed + 1).ToArray(); }
        internal bool HasRemaining => Lane.Papers.Where((p, i) => Next[i] <= p.Total).Any();
        internal Slot Take()
        {
            for (int offset = 0; offset < Next.Length; offset++)
            {
                int index = (Index + offset) % Next.Length;
                if (Next[index] > Lane.Papers[index].Total) continue;
                int number = Next[index]++;
                string suffix = number <= 20 ? char.ConvertFromUtf32(0x2460 + number - 1) : $"({number})";
                Index = (index + 1) % Next.Length;
                return new Slot(Lane.Name, Lane.Papers[index].Title + " " + suffix);
            }
            throw new InvalidDataException("배분 결과가 불완전합니다.");
        }
    }

    public static Preview Calculate(DateOnly start, DateOnly end, int dailyLimit,
        IEnumerable<DateOnly> skipDates, string firstReview, IEnumerable<Lane> lanes)
    {
        int span = end.DayNumber - start.DayNumber;
        if (span is < 0 or >= 120 || dailyLimit is < 1 or > 12)
            throw new InvalidDataException("기간은 1~120일, 하루 최대 분량은 1~12회입니다.");
        if (firstReview is not ("학습지" or "교재"))
            throw new InvalidDataException("복습 출처는 학습지 또는 교재입니다.");
        ArgumentNullException.ThrowIfNull(skipDates);
        ArgumentNullException.ThrowIfNull(lanes);
        var skipList = skipDates.Take(121).ToArray();
        var skips = skipList.ToHashSet();
        if (skipList.Length > 120 || skips.Count != skipList.Length ||
            skips.Any(d => d < start || d > end))
            throw new InvalidDataException("쉬는 날은 기간 안의 중복 없는 날짜여야 합니다.");
        var sources = lanes.Take(9).ToArray();
        if (sources.Length is < 1 or > 8 || sources.Any(l => l is null) ||
            sources.Select(l => l.Name).Distinct(StringComparer.Ordinal).Count() != sources.Length)
            throw new InvalidDataException("단원은 중복 없이 1~8개입니다.");
        var cursors = sources.Select(l => new Cursor(l)).ToArray();
        int total = sources.Sum(l => l.Papers.Sum(p => p.Total - p.Completed));
        if (total > 240) throw new InvalidDataException("남은 시험지는 최대 240회입니다.");
        var dates = Enumerable.Range(0, span + 1).Select(start.AddDays).Where(d => !skips.Contains(d)).ToArray();
        if (total > dates.Length * dailyLimit)
            throw new InvalidDataException("남은 날짜에 끝낼 수 없습니다. 날짜 또는 하루 분량을 조정하세요.");
        int remaining = total;
        var days = new List<Day>();
        for (int day = 0; day < dates.Length && remaining > 0; day++)
        {
            var selected = new List<Slot>();
            for (int slot = 0; slot < dailyLimit && remaining > 0; slot++)
            {
                int desired = (day * dailyLimit + slot) % cursors.Length;
                var cursor = Enumerable.Range(0, cursors.Length)
                    .Select(delta => cursors[(desired + delta) % cursors.Length]).First(c => c.HasRemaining);
                selected.Add(cursor.Take()); remaining--;
            }
            string review = day % 2 == 0 ? firstReview : firstReview == "학습지" ? "교재" : "학습지";
            days.Add(new Day(dates[day], review, selected.AsReadOnly()));
        }
        if (remaining != 0) throw new InvalidDataException("배분 결과가 불완전합니다.");
        return new Preview(days, total, dates.Length, dailyLimit);
    }
}
