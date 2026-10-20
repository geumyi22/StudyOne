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
            sent += batch.Length;
        }
        return sent;
    }
}
