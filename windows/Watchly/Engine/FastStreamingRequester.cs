// Port of MonoTorrent 3.0.2's StreamingPieceRequester (MIT, Copyright (C) 2021 Alan McGovern).
//
// Upstream keeps at most 4 / 3 / 2 blocks (16 KB each) in flight per peer — for the pieces at
// the playhead, the 45 after them, and everything else. That is one round trip's worth of a
// few dozen KB, so a swarm qBittorrent pulled 3.8 MB/s from gave ~500 KB/s here. Past the two
// pieces at the playhead (still requested from two peers at once, so still capped) each peer
// now gets MaxPendingRequests: MonoTorrent's own per-peer depth, which grows with that peer's
// speed up to what the peer says it accepts — what its normal (non-streaming) picker uses.
// Everything else is upstream's.

using MonoTorrent;
using MonoTorrent.PiecePicking;

namespace Watchly.Engine;

public sealed class FastStreamingRequester : IStreamingPieceRequester
{
    bool refreshAfterSeeking;

    IMessageEnqueuer? enqueuer;
    IPieceRequesterData? torrentData;
    IReadOnlyList<ReadOnlyBitField>? ignoringBitfields;
    IPiecePicker? lowPriorityPicker;
    IPiecePicker? highPriorityPicker;
    BitField? temp;

    public bool InEndgameMode => false;

    /// <summary>The piece the player is reading.</summary>
    public int HighPriorityPieceIndex { get; private set; }

    /// <summary>Pieces kept buffered past the read position.</summary>
    public const int HighPriorityCount = 15;

    public const int LowPriorityCount = HighPriorityCount * 2;

    public void Initialise(IPieceRequesterData data, IMessageEnqueuer messageEnqueuer, ReadOnlySpan<ReadOnlyBitField> ignoring)
    {
        torrentData = data;
        enqueuer = messageEnqueuer;
        ignoringBitfields = ignoring.ToArray();
        temp = new BitField(data.PieceCount);

        var standardPicker = new StandardPicker();
        highPriorityPicker = IgnoringPicker.Wrap(new PriorityPicker(standardPicker), ignoringBitfields);
        lowPriorityPicker = IgnoringPicker.Wrap(new PriorityPicker(new RarestFirstPicker(new RandomisedPicker(standardPicker))), ignoringBitfields);
        lowPriorityPicker.Initialise(data);
        highPriorityPicker.Initialise(data);
    }

    ReadOnlyBitField[] otherAvailableCache = Array.Empty<ReadOnlyBitField>();

    public void AddRequests(ReadOnlySpan<(IRequester Peer, ReadOnlyBitField Available)> peers)
    {
        if (!refreshAfterSeeking || torrentData is null || enqueuer is null)
            return;
        refreshAfterSeeking = false;
        var allPeers = peers.ToArray().OrderBy(t => -t.Peer.DownloadSpeed).ToArray();

        // Only peers that support cancellation are worth cancelling on.
        var start = HighPriorityPieceIndex;
        var end = Math.Min(torrentData.PieceCount - 1, start + HighPriorityCount - 1);
        var bitfield = GenerateAlreadyHaves();
        if (bitfield.FirstFalse(start, end) != -1)
        {
            foreach (var peer in allPeers.Where(p => p.Peer.CanCancelRequests))
            {
                if (HighPriorityPieceIndex > 0)
                    CancelRequests(peer.Peer, 0, HighPriorityPieceIndex - 1);
                if (HighPriorityPieceIndex + HighPriorityCount < bitfield.Length)
                    CancelRequests(peer.Peer, HighPriorityPieceIndex + HighPriorityCount, bitfield.Length - 1);
            }
        }

        if (otherAvailableCache.Length < peers.Length)
            otherAvailableCache = new ReadOnlyBitField[peers.Length];
        var otherAvailable = otherAvailableCache.AsSpan(0, peers.Length);
        for (var i = 0; i < otherAvailable.Length; i++)
            otherAvailable[i] = peers[i].Available;

        // The piece at the new position first, from the fastest peers, fast-peer ones first.
        var fastestPeers = allPeers.Where(t => t.Peer.DownloadSpeed > 50 * 1024).ToArray();
        foreach (var supportsFastPeer in new[] { true, false })
            for (var i = 0; i < 4; i++)
                foreach (var peer in fastestPeers.Where(p => p.Peer.SupportsFastPeer == supportsFastPeer))
                    AddRequests(peer.Peer, peer.Available, otherAvailable, HighPriorityPieceIndex, Math.Min(HighPriorityPieceIndex + 1, bitfield.Length - 1), 2, (i + 1) * 2);

        foreach (var peer in peers)
            AddRequests(peer.Peer, peer.Available, otherAvailable);
    }

    public void AddRequests(IRequester peer, ReadOnlyBitField available, ReadOnlySpan<ReadOnlyBitField> allPeers)
    {
        if (torrentData is null)
            return;
        // The two pieces at the playhead are asked of two peers at once; kept shallow, it's duplicate traffic.
        var pieceCount = torrentData.PieceCount;
        for (var i = HighPriorityPieceIndex; i < pieceCount && i <= HighPriorityPieceIndex + 1; i++)
            AddRequests(peer, available, allPeers, HighPriorityPieceIndex, HighPriorityPieceIndex, 2, 4);

        var lowPriorityEnd = Math.Min(pieceCount - 1, HighPriorityPieceIndex + LowPriorityCount - 1);
        AddRequests(peer, available, allPeers, HighPriorityPieceIndex, lowPriorityEnd, 1, peer.MaxPendingRequests);
        AddRequests(peer, available, allPeers, 0, pieceCount - 1, 1, peer.MaxPendingRequests);
    }

    void AddRequests(IRequester peer, ReadOnlyBitField available, ReadOnlySpan<ReadOnlyBitField> allPeers, int startPieceIndex, int endPieceIndex, int maxDuplicates, int preferredMaxRequests)
    {
        if (!peer.CanRequestMorePieces || torrentData is null || enqueuer is null)
            return;
        var maxTotalRequests = Math.Min(preferredMaxRequests, peer.MaxPendingRequests);

        if (!peer.IsChoking)
        {
            while (peer.CanRequestMorePieces && peer.AmRequestingPiecesCount < maxTotalRequests)
            {
                if (lowPriorityPicker!.ContinueAnyExistingRequest(peer, available, startPieceIndex, endPieceIndex, maxDuplicates, out var request))
                    enqueuer.EnqueueRequest(peer, request);
                else
                    break;
            }
        }

        // A peer choking us still sends pieces from its allowed-fast set.
        if (peer.SupportsFastPeer && peer.IsChoking)
        {
            while (peer.CanRequestMorePieces && peer.AmRequestingPiecesCount < maxTotalRequests)
            {
                if (lowPriorityPicker!.ContinueExistingRequest(peer, startPieceIndex, endPieceIndex, out var segment))
                    enqueuer.EnqueueRequest(peer, segment);
                else
                    break;
            }
        }

        if (!peer.IsChoking || (peer.SupportsFastPeer && peer.IsAllowedFastPieces.Count > 0))
        {
            BitField? filtered = null;
            // PieceSegment is two ints: at most 4 KB of stack, the loop picks again for anything more.
            Span<PieceSegment> buffer = stackalloc PieceSegment[Math.Clamp(maxTotalRequests, 1, 512)];
            while (peer.CanRequestMorePieces && peer.AmRequestingPiecesCount < maxTotalRequests)
            {
                filtered ??= GenerateAlreadyHaves().Not().And(available);
                var slice = buffer.Slice(0, Math.Min(buffer.Length, maxTotalRequests - peer.AmRequestingPiecesCount));
                var requested = PriorityPick(peer, filtered, allPeers, startPieceIndex, endPieceIndex, slice);
                if (requested > 0)
                    enqueuer.EnqueueRequests(peer, slice.Slice(0, requested));
                else
                    break;
            }
        }
    }

    BitField GenerateAlreadyHaves()
    {
        temp!.From(ignoringBitfields![0]);
        for (var i = 1; i < ignoringBitfields.Count; i++)
            temp.Or(ignoringBitfields[i]);
        return temp;
    }

    int PriorityPick(IRequester peer, ReadOnlyBitField available, ReadOnlySpan<ReadOnlyBitField> otherPeers, int startIndex, int endIndex, Span<PieceSegment> requests)
    {
        int requestCount;
        if (HighPriorityPieceIndex >= startIndex && HighPriorityPieceIndex <= endIndex)
        {
            var start = HighPriorityPieceIndex;
            var end = Math.Min(endIndex, HighPriorityPieceIndex + HighPriorityCount - 1);
            for (var prioritised = start; prioritised <= start + 1 && prioritised <= end; prioritised++)
            {
                if (!available[prioritised]) continue;
                if ((requestCount = highPriorityPicker!.PickPiece(peer, available, otherPeers, prioritised, prioritised, requests)) > 0)
                    return requestCount;
                if (highPriorityPicker.ContinueAnyExistingRequest(peer, available, prioritised, prioritised, 3, out requests[0]))
                    return 1;
            }
            if ((requestCount = highPriorityPicker!.PickPiece(peer, available, otherPeers, start, end, requests)) > 0)
                return requestCount;
        }

        var lowPriorityEndIndex = Math.Min(HighPriorityPieceIndex + LowPriorityCount, endIndex);
        if ((requestCount = lowPriorityPicker!.PickPiece(peer, available, otherPeers, HighPriorityPieceIndex, lowPriorityEndIndex, requests)) > 0)
            return requestCount;
        // Upstream stopped here at 2 requests per peer; the caller's limit is the only limit now.
        return lowPriorityPicker.PickPiece(peer, available, otherPeers, startIndex, endIndex, requests);
    }

    public void SeekToPosition(ITorrentManagerFile file, long position)
    {
        var oldIndex = HighPriorityPieceIndex;
        ReadToPosition(file, position);
        refreshAfterSeeking |= oldIndex != HighPriorityPieceIndex;
    }

    public void ReadToPosition(ITorrentManagerFile file, long position)
    {
        if (torrentData is null) return;
        HighPriorityPieceIndex = position >= file.Length
            ? file.EndPieceIndex
            : torrentData.ByteOffsetToPieceIndex(position + file.OffsetInTorrent);
    }

    public bool ValidatePiece(IRequester peer, PieceSegment blockInfo, out bool pieceComplete, HashSet<IRequester> peersInvolved)
        => highPriorityPicker!.ValidatePiece(peer, blockInfo, out pieceComplete, peersInvolved);

    public bool IsInteresting(IRequester peer, ReadOnlyBitField bitfield)
        => highPriorityPicker!.IsInteresting(peer, bitfield);

    PieceSegment[] cancellationsCache = Array.Empty<PieceSegment>();

    public void CancelRequests(IRequester peer, int startIndex, int endIndex)
    {
        if (highPriorityPicker is null || enqueuer is null) return;
        if (cancellationsCache.Length < peer.AmRequestingPiecesCount)
            cancellationsCache = new PieceSegment[peer.AmRequestingPiecesCount];
        var cancellations = cancellationsCache.AsSpan(0, peer.AmRequestingPiecesCount);
        var cancelled = highPriorityPicker.CancelRequests(peer, startIndex, endIndex, cancellations);
        enqueuer.EnqueueCancellations(peer, cancellations.Slice(0, cancelled));
    }

    public void RequestRejected(IRequester peer, PieceSegment blockInfo) => highPriorityPicker!.RequestRejected(peer, blockInfo);

    public int CurrentRequestCount() => highPriorityPicker!.CurrentRequestCount();
}
