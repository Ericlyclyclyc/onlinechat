package net.mcless.dev.onlinechat.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mcless.dev.onlinechat.OnlineChat;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Hybrid in-memory + on-disk store for bridged chat messages.
 *
 * <p>Every message is assigned a monotonically increasing {@code seq} and written <em>through</em> to an
 * append-only JSON-Lines archive under the run directory (one JSON object per line, chronological, each
 * carrying its {@code id}=seq). Only the most recent {@code memoryCap} messages are additionally held in a
 * RAM cache ({@link #recent}) for fast broadcast and for serving the initial page; older messages live on
 * disk and are read back on demand when a client scrolls up ("load more"). Write-through means a crash can
 * never lose an already-acknowledged message, and the archive doubles as a deployable backup.
 *
 * <p>The seq space is contiguous and the archive is complete (recent ⊆ disk), so a page is served either
 * from the RAM cache (fast path) or by seeking straight into the archive via {@link #diskOffsets} — both
 * O(page&nbsp;size), never O(archive&nbsp;size). All public methods are guarded by a single lock: chat
 * volume is human-scale, so a coarse lock keeps the invariants (seq contiguity, cache/disk agreement,
 * offset index) trivially race-free. Callers that read old pages should dispatch off the Netty event loop
 * (see {@code HttpApiHandler#blockingPool}) since those reads touch the disk.
 */
public class MessageStore {
    /** A page of client-ready messages plus whether older history still exists before the returned range. */
    public record Page(List<JsonObject> messages, boolean hasMore) {}

    private static final Gson GSON = new Gson();
    /** Hard ceiling on a single page request so a client cannot ask for the whole archive at once. */
    private static final int MAX_LIMIT = 200;

    private final Path file;
    private final int memoryCap;
    private final Object lock = new Object();

    /** Most recent messages (newest at the tail), also present on disk. Bounded by {@link #memoryCap}. */
    private final Deque<Entry> recent = new ArrayDeque<>();
    /** seq of each archived line, ascending. Parallel to {@link #diskOffsets}. */
    private final List<Long> diskSeqs = new ArrayList<>();
    /** Byte offset of each archived line, parallel to {@link #diskSeqs}. */
    private final List<Long> diskOffsets = new ArrayList<>();
    private long nextSeq = 1;   // next seq to hand out
    private long fileLen = 0;   // current append position (== archive byte length)

    private record Entry(long seq, ChatBridge.ChatMessage msg) {}

    public MessageStore(Path file, int memoryCap) {
        this.file = file;
        this.memoryCap = Math.max(1, memoryCap);
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    /**
     * Reads the archive once at startup: rebuilds the offset index, warms the recent cache with the newest
     * {@code memoryCap} messages, and truncates any incomplete trailing line left by a crash mid-write.
     */
    public void load() {
        synchronized (lock) {
            recent.clear();
            diskSeqs.clear();
            diskOffsets.clear();
            nextSeq = 1;
            fileLen = 0;
            try {
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                if (!Files.exists(file)) {
                    Files.createFile(file);
                    restrictPermissions(file);
                    OnlineChat.LOGGER.info("[OnlineChat] Created empty chat archive {}", file);
                    return;
                }
                List<Entry> parsed = new ArrayList<>();
                long lastGoodEnd = 0;
                boolean corrupt = false;
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
                    ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
                    long lineStart = 0;
                    long pos = 0;
                    int b;
                    while ((b = in.read()) != -1) {
                        if (b == '\n') {
                            long endOfLine = pos + 1;
                            if (lineBuf.size() > 0) {
                                Entry e = parseLine(lineBuf.toString(StandardCharsets.UTF_8));
                                if (e == null) {
                                    // Corrupt / half-written line: keep everything before it, drop the rest.
                                    corrupt = true;
                                    lineBuf.reset();
                                    break;
                                }
                                diskSeqs.add(e.seq());
                                diskOffsets.add(lineStart);
                                parsed.add(e);
                            }
                            lastGoodEnd = endOfLine;
                            lineBuf.reset();
                            pos = endOfLine;
                            lineStart = pos;
                        } else {
                            lineBuf.write(b);
                            pos++;
                        }
                    }
                }
                if (lastGoodEnd != Files.size(file)) {
                    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                        raf.setLength(lastGoodEnd);
                    }
                    if (corrupt) {
                        OnlineChat.LOGGER.warn("[OnlineChat] Chat archive {} had a corrupt/incomplete tail; truncated to {} bytes", file, lastGoodEnd);
                    }
                }
                fileLen = lastGoodEnd;
                if (!parsed.isEmpty()) {
                    nextSeq = parsed.get(parsed.size() - 1).seq() + 1;
                    int from = Math.max(0, parsed.size() - memoryCap);
                    for (int i = from; i < parsed.size(); i++) recent.addLast(parsed.get(i));
                }
                restrictPermissions(file);
                OnlineChat.LOGGER.info("[OnlineChat] Chat archive {}: {} message(s) on disk, {} cached in memory (cap {})",
                        file, parsed.size(), recent.size(), memoryCap);
            } catch (Exception e) {
                OnlineChat.LOGGER.error("[OnlineChat] Failed to load chat archive {}; continuing with an empty store", file, e);
            }
        }
    }

    // ─────────────────────────── Write ───────────────────────────

    /**
     * Persists {@code msg} (write-through), assigns it the next seq, and refreshes the recent cache.
     * Returns the assigned seq, which callers embed as the message {@code id} when broadcasting.
     * On a rare disk failure the seq is still returned (so live clients see the message) but nothing is
     * committed — {@link #nextSeq} is not advanced, keeping the archive gap-free.
     */
    public long append(ChatBridge.ChatMessage msg) {
        synchronized (lock) {
            long seq = nextSeq;
            long startOffset = fileLen;
            byte[] bytes = (GSON.toJson(toClientJson(seq, msg)) + "\n").getBytes(StandardCharsets.UTF_8);
            try {
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                // Commit in-memory state only after the bytes are safely on disk.
                fileLen = startOffset + bytes.length;
                diskSeqs.add(seq);
                diskOffsets.add(startOffset);
                nextSeq = seq + 1;
                recent.addLast(new Entry(seq, msg));
                while (recent.size() > memoryCap) recent.removeFirst();   // evicted messages remain on disk
            } catch (Exception e) {
                OnlineChat.LOGGER.error("[OnlineChat] Failed to persist a chat message to {}; it will be broadcast but not archived", file, e);
                // Roll back any partial bytes so the archive and the offset index stay consistent; nextSeq is
                // left unadvanced, so this seq is simply reused by the next (successful) append - no gap.
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) { raf.setLength(startOffset); } catch (Exception ignored) {}
                fileLen = startOffset;
            }
            return seq;
        }
    }

    // ─────────────────────────── Read / pagination ───────────────────────────

    /** The newest {@code limit} messages (chronological), plus whether older history exists. */
    public Page latest(int limit) {
        synchronized (lock) {
            return rangeBefore(nextSeq, limit);
        }
    }

    /**
     * Up to {@code limit} messages with seq strictly below {@code cursorSeq} (chronological), plus whether
     * even older history exists. This backs the client's "scroll up to load more" and never overlaps a
     * previously returned page because the cursor is exclusive and seqs are unique and contiguous.
     */
    public Page before(long cursorSeq, int limit) {
        synchronized (lock) {
            return rangeBefore(cursorSeq, limit);
        }
    }

    private Page rangeBefore(long cursorSeq, int limit) {   // assumes lock held
        int lim = Math.max(1, Math.min(MAX_LIMIT, limit));
        if (diskSeqs.isEmpty()) return new Page(List.of(), false);
        int hi = floorIndex(cursorSeq - 1);          // index of the newest archived seq < cursor
        if (hi < 0) return new Page(List.of(), false);
        int lo = Math.max(0, hi - lim + 1);
        List<Entry> entries = readEntries(lo, hi);
        List<JsonObject> msgs = new ArrayList<>(entries.size());
        for (Entry e : entries) msgs.add(toClientJson(e.seq(), e.msg()));
        return new Page(msgs, lo > 0);               // hasMore iff something older than `lo` remains
    }

    /**
     * Case-insensitive substring search over the whole archive, newest first.
     * <p>
     * Matches are checked against the message text and the author name. {@code beforeSeq <= 0} starts
     * from the newest message; otherwise only messages with seq strictly below {@code beforeSeq} are
     * considered, so the caller can page through many hits with the same {@code id} cursor the history
     * API uses. The scan walks the file backwards in chunks (via {@link #diskOffsets}), so it is
     * O(archive) in the worst case but never loads the whole archive into memory at once. Callers
     * should run it off the Netty event loop.
     */
    public Page search(String query, long beforeSeq, int limit) {
        synchronized (lock) {
            int lim = Math.max(1, Math.min(MAX_LIMIT, limit));
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            if (q.isEmpty() || q.length() > 64) return new Page(List.of(), false);
            long cursor = beforeSeq <= 0 ? Long.MAX_VALUE : beforeSeq - 1;
            int hi = floorIndex(cursor);
            List<JsonObject> out = new ArrayList<>();
            boolean hasMore = false;
            final int chunk = 64;
            while (hi >= 0) {
                int lo = Math.max(0, hi - chunk + 1);
                List<Entry> entries = readEntries(lo, hi);
                for (int i = entries.size() - 1; i >= 0; i--) {
                    Entry e = entries.get(i);
                    if (matches(e.msg(), q)) {
                        if (out.size() >= lim) { hasMore = true; break; }
                        out.add(toClientJson(e.seq(), e.msg()));
                    }
                }
                if (hasMore) break;
                hi = lo - 1;
            }
            return new Page(out, hasMore);
        }
    }

    private static boolean matches(ChatBridge.ChatMessage m, String q) {
        if (m.text() != null && m.text().toLowerCase(Locale.ROOT).contains(q)) return true;
        return m.author() != null && m.author().toLowerCase(Locale.ROOT).contains(q);
    }

    /** Index of the largest archived seq {@code <= target}, or -1 when none qualifies. */
    private int floorIndex(long target) {
        int i = Collections.binarySearch(diskSeqs, target);
        return i >= 0 ? i : (-i - 1) - 1;
    }

    /** Reads archived entries {@code [lo, hi]} (indices), from the RAM cache when fully covered, else disk. */
    private List<Entry> readEntries(int lo, int hi) {   // assumes lock held
        long loSeq = diskSeqs.get(lo);
        long hiSeq = diskSeqs.get(hi);
        if (!recent.isEmpty()) {
            long rLo = recent.peekFirst().seq();
            long rHi = recent.peekLast().seq();
            if (loSeq >= rLo && hiSeq <= rHi) {
                List<Entry> out = new ArrayList<>(hi - lo + 1);
                for (Entry e : recent) {
                    if (e.seq() >= loSeq && e.seq() <= hiSeq) out.add(e);
                }
                return out;
            }
        }
        List<Entry> out = new ArrayList<>(hi - lo + 1);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            for (int i = lo; i <= hi; i++) {
                raf.seek(diskOffsets.get(i));
                Entry e = parseLine(readLineUtf8(raf));
                if (e != null) out.add(e);
            }
        } catch (Exception e) {
            OnlineChat.LOGGER.error("[OnlineChat] Failed to read chat archive {} (range {}..{})", file, loSeq, hiSeq, e);
        }
        return out;
    }

    // ─────────────────────────── (De)serialisation ───────────────────────────

    /** Client-facing JSON for a message: the normal payload plus its {@code id} (the pagination cursor). */
    public static JsonObject toClientJson(long seq, ChatBridge.ChatMessage m) {
        JsonObject o = m.toJson();
        o.addProperty("id", seq);
        return o;
    }

    private static Entry parseLine(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty()) return null;
        try {
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            if (!o.has("id") || o.get("id").isJsonNull()) return null;
            return new Entry(o.get("id").getAsLong(), fromJson(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static ChatBridge.ChatMessage fromJson(JsonObject o) {
        long ts = o.has("ts") && !o.get("ts").isJsonNull() ? o.get("ts").getAsLong() : 0L;
        String type = o.has("type") && !o.get("type").isJsonNull() ? o.get("type").getAsString() : "system";
        ChatBridge.Kind kind = switch (type) {
            case "chat" -> ChatBridge.Kind.CHAT;
            case "web" -> ChatBridge.Kind.WEB;
            default -> ChatBridge.Kind.SYSTEM;
        };
        String text = o.has("text") && !o.get("text").isJsonNull() ? o.get("text").getAsString() : "";
        return new ChatBridge.ChatMessage(ts, kind, optString(o, "author"), optString(o, "authorUuid"), text, optString(o, "systemKind"));
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /** Reads one UTF-8 line from the current file pointer (up to but excluding '\n'); null at EOF. */
    private static String readLineUtf8(RandomAccessFile raf) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        boolean any = false;
        while ((b = raf.read()) != -1) {
            any = true;
            if (b == '\n') break;
            buf.write(b);
        }
        return any ? buf.toString(StandardCharsets.UTF_8) : null;
    }

    /** Best-effort owner-only permissions (POSIX 0600); a silent no-op on non-POSIX filesystems (Windows). */
    private static void restrictPermissions(Path f) {
        try {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem; nothing more we can do portably.
        }
    }
}
