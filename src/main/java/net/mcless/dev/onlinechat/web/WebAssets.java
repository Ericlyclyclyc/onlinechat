package net.mcless.dev.onlinechat.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mcless.dev.onlinechat.OnlineChat;
import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Exposes the bundled web front-end ({@code /web/**} inside the jar) as editable files on disk.
 * <p>
 * The hidden marker file {@value #MARKER} doubles as a manifest: it records the SHA-256 of every file
 * <em>as shipped by the jar</em> at the time it was last written. On start-up:
 * <ul>
 *   <li>Marker absent (fresh install, or an operator deleted it): the whole directory is overwritten with a
 *       pristine copy and the manifest is written.</li>
 *   <li>Marker present: an in-place upgrade. Files the operator never touched (on-disk hash equals the hash
 *       recorded in the manifest) are refreshed to the new bundled version; files that differ from what the
 *       previous version shipped are treated as customisations and left alone (a warning lists the ones
 *       whose bundled version changed so the operator can merge by hand); files added in the new version are
 *       copied; files the new version no longer ships are deleted when unmodified. An old marker without a
 *       manifest (pre-manifest versions) is handled conservatively: nothing that differs from the jar is
 *       overwritten or deleted.</li>
 * </ul>
 * {@link HttpApiHandler} serves files from this directory first and falls back to the jar for anything
 * missing. Locale files ({@code locales/*.json}) read from disk are additionally overlaid on the bundled
 * copy, so a customised translation keeps working when a newer version introduces new keys.
 */
public final class WebAssets {
    public static final String MARKER = ".exist";
    private static final String RESOURCE_ROOT = "web";
    private static final String MANIFEST_HEADER = "# OnlineChat web front-end manifest - do not edit.";
    private static final String MANIFEST_HINT = "# Delete this file to re-extract the default web front-end on next start.";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final HexFormat HEX = HexFormat.of();

    private record Cached(byte[] data, FileTime modified) {}

    private final Path dir;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public WebAssets(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() { return dir; }

    /** Populates or upgrades {@link #dir()} from the bundled front-end; see the class comment for the rules. */
    public void extractIfNeeded() {
        Path source = bundledRoot();
        if (source == null) {
            OnlineChat.LOGGER.error("[OnlineChat] Could not locate the bundled web resources; the front-end will be served from the jar only");
            return;
        }
        Map<String, byte[]> bundled;
        try {
            bundled = readBundled(source);
        } catch (IOException e) {
            OnlineChat.LOGGER.error("[OnlineChat] Failed to read the bundled web front-end", e);
            return;
        }
        Path marker = dir.resolve(MARKER);
        try {
            if (Files.exists(marker)) {
                upgrade(marker, bundled);
            } else {
                extractAll(marker, bundled);
            }
        } catch (IOException e) {
            OnlineChat.LOGGER.error("[OnlineChat] Failed to extract the web front-end to {}", dir, e);
        }
        cache.clear();
    }

    private void extractAll(Path marker, Map<String, byte[]> bundled) throws IOException {
        Files.createDirectories(dir);
        Map<String, String> manifest = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : bundled.entrySet()) {
            write(e.getKey(), e.getValue());
            manifest.put(e.getKey(), sha256(e.getValue()));
        }
        writeManifest(marker, manifest);
        OnlineChat.LOGGER.info("[OnlineChat] Extracted {} web front-end file(s) to {}", bundled.size(), dir);
    }

    private void upgrade(Path marker, Map<String, byte[]> bundled) throws IOException {
        Map<String, String> previous = readManifest(marker);
        Map<String, String> manifest = new LinkedHashMap<>();
        List<String> added = new ArrayList<>(), updated = new ArrayList<>(), removed = new ArrayList<>(), conflicts = new ArrayList<>();
        int customised = 0;

        for (Map.Entry<String, byte[]> e : bundled.entrySet()) {
            String rel = e.getKey();
            byte[] data = e.getValue();
            String jarHash = sha256(data);
            manifest.put(rel, jarHash);
            Path target = dir.resolve(rel).normalize();
            if (!target.startsWith(dir)) continue;
            if (!Files.isRegularFile(target)) {
                write(rel, data);
                added.add(rel);
                continue;
            }
            String diskHash = sha256(Files.readAllBytes(target));
            if (diskHash.equals(jarHash)) continue;
            String shipped = previous.get(rel);
            if (shipped != null && shipped.equals(diskHash)) {
                // Untouched copy of what the previous version shipped: safe to refresh.
                write(rel, data);
                updated.add(rel);
                continue;
            }
            // Operator customisation (or unknown provenance): keep it, but flag when the bundled version moved on.
            customised++;
            if (shipped == null || !shipped.equals(jarHash)) conflicts.add(rel);
        }

        // Files the previous version shipped but this one no longer does.
        for (Map.Entry<String, String> e : previous.entrySet()) {
            String rel = e.getKey();
            if (bundled.containsKey(rel)) continue;
            Path target = dir.resolve(rel).normalize();
            if (!target.startsWith(dir) || !Files.isRegularFile(target)) continue;
            if (sha256(Files.readAllBytes(target)).equals(e.getValue())) {
                Files.delete(target);
                deleteEmptyParents(target.getParent());
                removed.add(rel);
            } else {
                customised++;
                conflicts.add(rel + " (no longer shipped)");
            }
        }

        writeManifest(marker, manifest);
        if (added.isEmpty() && updated.isEmpty() && removed.isEmpty()) {
            OnlineChat.LOGGER.info("[OnlineChat] Serving web front-end from {} ({} customised file(s) kept)", dir, customised);
        } else {
            OnlineChat.LOGGER.info("[OnlineChat] Upgraded web front-end in {}: {} added, {} updated, {} removed, {} customised file(s) kept",
                    dir, added.size(), updated.size(), removed.size(), customised);
            if (!added.isEmpty()) OnlineChat.LOGGER.info("[OnlineChat]   added:   {}", added);
            if (!updated.isEmpty()) OnlineChat.LOGGER.info("[OnlineChat]   updated: {}", updated);
            if (!removed.isEmpty()) OnlineChat.LOGGER.info("[OnlineChat]   removed: {}", removed);
        }
        if (!conflicts.isEmpty()) {
            OnlineChat.LOGGER.warn("[OnlineChat] {} customised web file(s) differ from the version bundled with this release and were NOT updated; "
                    + "review them against the jar (or delete {} to reset everything): {}", conflicts.size(), MARKER, conflicts);
        }
    }

    /**
     * Returns the on-disk copy of {@code relativePath} (e.g. {@code index.html}, {@code locales/en.json}),
     * or {@code null} when it does not exist there. Rejects anything escaping the directory.
     * Locale files are overlaid on the bundled copy so keys missing from a customised file fall back to the default.
     */
    public byte[] read(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        String rel = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        if (rel.isEmpty() || rel.equals(MARKER)) return null;
        Path file = dir.resolve(rel).normalize();
        if (!file.startsWith(dir) || file.getFileName().toString().equals(MARKER)) return null;
        try {
            if (!Files.isRegularFile(file)) {
                cache.remove(rel);
                return null;
            }
            FileTime mtime = Files.getLastModifiedTime(file);
            Cached c = cache.get(rel);
            if (c != null && c.modified.equals(mtime)) return c.data;
            byte[] data = Files.readAllBytes(file);
            if (isLocale(rel)) data = mergeLocale(rel, data);
            cache.put(rel, new Cached(data, mtime));
            return data;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isLocale(String rel) {
        return rel.startsWith("locales/") && rel.endsWith(".json") && rel.indexOf('/', "locales/".length()) < 0;
    }

    /** Bundled locale as the base, on-disk keys on top; returns the disk bytes untouched if either side is not a JSON object. */
    private static byte[] mergeLocale(String rel, byte[] disk) {
        try (InputStream in = WebAssets.class.getResourceAsStream("/" + RESOURCE_ROOT + "/" + rel)) {
            if (in == null) return disk;
            JsonElement baseEl = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            JsonElement overEl = JsonParser.parseString(new String(disk, StandardCharsets.UTF_8));
            if (!baseEl.isJsonObject() || !overEl.isJsonObject()) return disk;
            JsonObject merged = baseEl.getAsJsonObject().deepCopy();
            deepMerge(merged, overEl.getAsJsonObject());
            return GSON.toJson(merged).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return disk;
        }
    }

    private static void deepMerge(JsonObject into, JsonObject from) {
        for (Map.Entry<String, JsonElement> e : from.entrySet()) {
            JsonElement existing = into.get(e.getKey());
            if (existing != null && existing.isJsonObject() && e.getValue().isJsonObject()) {
                deepMerge(existing.getAsJsonObject(), e.getValue().getAsJsonObject());
            } else {
                into.add(e.getKey(), e.getValue());
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void write(String rel, byte[] data) throws IOException {
        Path target = dir.resolve(rel).normalize();
        if (!target.startsWith(dir)) return;
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Removes now-empty directories left behind by a deleted file, never going above {@link #dir}. */
    private void deleteEmptyParents(Path p) {
        while (p != null && p.startsWith(dir) && !p.equals(dir)) {
            try (Stream<Path> s = Files.list(p)) {
                if (s.findAny().isPresent()) return;
            } catch (IOException e) {
                return;
            }
            try {
                Files.delete(p);
            } catch (IOException e) {
                return;
            }
            p = p.getParent();
        }
    }

    /** Reads every regular file under the bundled {@code web/} root into memory, keyed by forward-slash relative path. */
    private static Map<String, byte[]> readBundled(Path source) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                if (Files.isDirectory(p)) continue;
                // Both paths live in the mod's filesystem; go through a string so the relative part can be
                // re-resolved against the default filesystem.
                String rel = source.relativize(p).toString().replace('\\', '/');
                if (rel.isEmpty() || rel.equals(MARKER)) continue;
                out.put(rel, Files.readAllBytes(p));
            }
        }
        return out;
    }

    /** Parses {@code <sha256hex> <relative/path>} lines; comments, blanks and {@code key=value} lines are ignored. */
    private static Map<String, String> readManifest(Path marker) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int sp = s.indexOf(' ');
                if (sp != 64 || s.length() <= 65) continue;
                out.put(s.substring(sp + 1).trim(), s.substring(0, sp).toLowerCase());
            }
        } catch (IOException e) {
            OnlineChat.LOGGER.warn("[OnlineChat] Could not read the web front-end manifest {}; treating every file as customised", marker);
        }
        return out;
    }

    private void writeManifest(Path marker, Map<String, String> manifest) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(MANIFEST_HEADER).append('\n').append(MANIFEST_HINT).append('\n');
        sb.append("version=").append(modVersion()).append('\n');
        for (Map.Entry<String, String> e : manifest.entrySet()) {
            sb.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
        }
        // A hidden file cannot be opened for truncating writes on Windows; recreate it instead.
        Files.deleteIfExists(marker);
        Files.writeString(marker, sb.toString(), StandardCharsets.UTF_8);
        hide(marker);
    }

    private static String sha256(byte[] data) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String modVersion() {
        try {
            return ModList.get().getModContainerById(OnlineChat.MODID)
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Locates the bundled {@code web/} resource root. The mod's own classloader is the single source
     * of truth here: it handles dev workspaces (where FML loads resources from a separate
     * {@code resources/main} path), union filesystems and production jars alike. For a jar-backed
     * resource the root is opened through a zip filesystem; for a plain file it is the directory
     * containing {@code web/index.html}.
     */
    private static Path bundledRoot() {
        try {
            java.net.URL url = WebAssets.class.getClassLoader().getResource(RESOURCE_ROOT + "/index.html");
            if (url == null) url = WebAssets.class.getResource("/" + RESOURCE_ROOT + "/index.html");
            if (url == null) return null;
            if ("jar".equals(url.getProtocol())) {
                java.net.JarURLConnection jc = (java.net.JarURLConnection) url.openConnection();
                Path jarPath = Paths.get(jc.getJarFileURL().toURI());
                FileSystem fs = FileSystems.newFileSystem(java.net.URI.create("jar:" + jarPath.toUri()), java.util.Map.of());
                Path root = fs.getPath("/" + RESOURCE_ROOT);
                if (!Files.isDirectory(root)) {
                    try { fs.close(); } catch (IOException ignored) {}
                    return null;
                }
                return root; // the filesystem stays open for as long as this Path is referenced
            }
            Path index = Paths.get(url.toURI());
            Path root = index.getParent();
            return Files.isDirectory(root) ? root : null;
        } catch (Exception e) {
            OnlineChat.LOGGER.warn("[OnlineChat] Unable to resolve bundled web resources", e);
            return null;
        }
    }

    private static void hide(Path file) {
        try {
            DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
            if (dos != null) dos.setHidden(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-Windows: the leading dot already hides the file.
        }
    }
}
