package edu.ember.server.storage;

import edu.ember.common.util.MiniJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link BlobStore} backed by the rootz.so REST API (https://rootz.so/docs), using only the JDK
 * HTTP client. @concept Adapter pattern, HTTP, exception translation
 */
public final class RootzBlobStore implements BlobStore {
    private static final String API = "https://rootz.so/api/files/";
    private static final int PAGE = 200;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();
    private final String apiKey;
    private final String folderId;
    /** Signed download links that the list call already returns (valid 24 h), keyed by file ID. */
    private final Map<String, String> signedUrls = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long listedAtMs;

    /** folderId may be null to use the account's root folder. */
    public RootzBlobStore(String apiKey, String folderId) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ROOTZ_API_KEY is not set");
        }
        this.apiKey = apiKey.strip();
        this.folderId = folderId == null || folderId.isBlank() ? null : folderId.strip();
    }

    /** ID of the folder named {@code name} directly inside {@code parentId}, or null. */
    public static String findSubfolder(String apiKey, String parentId, String name) throws IOException {
        RootzBlobStore probe = new RootzBlobStore(apiKey, parentId);
        Object folders = MiniJson.path(json(probe.send(probe.authorized(API
                + "list?includeFolders=true&limit=1&folderId=" + encode(parentId)).GET())), "folders");
        if (folders instanceof List<?> list) {
            for (Object folder : list) {
                if (name.equals(MiniJson.path(folder, "name"))
                        && MiniJson.path(folder, "id") instanceof String id) {
                    return id;
                }
            }
        }
        return null;
    }

    @Override
    public Map<String, String> list(String prefix) throws IOException {
        Map<String, String> files = new HashMap<>();
        for (int page = 1; ; page++) {
            String query = "list?includeFolders=false&limit=" + PAGE + "&page=" + page
                    + (folderId == null ? "" : "&folderId=" + encode(folderId));
            Object data = MiniJson.path(json(send(authorized(API + query).GET())), "data");
            if (!(data instanceof List<?> items)) {
                throw new IOException("rootz list returned no file list");
            }
            for (Object item : items) {
                Object name = MiniJson.path(item, "name");
                Object id = MiniJson.path(item, "id");
                if (name instanceof String text && id instanceof String fileId
                        && text.startsWith(prefix)) {
                    files.put(text, fileId);
                    if (MiniJson.path(item, "url") instanceof String url) {
                        signedUrls.put(fileId, url);
                    }
                }
            }
            if (items.size() < PAGE) {
                listedAtMs = System.currentTimeMillis();
                return files;
            }
        }
    }

    @Override
    public String put(String name, byte[] data) throws IOException {
        String boundary = "ember" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream(data.length + 512);
        if (folderId != null) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"folderId\""
                    + "\r\n\r\n" + folderId + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
                + "filename=\"" + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(data);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = authorized(API + "upload")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        Object id = MiniJson.path(json(send(request)), "data", "id");
        if (!(id instanceof String fileId)) {
            throw new IOException("rootz upload returned no file id");
        }
        return fileId;
    }

    @Override
    public byte[] get(String id) throws IOException {
        String cached = signedUrls.remove(id);
        if (cached != null && System.currentTimeMillis() - listedAtMs < 20 * 3_600_000L) {
            try {
                // One request instead of two, and it does not count as a rootz download.
                return send(HttpRequest.newBuilder(URI.create(cached)).timeout(Duration.ofMinutes(5))
                        .header("User-Agent", "ember-server").GET()).body();
            } catch (IOException expired) {
                // Fall through to a fresh link from the API.
            }
        }
        HttpResponse<byte[]> response = send(authorized(API + "download/" + encode(id)).GET());
        String type = response.headers().firstValue("Content-Type").orElse("");
        if (!type.contains("json")) {
            return response.body(); // the API redirected straight to the file
        }
        Object url = MiniJson.path(json(response), "data", "url");
        if (!(url instanceof String signed)) {
            throw new IOException("rootz download returned no URL");
        }
        // The signed URL is on a storage host: never send it our API key.
        return send(HttpRequest.newBuilder(URI.create(signed)).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "ember-server").GET()).body();
    }

    @Override
    public void delete(String id) throws IOException {
        signedUrls.remove(id);
        send(authorized(API + "delete?fileId=" + encode(id)).DELETE());
    }

    private HttpRequest.Builder authorized(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", "ember-server");
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) throws IOException {
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("rootz request interrupted", exception);
        }
        if (response.statusCode() / 100 != 2) {
            String detail = "";
            try {
                Object error = MiniJson.path(json(response), "error");
                detail = error instanceof String text ? ": " + text : "";
            } catch (IOException | IllegalArgumentException ignored) {
                // Non-JSON error page; the status code is enough.
            }
            throw new IOException("rootz returned HTTP " + response.statusCode() + detail);
        }
        return response;
    }

    private static Object json(HttpResponse<byte[]> response) throws IOException {
        try {
            return MiniJson.parse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new IOException("rootz returned invalid JSON", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
