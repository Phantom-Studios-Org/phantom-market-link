package com.phantom.marketlink.link;

import com.phantom.marketlink.PhantomMarketLink;
import com.phantom.marketlink.config.LinkConfig;
import com.phantom.marketlink.gui.LinkMessages;
import com.phantom.marketlink.gui.LitematicaBridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

// Downloads a schematic and saves it to Litematica's schematics dir, then tries to
// load it as a placement. Slug/hash are re-validated here and the filename comes from
// the slug only — a response header can contribute at most a whitelisted extension.
public final class SchematicReceiver {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{1,100}$");
    private static final Pattern FILE_HASH = Pattern.compile("^[a-f0-9]{32,128}$");
    private static final Set<String> EXTENSIONS = Set.of("litematic", "schem", "mcstructure", "nbt");
    private static final String DEFAULT_EXT = "litematic";
    private static final int MAX_COLLISION_SUFFIX = 1000;
    private static final long MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024;

    private final LinkConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public SchematicReceiver(LinkConfig config) {
        this.config = config;
    }

    public Result receive(String slug, String fileHash, String format) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            return Result.failed("invalid_slug");
        }
        if (fileHash == null || !FILE_HASH.matcher(fileHash).matches()) {
            return Result.failed("invalid_file_hash");
        }
        if (format != null && !EXTENSIONS.contains(format.toLowerCase(Locale.ROOT))) {
            return Result.failed("invalid_format");
        }

        try {
            String url = config.baseUrl() + "/api/posts/" + slug + "/files/" + fileHash + "/download"
                    + (format != null ? "?format=" + format : "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + config.sessionToken())
                    .header("Accept", "application/octet-stream")
                    .timeout(Duration.ofMinutes(2))
                    .GET()
                    .build();

            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                drain(resp.body());
                return Result.failed("http_" + resp.statusCode());
            }
            if (resp.headers().firstValueAsLong("Content-Length").orElse(0L) > MAX_DOWNLOAD_BYTES) {
                drain(resp.body());
                return Result.failed("too_large");
            }

            String ext = resolveExtension(format, resp);
            File dir = LitematicaBridge.getSchematicsDirectory();
            if (dir == null) {
                drain(resp.body());
                return Result.failed("no_schematics_dir");
            }
            Files.createDirectories(dir.toPath());

            File target = resolveTarget(dir, slug, ext);
            Path tmp = Files.createTempFile(dir.toPath(), slug + "-", ".part");
            try (InputStream in = resp.body()) {
                copyCapped(in, tmp);
            } catch (TooLargeException e) {
                Files.deleteIfExists(tmp);
                return Result.failed("too_large");
            }
            Files.move(tmp, target.toPath(), StandardCopyOption.ATOMIC_MOVE);

            PhantomMarketLink.LOGGER.info("Received schematic '{}' -> {}", slug, target.getName());

            // A failed auto-load must not fail the ack — the file saved fine.
            LitematicaBridge.LoadResult load = LitematicaBridge.LoadResult.SKIPPED;
            if ("litematic".equals(ext)) {
                load = LitematicaBridge.tryLoadPlacement(target);
            }
            if (load == LitematicaBridge.LoadResult.FAILED) {
                LinkMessages.info("phantom_market_link.toast.received_load_failed", slug);
            } else {
                LinkMessages.info("phantom_market_link.toast.received", slug);
            }
            return Result.ok();
        } catch (Exception e) {
            PhantomMarketLink.LOGGER.error("SchematicReceiver.receive failed", e);
            return Result.failed("save_error");
        }
    }

    private String resolveExtension(String format, HttpResponse<InputStream> resp) {
        if (format != null) {
            String f = format.toLowerCase(Locale.ROOT);
            if (EXTENSIONS.contains(f)) {
                return f;
            }
        }
        // Header can supply an extension but never the filename itself.
        String cd = resp.headers().firstValue("Content-Disposition").orElse(null);
        if (cd != null) {
            int dot = cd.lastIndexOf('.');
            if (dot >= 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = dot + 1; i < cd.length(); i++) {
                    char c = Character.toLowerCase(cd.charAt(i));
                    if (c >= 'a' && c <= 'z') {
                        sb.append(c);
                    } else {
                        break;
                    }
                }
                String candidate = sb.toString();
                if (EXTENSIONS.contains(candidate)) {
                    return candidate;
                }
            }
        }
        return DEFAULT_EXT;
    }

    private File resolveTarget(File dir, String slug, String ext) {
        File candidate = new File(dir, slug + "." + ext);
        if (!candidate.exists()) {
            return candidate;
        }
        for (int i = 2; i < MAX_COLLISION_SUFFIX; i++) {
            File c = new File(dir, slug + "-" + i + "." + ext);
            if (!c.exists()) {
                return c;
            }
        }
        return new File(dir, slug + "-" + MAX_COLLISION_SUFFIX + "." + ext);
    }

    // Stream to disk, aborting past MAX_DOWNLOAD_BYTES so a runaway response can't fill it.
    private static void copyCapped(InputStream in, Path tmp) throws IOException, TooLargeException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        try (var out = Files.newOutputStream(tmp)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw new TooLargeException();
                }
                out.write(buf, 0, n);
            }
        }
    }

    private static final class TooLargeException extends Exception {
    }

    private static void drain(InputStream in) {
        try (InputStream ignored = in) {
        } catch (Exception ignored) {
        }
    }

    public record Result(boolean delivered, String error) {
        static Result ok() {
            return new Result(true, null);
        }

        static Result failed(String error) {
            return new Result(false, error);
        }
    }
}
