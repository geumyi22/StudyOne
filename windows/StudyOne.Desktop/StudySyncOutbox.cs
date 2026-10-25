using System.Globalization;
using System.IO;
using System.Text;
using System.Text.Json;

namespace StudyOne.Desktop;

/// <summary>
/// Opt-in, create-only local outbox for the account-scoped HTTPS v2 protocol.
/// Offline records are saved without a credential and cannot be uploaded until
/// the user re-enters a WRITE-scoped device token for the exact bound account.
/// No background worker, destructive server changes or automatic retries.
/// </summary>
internal static class StudySyncOutbox
{
    internal const string Format = "studyone-sync-outbox";
    internal const int MaxBatches = 100;
    internal const int MaxItems = 1000;
    internal const int MaxBytes = 2_000_000;

    internal sealed class Batch
    {
        public string RequestId { get; set; } = "";
        public string AccountId { get; set; } = "";
        public string Endpoint { get; set; } = "";
        public List<SyncClient.Mutation> Mutations { get; set; } = new();
    }

    internal sealed class Document
    {
        public string Format { get; set; } = StudySyncOutbox.Format;
        public int Schema { get; set; } = 1;
        public List<Batch> Batches { get; set; } = new();
    }

    internal static string FilePath(string dataDirectory) =>
        Path.Combine(dataDirectory, "SyncOutbox", "outbox.json");

    private static string Key(SyncClient.Mutation change) =>
        change.Kind + "#" + change.Id.ToLowerInvariant();

    private static void Validate(Document document)
    {
        if (document.Format != Format || document.Schema != 1 ||
            document.Batches is null || document.Batches.Count > MaxBatches)
            throw new InvalidDataException("지원하지 않는 대기열 데이터입니다.");
        var requestIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var seenPerAccount = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        int total = 0;
        foreach (Batch batch in document.Batches)
        {
            if (!Guid.TryParse(batch.RequestId, out Guid requestId) ||
                requestId.ToString("D") != batch.RequestId ||
                !requestIds.Add(batch.RequestId) ||
                !Guid.TryParse(batch.AccountId, out Guid accountId) ||
                accountId.ToString("D") != batch.AccountId ||
                batch.Mutations is null || batch.Mutations.Count is < 1 or > 50)
                throw new InvalidDataException("대기열 요청 ID/계정 또는 크기가 잘못됐습니다.");
            var endpoint = SyncClient.ParseEndpoint(batch.Endpoint);
            if (endpoint.Scheme != Uri.UriSchemeHttps ||
                endpoint.AbsoluteUri != batch.Endpoint)
                throw new InvalidDataException("대기열은 HTTPS 계정 서버에만 전송합니다.");
            total += batch.Mutations.Count;
            if (total > MaxItems)
                throw new InvalidDataException("대기열은 최대 1,000개까지만 보관합니다.");
            foreach (var item in batch.Mutations)
            {
                if (item is null || item.BaseRevision != 0 || item.Deleted ||
                    item.Payload is null || (item.Kind != "task" && item.Kind != "progress") ||
                    !seenPerAccount.Add(batch.AccountId + "#" + Key(item)))
                    throw new InvalidDataException("대기열에 중복/수정/삭제 기록이 포함됐습니다.");
                if (item.Kind == "task")
                {
                    if (!long.TryParse(item.Id, NumberStyles.None,
                        CultureInfo.InvariantCulture, out long id) || id < 1 ||
                        id.ToString(CultureInfo.InvariantCulture) != item.Id)
                        throw new InvalidDataException("대기열 일정 ID가 올바르지 않습니다.");
                    StudyTask task = JsonSerializer.Deserialize<StudyTask>(
                        StudyData.Serialize(item.Payload), StudyData.JsonOptions)
                        ?? throw new InvalidDataException("일정 내용이 없습니다.");
                    if (task.Id != id)
                        throw new InvalidDataException("대기열 일정 ID가 일치하지 않습니다.");
                    StudyData.SerializeAndValidateTasks(new TaskBackup { Tasks = { task } });
                }
                else
                {
                    if (!Guid.TryParse(item.Id, out Guid id) ||
                        id.ToString("D") != item.Id)
                        throw new InvalidDataException("대기열 진도 ID가 올바르지 않습니다.");
                    StudyProgressItem progress = JsonSerializer.Deserialize<StudyProgressItem>(
                        StudyData.Serialize(item.Payload), StudyData.JsonOptions)
                        ?? throw new InvalidDataException("진도 내용이 없습니다.");
                    if (progress.Id != item.Id)
                        throw new InvalidDataException("대기열 진도 ID가 일치하지 않습니다.");
                    StudyData.SerializeAndValidateProgress(
                        new ProgressBackup { Items = { progress } });
                }
            }
        }
    }

    internal static Document Read(string dataDirectory)
    {
        string path = FilePath(dataDirectory);
        if (!File.Exists(path)) return new Document();
        byte[] bytes = StudyData.ReadLimited(path);
        if (bytes.Length > MaxBytes)
            throw new InvalidDataException("동기화 대기열이 너무 큽니다.");
        Document doc = JsonSerializer.Deserialize<Document>(bytes, StudyData.JsonOptions)
            ?? throw new InvalidDataException("대기열 JSON이 비어 있습니다.");
        Validate(doc);
        return doc;
    }

    private static void Save(string dataDirectory, Document doc)
    {
        Validate(doc);
        byte[] bytes = StudyData.Serialize(doc);
        if (bytes.Length > MaxBytes)
            throw new InvalidDataException("대기열 한도를 초과했습니다.");
        StudyData.SaveAtomic(FilePath(dataDirectory), bytes);
    }

    /// <summary>
    /// Save a fixed, reviewable copy of all currently local, create-only items.
    /// No credentials, server calls or automatic upload. Existing queue keys
    /// are never overwritten; changed local items must be resolved manually.
    /// </summary>
    internal static int Enqueue(TaskBackup tasks, ProgressBackup progress,
                                string accountId, Uri endpoint, string dataDirectory)
    {
        if (!Guid.TryParse(accountId, out Guid account) ||
            account.ToString("D") != accountId ||
            SyncClient.ParseEndpoint(endpoint.AbsoluteUri).Scheme != Uri.UriSchemeHttps)
            throw new InvalidDataException("HTTPS 계정 서버와 정확한 계정 UUID가 필요합니다.");
        Document queue = Read(dataDirectory);
        var seen = queue.Batches
            .Where(x => x.AccountId == accountId)
            .SelectMany(x => x.Mutations.Select(Key))
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        // The entire local source is validated first by the existing transport.
        var empty = new SyncClient.Snapshot { Schema = 1, Cursor = 0 };
        var candidates = SyncClient.NewOnly(tasks, progress, empty)
            .Where(x => !seen.Contains(Key(x))).ToList();
        foreach (var chunk in candidates.Chunk(50))
        {
            queue.Batches.Add(new Batch
            {
                RequestId = Guid.NewGuid().ToString("D"),
                AccountId = accountId, Endpoint = endpoint.AbsoluteUri,
                Mutations = chunk.ToList()
            });
        }
        if (candidates.Count > 0) Save(dataDirectory, queue);
        return candidates.Count;
    }

    /// <summary>
    /// For every queued record, validate the current local value is unchanged.
    /// Never replay a stale edit, a locally deleted entry or a different account.
    /// </summary>
    internal static void ValidateCurrent(Batch batch,
                                         TaskBackup tasks, ProgressBackup progress)
    {
        StudyData.SerializeAndValidateTasks(tasks);
        StudyData.SerializeAndValidateProgress(progress);
        var current = SyncClient.NewOnly(tasks, progress,
            new SyncClient.Snapshot { Schema = 1, Cursor = 0 })
            .ToDictionary(Key, StringComparer.OrdinalIgnoreCase);
        foreach (var change in batch.Mutations)
        {
            if (!current.TryGetValue(Key(change), out var live) ||
                !string.Equals(
                    Encoding.UTF8.GetString(StudyData.Serialize(change.Payload)),
                    Encoding.UTF8.GetString(StudyData.Serialize(live.Payload)),
                    StringComparison.Ordinal))
                throw new InvalidDataException("로컬 학습 기록이 대기열 저장 후 변경됐습니다. 전송을 중단합니다.");
        }
    }

    internal static void ConfirmAccount(Batch batch, SyncClient.Session session, Uri endpoint)
    {
        if (session.Schema != 1 || session.AccountId != batch.AccountId ||
            session.Audience != "device" || session.Scope != "read-write" ||
            session.ExpiresAt <= session.ServerTime ||
            batch.Endpoint != endpoint.AbsoluteUri)
            throw new InvalidOperationException(
                "대기열 계정/서버와 인증한 쓰기 권한 계정이 일치하지 않습니다.");
    }

    internal static void Acknowledge(string dataDirectory, string requestId)
    {
        Document queue = Read(dataDirectory);
        var found = queue.Batches.SingleOrDefault(x => x.RequestId == requestId);
        if (found is null)
            throw new InvalidDataException("승인할 대기열 요청이 없습니다.");
        queue.Batches.Remove(found);
        Save(dataDirectory, queue);
    }

    internal static void Discard(string dataDirectory, string requestId) =>
        Acknowledge(dataDirectory, requestId); // UI obtains separate user consent
}
