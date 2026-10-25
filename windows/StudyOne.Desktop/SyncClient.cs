using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace StudyOne.Desktop;

/// <summary>
/// Deliberately local-only, opt-in sync API client. Never persists or logs a token.
/// No background upload, auto-merge or destructive pull is implemented.
/// </summary>
internal static class SyncClient
{
    private static readonly Uri LocalAddress = new("http://127.0.0.1:8765/");

    /// <summary>HTTP is allowed only on loopback; every remote endpoint must be HTTPS.</summary>
    internal static Uri ParseEndpoint(string value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) ||
            !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) ||
            !string.IsNullOrEmpty(uri.Fragment) || uri.AbsolutePath != "/")
            throw new InvalidOperationException("서버 주소는 HTTPS 도메인 루트만 허용합니다.");
        if (uri.Scheme == Uri.UriSchemeHttp)
        {
            if (uri.Host != "127.0.0.1" && uri.Host != "localhost" &&
                uri.Host != "[::1]" && uri.Host != "::1")
                throw new InvalidOperationException("HTTP는 로컬 개발 서버에만 사용할 수 있습니다.");
            if (uri.Port != 8765)
                throw new InvalidOperationException("개발용 HTTP는 8765 포트만 허용합니다.");
        }
        else if (uri.Scheme != Uri.UriSchemeHttps)
        {
            throw new InvalidOperationException("서버에 연결하려면 유효한 HTTPS 주소가 필요합니다.");
        }
        return uri;
    }

    private static string ApiRoot(Uri endpoint) =>
        endpoint.Scheme == Uri.UriSchemeHttp ? "v1/" : "v2/";

    internal sealed class Snapshot
    {
        public int Schema { get; set; }
        public long Cursor { get; set; }
        public List<RemoteRecord> Records { get; set; } = new();
    }

    internal sealed class Session
    {
        public int Schema { get; set; }
        public string AccountId { get; set; } = "";
        public string DeviceId { get; set; } = "";
        public string Scope { get; set; } = "";
        public string Audience { get; set; } = "";
        public long ExpiresAt { get; set; }
        public long ServerTime { get; set; }
    }

    internal sealed class RemoteRecord
    {
        public string Kind { get; set; } = "";
        public string Id { get; set; } = "";
        public long Revision { get; set; }
        public bool Deleted { get; set; }
        public long UpdatedAt { get; set; }
        public JsonElement Payload { get; set; }
    }

    internal sealed class Mutation
    {
        public string Kind { get; set; } = "";
        public string Id { get; set; } = "";
        public long BaseRevision { get; set; }
        public bool Deleted { get; set; }
        public object? Payload { get; set; }
    }

    private static HttpClient OpenClient(string token, Uri? endpoint = null)
    {
        if (string.IsNullOrWhiteSpace(token) || token.Length < 32 ||
            token.Length > 1024 || token.Any(c => c <= 32 || c >= 127))
            throw new InvalidOperationException("동기화 토큰은 32자 이상이어야 합니다.");
        var handler = new HttpClientHandler
        {
            UseProxy = false,
            AllowAutoRedirect = false,
            UseCookies = false
        };
        var client = new HttpClient(handler)
        {
            BaseAddress = ParseEndpoint((endpoint ?? LocalAddress).AbsoluteUri),
            Timeout = TimeSpan.FromSeconds(12)
        };
        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", token);
        return client;
    }

    private static async Task<byte[]> ReadLimited(HttpResponseMessage response,
                                                   CancellationToken cancellationToken)
    {
        if (response.Content.Headers.ContentLength is long length && length > 4_000_000)
            throw new InvalidDataException("동기화 서버 응답이 너무 큽니다.");
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var memory = new MemoryStream();
        var buffer = new byte[8192];
        int count;
        while ((count = await stream.ReadAsync(buffer, cancellationToken)) != 0)
        {
            if (memory.Length + count > 4_000_000)
                throw new InvalidDataException("동기화 서버 응답이 너무 큽니다.");
            memory.Write(buffer, 0, count);
        }
        return memory.ToArray();
    }

    internal static async Task<Snapshot> GetSnapshot(string token,
                                                       CancellationToken cancel = default,
                                                       Uri? endpoint = null)
    {
        using var http = OpenClient(token, endpoint);
        using var response = await http.GetAsync(ApiRoot(http.BaseAddress!) + "snapshot", cancel);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException(
                response.StatusCode == HttpStatusCode.Unauthorized
                    ? "인증이 거절되었습니다. 토큰을 확인하세요."
                    : $"동기화 서버 읽기 실패: HTTP {(int)response.StatusCode}");
        var bytes = await ReadLimited(response, cancel);
        Snapshot value = JsonSerializer.Deserialize<Snapshot>(bytes, StudyData.JsonOptions)
                         ?? throw new InvalidDataException("동기화 스냅샷이 비어 있습니다.");
        if (value.Schema != 1 || value.Cursor < 0 || value.Records is null ||
            value.Records.Count > 7000 ||
            value.Records.Any(x => x is null || (x.Kind != "task" && x.Kind != "progress") ||
                                   string.IsNullOrWhiteSpace(x.Id) || x.Revision < 1))
            throw new InvalidDataException("지원하지 않는 동기화 스냅샷입니다.");
        return value;
    }

    /// <summary>Verify a device token belongs to the exact intended HTTPS account.</summary>
    internal static async Task<Session> GetSession(
        string token, Uri endpoint, CancellationToken cancel = default)
    {
        if (ParseEndpoint(endpoint.AbsoluteUri).Scheme != Uri.UriSchemeHttps)
            throw new InvalidOperationException("대기열은 HTTPS 계정 서버에서만 전송합니다.");
        using var http = OpenClient(token, endpoint);
        using var response = await http.GetAsync("v2/session", cancel);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException(
                $"동기화 계정 인증 실패: HTTP {(int)response.StatusCode}");
        byte[] raw = await ReadLimited(response, cancel);
        Session value = JsonSerializer.Deserialize<Session>(raw, StudyData.JsonOptions)
            ?? throw new InvalidDataException("인증 세션 응답이 비어 있습니다.");
        if (value.Schema != 1 || !Guid.TryParse(value.AccountId, out _) ||
            !Guid.TryParse(value.DeviceId, out _) || value.ExpiresAt <= value.ServerTime)
            throw new InvalidDataException("잘못된 계정 인증 정보입니다.");
        return value;
    }

    /// <summary>
    /// Replays EXACTLY one bounded, already-user-approved requestId and body.
    /// When the response is lost, the same requestId is retried, never minted
    /// again. The server guarantees that replay returns the stored result.
    /// </summary>
    /// <summary>
    /// Validate a full authoritative server receipt instead of trusting HTTP 200.
    /// A mismatch means the result is UNKNOWN; the persisted outbox requestId
    /// remains available for safe replay, never replaced with a new UUID.
    /// </summary>
    internal static void VerifyChangeAck(byte[] raw, IReadOnlyList<Mutation> expected)
    {
        if (expected is null || expected.Count is < 1 or > 50)
            throw new InvalidDataException("검증할 서버 변경 요청이 올바르지 않습니다.");
        using JsonDocument document = JsonDocument.Parse(raw);
        JsonElement root = document.RootElement;
        if (!MatchingKeys(root, "schema", "cursor", "changes") ||
            root.GetProperty("schema").GetInt32() != 1 ||
            root.GetProperty("changes").ValueKind != JsonValueKind.Array)
            throw new InvalidDataException("서버 변경 승인 형식이 올바르지 않습니다.");
        var changes = root.GetProperty("changes").EnumerateArray().ToArray();
        if (changes.Length != expected.Count)
            throw new InvalidDataException("서버가 요청의 일부만 승인했습니다.");
        long previous = 0;
        for (int i = 0; i < changes.Length; i++)
        {
            JsonElement change = changes[i];
            var item = expected[i];
            if (!MatchingKeys(change, "seq", "kind", "id", "revision", "deleted", "updatedAt") ||
                change.GetProperty("kind").GetString() != item.Kind ||
                change.GetProperty("id").GetString() != item.Id ||
                change.GetProperty("revision").GetInt64() != item.BaseRevision + 1 ||
                change.GetProperty("deleted").GetBoolean() != item.Deleted ||
                change.GetProperty("updatedAt").GetInt64() < 0)
                throw new InvalidDataException("서버 기록 ID·수정 버전이 요청과 일치하지 않습니다.");
            long seq = change.GetProperty("seq").GetInt64();
            if (seq <= previous)
                throw new InvalidDataException("서버 승인 순서가 올바르지 않습니다.");
            previous = seq;
        }
        if (root.GetProperty("cursor").GetInt64() != previous)
            throw new InvalidDataException("서버 커서가 최종 승인 기록과 일치하지 않습니다.");
    }

    internal static async Task<int> UploadQueuedBatch(
        string token, Uri endpoint, StudySyncOutbox.Batch batch,
        CancellationToken cancel = default)
    {
        if (ParseEndpoint(endpoint.AbsoluteUri).Scheme != Uri.UriSchemeHttps)
            throw new InvalidOperationException("대기열은 HTTPS 계정 서버만 허용합니다.");
        if (batch.Mutations.Count is < 1 or > 50 ||
            !Guid.TryParse(batch.RequestId, out _))
            throw new InvalidDataException("대기열 요청이 올바르지 않습니다.");
        using var http = OpenClient(token, endpoint);
        var body = new { requestId = batch.RequestId, mutations = batch.Mutations };
        using var message = new HttpRequestMessage(HttpMethod.Post, "v2/changes")
        {
            Content = new ByteArrayContent(StudyData.Serialize(body))
        };
        message.Content.Headers.ContentType =
            new MediaTypeHeaderValue("application/json");
        using var response = await http.SendAsync(message, cancel);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException(response.StatusCode switch {
                HttpStatusCode.Conflict => "서버에 동일 ID 변경 또는 충돌이 있습니다. 대기열을 보존했습니다.",
                HttpStatusCode.Unauthorized => "로그인이 만료됐습니다. 대기열을 보존했습니다.",
                HttpStatusCode.Forbidden => "쓰기 권한이 없습니다. 대기열을 보존했습니다.",
                _ => $"서버 HTTP {(int)response.StatusCode} 오류. 대기열을 보존했습니다."
            });
        byte[] raw = await ReadLimited(response, cancel);
        VerifyChangeAck(raw, batch.Mutations);
        return batch.Mutations.Count;
    }

    internal static List<Mutation> NewOnly(TaskBackup tasks, ProgressBackup progress,
                                          Snapshot snapshot)
    {
        // Validate both source files BEFORE preparing anything for upload.
        StudyData.SerializeAndValidateTasks(tasks);
        StudyData.SerializeAndValidateProgress(progress);
        var remoteKeys = snapshot.Records
            .Select(x => (x.Kind, x.Id))
            .ToHashSet();
        List<Mutation> candidates = new();
        foreach (var task in tasks.Tasks)
        {
            string id = task.Id.ToString(System.Globalization.CultureInfo.InvariantCulture);
            if (!remoteKeys.Contains(("task", id)))
                candidates.Add(new Mutation {
                    Kind = "task", Id = id, BaseRevision = 0,
                    Deleted = false, Payload = task
                });
        }
        foreach (var item in progress.Items)
        {
            if (!remoteKeys.Contains(("progress", item.Id)))
                candidates.Add(new Mutation {
                    Kind = "progress", Id = item.Id, BaseRevision = 0,
                    Deleted = false, Payload = item
                });
        }
        return candidates;
    }

    /// <summary>
    /// Upload *new items only*. No remote existing records are overwritten.
    /// Any conflict halts the batch; safe retry re-reads current server IDs.
    /// </summary>
    internal static async Task<int> UploadNew(string token, List<Mutation> pending,
                                               CancellationToken cancel = default,
                                               Uri? endpoint = null)
    {
        int sent = 0;
        using var http = OpenClient(token, endpoint);
        foreach (var batch in pending.Chunk(50))
        {
            var body = new
            {
                requestId = Guid.NewGuid().ToString("D"),
                mutations = batch
            };
            using var request = new HttpRequestMessage(HttpMethod.Post,
                ApiRoot(http.BaseAddress!) + "changes")
            {
                Content = new ByteArrayContent(StudyData.Serialize(body))
            };
            request.Content.Headers.ContentType =
                new MediaTypeHeaderValue("application/json");
            using var response = await http.SendAsync(request, cancel);
            if (!response.IsSuccessStatusCode)
            {
                throw new InvalidOperationException(response.StatusCode switch
                {
                    HttpStatusCode.Conflict => $"다른 기기의 변경과 충돌했습니다. {sent}개 전송 후 중단했습니다. 다시 조회하세요.",
                    HttpStatusCode.Unauthorized => "인증 실패: 토큰을 확인하세요.",
                    _ => $"동기화 업로드 중 HTTP {(int)response.StatusCode} 오류가 발생했습니다. {sent}개 전송 후 중단했습니다."
                });
            }
            byte[] receipt = await ReadLimited(response, cancel);
            VerifyChangeAck(receipt, batch);
            sent += batch.Length;
        }
        return sent;
    }

    internal sealed class IncomingPlan
    {
        public List<StudyTask> NewTasks { get; } = new();
        public List<StudyProgressItem> NewProgress { get; } = new();
        public int Conflicts { get; set; }
        public int ExistingIdentical { get; set; }
        public long ServerCursor { get; set; }
    }

    private static bool MatchingKeys(JsonElement element, params string[] expected)
    {
        if (element.ValueKind != JsonValueKind.Object) return false;
        return element.EnumerateObject().Select(p => p.Name)
            .ToHashSet(StringComparer.Ordinal)
            .SetEquals(expected);
    }

    private static bool TaskContentEquals(StudyTask a, StudyTask b) =>
        a.Id == b.Id && a.Type == b.Type && a.Subject == b.Subject &&
        a.Title == b.Title && a.DueDate == b.DueDate &&
        a.Importance == b.Importance && a.Completed == b.Completed;

    private static bool ProgressContentEquals(StudyProgressItem a, StudyProgressItem b) =>
        a.Id.Equals(b.Id, StringComparison.OrdinalIgnoreCase) &&
        a.Subject == b.Subject && a.Material == b.Material && a.Unit == b.Unit &&
        a.Status == b.Status && a.InExamScope == b.InExamScope;

    /// <summary>
    /// Preview only remote IDs missing on this PC. No server or disk writes.
    /// Existing ID with different content -> conflict, never an overwrite.
    /// Server-side deletions remain unapplied pending explicit tombstone UX.
    /// </summary>
    internal static IncomingPlan PreviewNewRemote(
        TaskBackup tasks, ProgressBackup progress, Snapshot remote)
    {
        StudyData.SerializeAndValidateTasks(tasks);
        StudyData.SerializeAndValidateProgress(progress);
        if (remote.Schema != 1 || remote.Cursor < 0 ||
            remote.Records is null || remote.Records.Count > 7000)
            throw new InvalidDataException("지원되지 않는 동기화 스냅샷입니다.");

        var localTasks = tasks.Tasks.ToDictionary(x => x.Id);
        var localProgress = progress.Items.ToDictionary(x => x.Id,
                                                          StringComparer.OrdinalIgnoreCase);
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var plan = new IncomingPlan { ServerCursor = remote.Cursor };
        foreach (RemoteRecord row in remote.Records)
        {
            if (row is null || row.Deleted || row.Revision < 1 ||
                (row.Kind != "task" && row.Kind != "progress") ||
                !seen.Add(row.Kind + "#" + row.Id))
                throw new InvalidDataException("중복되거나 손상된 서버 진도 기록입니다.");

            if (row.Kind == "task")
            {
                if (!MatchingKeys(row.Payload, "id", "type", "subject",
                                   "title", "dueDate", "importance", "completed"))
                    throw new InvalidDataException("서버 일정 필드가 잘못되었습니다.");
                StudyTask item = row.Payload.Deserialize<StudyTask>(StudyData.JsonOptions)
                                 ?? throw new InvalidDataException("서버 일정이 비어 있습니다.");
                if (item.Id.ToString(System.Globalization.CultureInfo.InvariantCulture) != row.Id)
                    throw new InvalidDataException("서버 일정 ID가 서로 일치하지 않습니다.");
                if (localTasks.TryGetValue(item.Id, out var old))
                {
                    if (TaskContentEquals(old, item)) plan.ExistingIdentical++;
                    else plan.Conflicts++;
                }
                else plan.NewTasks.Add(item);
            }
            else
            {
                if (!MatchingKeys(row.Payload, "id", "subject", "material", "unit",
                                   "status", "inExamScope", "revision", "updatedAt"))
                    throw new InvalidDataException("서버 진도 필드가 잘못되었습니다.");
                StudyProgressItem item = row.Payload.Deserialize<StudyProgressItem>(StudyData.JsonOptions)
                                         ?? throw new InvalidDataException("서버 진도가 비어 있습니다.");
                if (!item.Id.Equals(row.Id, StringComparison.OrdinalIgnoreCase) ||
                    item.Revision != row.Revision)
                    throw new InvalidDataException("서버 진도 ID·수정 기록이 일치하지 않습니다.");
                if (localProgress.TryGetValue(item.Id, out var old))
                {
                    if (ProgressContentEquals(old, item)) plan.ExistingIdentical++;
                    else plan.Conflicts++;
                }
                else plan.NewProgress.Add(item);
            }
        }
        // Validate resulting files before displaying a merge confirmation.
        MakeMergedTasks(tasks, plan);
        MakeMergedProgress(progress, plan);
        return plan;
    }

    internal static TaskBackup MakeMergedTasks(TaskBackup current, IncomingPlan plan)
    {
        var candidate = new TaskBackup
        {
            Format = current.Format, Schema = current.Schema,
            ExportedAt = DateTime.Today.ToString("yyyy-MM-dd",
                System.Globalization.CultureInfo.InvariantCulture),
            Grade = current.Grade, ClassName = current.ClassName,
            School = current.School,
            Tasks = current.Tasks.Concat(plan.NewTasks).ToList()
        };
        StudyData.SerializeAndValidateTasks(candidate);
        return candidate;
    }

    internal static ProgressBackup MakeMergedProgress(ProgressBackup current, IncomingPlan plan)
    {
        var candidate = new ProgressBackup
        {
            Format = current.Format, Schema = current.Schema,
            Items = current.Items.Concat(plan.NewProgress).ToList()
        };
        StudyData.SerializeAndValidateProgress(candidate);
        return candidate;
    }


    internal sealed class PendingPlans
    {
        public int Schema { get; set; }
        public List<PendingPlan> Proposals { get; set; } = new();
    }

    internal sealed class PendingPlan
    {
        public string Id { get; set; } = "";
        public string Title { get; set; } = "";
        public string State { get; set; } = "";
        public long CreatedAt { get; set; }
        public long ExpiresAt { get; set; }
        public int TaskCount { get; set; }
        public List<StudyTask> Tasks { get; set; } = new();
        public long? Cursor { get; set; }

        public string Display => $"{Title} · {TaskCount}개 항목 · 승인 대기";
    }

    private static void RequirePrivateEndpoint(Uri endpoint)
    {
        if (ParseEndpoint(endpoint.AbsoluteUri).Scheme != Uri.UriSchemeHttps)
            throw new InvalidOperationException(
                "AI 학습 계획 승인은 계정별 HTTPS 서버에서만 지원합니다.");
    }

    internal static void ValidateProposals(PendingPlans proposals)
    {
        if (proposals.Schema != 1 || proposals.Proposals is null ||
            proposals.Proposals.Count > 20)
            throw new InvalidDataException("지원되지 않는 학습 계획 응답입니다.");
        var ids = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var plan in proposals.Proposals)
        {
            if (plan is null || !Guid.TryParse(plan.Id, out _) ||
                !ids.Add(plan.Id) || string.IsNullOrWhiteSpace(plan.Title) ||
                plan.Title.Length > 100 || plan.State != "pending" ||
                plan.TaskCount < 1 || plan.TaskCount > 30 ||
                plan.Tasks is null || plan.TaskCount != plan.Tasks.Count ||
                plan.ExpiresAt <= plan.CreatedAt)
                throw new InvalidDataException("학습 계획 초안이 손상되었습니다.");
            var mockBackup = new TaskBackup {
                Tasks = plan.Tasks
            };
            StudyData.SerializeAndValidateTasks(mockBackup);
        }
    }

    internal static async Task<PendingPlans> GetPendingPlans(
        string token, Uri endpoint, CancellationToken cancel = default)
    {
        RequirePrivateEndpoint(endpoint);
        using var http = OpenClient(token, endpoint);
        using var response = await http.GetAsync("v2/proposals", cancel);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException($"초안 목록 조회 실패: HTTP {(int)response.StatusCode}");
        var raw = await ReadLimited(response, cancel);
        var plans = JsonSerializer.Deserialize<PendingPlans>(raw, StudyData.JsonOptions)
                    ?? throw new InvalidDataException("학습 계획 목록이 비어 있습니다.");
        ValidateProposals(plans);
        return plans;
    }

    internal static async Task<string> DecideProposal(
        string token, Uri endpoint, string proposalId,
        bool approve, CancellationToken cancel = default)
    {
        RequirePrivateEndpoint(endpoint);
        if (!Guid.TryParse(proposalId, out _))
            throw new InvalidDataException("학습 계획 ID가 올바르지 않습니다.");
        string action = approve ? "approve" : "reject";
        using var http = OpenClient(token, endpoint);
        using var message = new HttpRequestMessage(HttpMethod.Post,
            "v2/proposals/" + Uri.EscapeDataString(proposalId) + "/" + action)
        {
            Content = new ByteArrayContent(Encoding.UTF8.GetBytes("{}"))
        };
        message.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        using var response = await http.SendAsync(message, cancel);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException(
                response.StatusCode == HttpStatusCode.Forbidden
                    ? "읽기 전용 토큰은 계획을 승인할 수 없습니다."
                    : $"계획 {(approve ? "승인" : "거부")} 실패: HTTP {(int)response.StatusCode}");
        var bytes = await ReadLimited(response, cancel);
        using var doc = JsonDocument.Parse(bytes);
        string state = doc.RootElement.GetProperty("state").GetString() ?? "";
        if (state != (approve ? "approved" : "rejected") ||
            doc.RootElement.GetProperty("id").GetString() != proposalId)
            throw new InvalidDataException("초안 결정 결과를 확인하지 못했습니다.");
        return state;
    }

}
