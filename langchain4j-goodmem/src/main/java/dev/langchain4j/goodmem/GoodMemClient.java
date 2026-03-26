package dev.langchain4j.goodmem;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

/**
 * Low-level HTTP client for communicating with the GoodMem API.
 * <p>
 * Handles authentication, URL normalization, and common request patterns
 * used by all GoodMem tools.
 */
public class GoodMemClient {

    private static final Logger log = LoggerFactory.getLogger(GoodMemClient.class);
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_CHUNK_SIZE = 512;
    private static final int DEFAULT_CHUNK_OVERLAP = 50;

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final boolean verifySsl;
    private final HttpClient httpClient;

    /**
     * Creates a new GoodMemClient.
     *
     * @param baseUrl   the base URL of the GoodMem API server
     * @param apiKey    the API key for authentication via X-API-Key header
     * @param timeout   request timeout
     * @param verifySsl whether to verify SSL certificates
     */
    public GoodMemClient(String baseUrl, String apiKey, Duration timeout, boolean verifySsl) {
        this.baseUrl = ensureNotBlank(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.apiKey = ensureNotBlank(apiKey, "apiKey");
        this.timeout = ensureNotNull(timeout, "timeout");
        this.verifySsl = verifySsl;
        this.httpClient = buildHttpClient();
    }

    public String baseUrl() {
        return baseUrl;
    }

    // -- Space operations --

    /**
     * Create a new space or return an existing one with the same name.
     * <p>
     * First lists existing spaces to check for a name match. If found,
     * returns the existing space info. Otherwise creates a new space.
     */
    public JsonObject createSpace(String name, String embedderId,
                                  String chunkingStrategy, int chunkSize, int chunkOverlap) {
        // Check for existing space with the same name
        try {
            List<JsonObject> spaces = listSpaces();
            for (JsonObject space : spaces) {
                if (space.has("name") && name.equals(space.get("name").getAsString())) {
                    String actualEmbedderId = embedderId;
                    if (space.has("spaceEmbedders")) {
                        JsonArray embedders = space.getAsJsonArray("spaceEmbedders");
                        if (!embedders.isEmpty()) {
                            JsonObject first = embedders.get(0).getAsJsonObject();
                            if (first.has("embedderId")) {
                                actualEmbedderId = first.get("embedderId").getAsString();
                            }
                        }
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("spaceId", space.get("spaceId").getAsString());
                    result.addProperty("name", space.get("name").getAsString());
                    result.addProperty("embedderId", actualEmbedderId);
                    result.addProperty("message", "Space already exists, reusing existing space");
                    result.addProperty("reused", true);
                    return result;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to list spaces for deduplication check, proceeding to create: {}", e.getMessage());
        }

        JsonObject chunkingConfig = new JsonObject();
        if ("none".equals(chunkingStrategy)) {
            chunkingConfig.add("none", new JsonObject());
        } else {
            JsonObject strategyConfig = new JsonObject();
            strategyConfig.addProperty("chunkSize", chunkSize);
            strategyConfig.addProperty("chunkOverlap", chunkOverlap);
            chunkingConfig.add(chunkingStrategy, strategyConfig);
        }

        JsonObject embedderRef = new JsonObject();
        embedderRef.addProperty("embedderId", embedderId);
        JsonArray spaceEmbedders = new JsonArray();
        spaceEmbedders.add(embedderRef);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("name", name);
        requestBody.add("spaceEmbedders", spaceEmbedders);
        requestBody.add("defaultChunkingConfig", chunkingConfig);

        JsonObject body = post("/v1/spaces", requestBody);

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("spaceId", body.get("spaceId").getAsString());
        result.addProperty("name", body.get("name").getAsString());
        result.addProperty("embedderId", embedderId);
        result.addProperty("message", "Space created successfully");
        result.addProperty("reused", false);
        return result;
    }

    /**
     * List all spaces.
     */
    public List<JsonObject> listSpaces() {
        JsonElement body = getJson("/v1/spaces");
        return parseListResponse(body, "spaces");
    }

    // -- Memory operations --

    /**
     * Create a new memory in a space from text or a file.
     * <p>
     * If both filePath and textContent are provided, the file takes priority.
     * The file content type is auto-detected from its extension.
     */
    public JsonObject createMemory(String spaceId, String textContent, String filePath,
                                   Map<String, Object> metadata) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("spaceId", spaceId);

        if (filePath != null && !filePath.isEmpty()) {
            Path path = Path.of(filePath);
            String mimeType = null;
            try {
                mimeType = Files.probeContentType(path);
            } catch (IOException e) {
                log.debug("Could not probe content type for {}: {}", filePath, e.getMessage());
            }
            if (mimeType == null) {
                // Fallback detection based on extension
                String name = path.getFileName().toString().toLowerCase();
                if (name.endsWith(".pdf")) {
                    mimeType = "application/pdf";
                } else if (name.endsWith(".docx")) {
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                } else if (name.endsWith(".txt")) {
                    mimeType = "text/plain";
                } else {
                    mimeType = "application/octet-stream";
                }
            }

            try {
                byte[] fileBytes = Files.readAllBytes(path);
                requestBody.addProperty("contentType", mimeType);

                if (mimeType.startsWith("text/")) {
                    requestBody.addProperty("originalContent", new String(fileBytes, StandardCharsets.UTF_8));
                } else {
                    requestBody.addProperty("originalContentB64",
                            Base64.getEncoder().encodeToString(fileBytes));
                }
            } catch (IOException e) {
                throw new GoodMemException("Failed to read file: " + filePath, e);
            }
        } else if (textContent != null && !textContent.isEmpty()) {
            requestBody.addProperty("contentType", "text/plain");
            requestBody.addProperty("originalContent", textContent);
        } else {
            throw new IllegalArgumentException(
                    "No content provided. Provide either textContent or filePath.");
        }

        if (metadata != null && !metadata.isEmpty()) {
            requestBody.add("metadata", GSON.toJsonTree(metadata));
        }

        JsonObject body = post("/v1/memories", requestBody);

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("memoryId", body.get("memoryId").getAsString());
        result.addProperty("spaceId", body.get("spaceId").getAsString());
        result.addProperty("status",
                body.has("processingStatus") ? body.get("processingStatus").getAsString() : "PENDING");
        result.addProperty("contentType", requestBody.get("contentType").getAsString());
        result.addProperty("message", "Memory created successfully");
        return result;
    }

    /**
     * Retrieve memories via semantic similarity search.
     * <p>
     * Supports polling for up to 10 seconds when waitForIndexing is enabled
     * and no results are found initially.
     */
    public JsonObject retrieveMemories(String query, String spaceIds, int maxResults,
                                       boolean includeMemoryDefinition, boolean waitForIndexing) {
        String[] ids = spaceIds.split(",");
        JsonArray spaceKeys = new JsonArray();
        for (String id : ids) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                JsonObject key = new JsonObject();
                key.addProperty("spaceId", trimmed);
                spaceKeys.add(key);
            }
        }
        if (spaceKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one valid Space ID is required.");
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("message", query);
        requestBody.add("spaceKeys", spaceKeys);
        requestBody.addProperty("requestedSize", maxResults);
        requestBody.addProperty("fetchMemory", includeMemoryDefinition);

        long maxWaitMs = 10_000;
        long pollIntervalMs = 2_000;
        long start = System.currentTimeMillis();
        JsonObject lastResult = null;

        while (true) {
            String responseText = postNdjson("/v1/memories:retrieve", requestBody);

            List<JsonObject> results = new ArrayList<>();
            List<JsonObject> memories = new ArrayList<>();
            String resultSetId = "";

            for (String line : responseText.split("\n")) {
                String jsonStr = line.trim();
                if (jsonStr.isEmpty()) continue;
                if (jsonStr.startsWith("data:")) {
                    jsonStr = jsonStr.substring(5).trim();
                }
                if (jsonStr.startsWith("event:") || jsonStr.isEmpty()) continue;

                try {
                    JsonObject item = GSON.fromJson(jsonStr, JsonObject.class);

                    if (item.has("resultSetBoundary")) {
                        JsonObject boundary = item.getAsJsonObject("resultSetBoundary");
                        if (boundary.has("resultSetId")) {
                            resultSetId = boundary.get("resultSetId").getAsString();
                        }
                    } else if (item.has("memoryDefinition")) {
                        memories.add(item.getAsJsonObject("memoryDefinition"));
                    } else if (item.has("retrievedItem")) {
                        JsonObject ri = item.getAsJsonObject("retrievedItem");
                        JsonObject chunkData = ri.has("chunk") ? ri.getAsJsonObject("chunk") : new JsonObject();
                        JsonObject chunk = chunkData.has("chunk") ? chunkData.getAsJsonObject("chunk") : new JsonObject();

                        JsonObject parsed = new JsonObject();
                        parsed.addProperty("chunkId",
                                chunk.has("chunkId") ? chunk.get("chunkId").getAsString() : "");
                        parsed.addProperty("chunkText",
                                chunk.has("chunkText") ? chunk.get("chunkText").getAsString() : "");
                        parsed.addProperty("memoryId",
                                chunk.has("memoryId") ? chunk.get("memoryId").getAsString() : "");
                        if (chunkData.has("relevanceScore")) {
                            parsed.addProperty("relevanceScore", chunkData.get("relevanceScore").getAsDouble());
                        }
                        if (chunkData.has("memoryIndex")) {
                            parsed.addProperty("memoryIndex", chunkData.get("memoryIndex").getAsInt());
                        }
                        results.add(parsed);
                    }
                } catch (Exception e) {
                    log.debug("Skipping unparseable NDJSON line: {}", jsonStr);
                }
            }

            lastResult = new JsonObject();
            lastResult.addProperty("success", true);
            lastResult.addProperty("resultSetId", resultSetId);
            lastResult.add("results", GSON.toJsonTree(results));
            lastResult.add("memories", GSON.toJsonTree(memories));
            lastResult.addProperty("totalResults", results.size());
            lastResult.addProperty("query", query);

            if (!results.isEmpty() || !waitForIndexing) {
                return lastResult;
            }

            long elapsed = System.currentTimeMillis() - start;
            if (elapsed >= maxWaitMs) {
                lastResult.addProperty("message",
                        "No results found after waiting 10 seconds for indexing. " +
                        "Memories may still be processing.");
                return lastResult;
            }

            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return lastResult;
            }
        }
    }

    /**
     * Fetch a specific memory by ID.
     */
    public JsonObject getMemory(String memoryId, boolean includeContent) {
        JsonElement memoryBody = getJson("/v1/memories/" + memoryId);

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.add("memory", memoryBody);

        if (includeContent) {
            try {
                JsonElement contentBody = getJson("/v1/memories/" + memoryId + "/content");
                result.add("content", contentBody);
            } catch (Exception e) {
                result.addProperty("contentError", "Failed to fetch content: " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * Delete a memory by ID.
     */
    public JsonObject deleteMemory(String memoryId) {
        delete("/v1/memories/" + memoryId);

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("memoryId", memoryId);
        result.addProperty("message", "Memory deleted successfully");
        return result;
    }

    /**
     * List all available embedder models.
     */
    public List<JsonObject> listEmbedders() {
        JsonElement body = getJson("/v1/embedders");
        return parseListResponse(body, "embedders");
    }

    // -- HTTP helpers --

    private HttpClient buildHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(timeout);
        if (!verifySsl) {
            try {
                javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                sslContext.init(null, new javax.net.ssl.TrustManager[]{
                        new javax.net.ssl.X509TrustManager() {
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return new java.security.cert.X509Certificate[0];
                            }
                            public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                        }
                }, new java.security.SecureRandom());
                builder.sslContext(sslContext);
            } catch (Exception e) {
                throw new GoodMemException("Failed to configure SSL context for insecure connections", e);
            }
        }
        return builder.build();
    }

    private JsonElement getJson(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(timeout)
                .GET()
                .build();

        HttpResponse<String> response = execute(request);
        return GSON.fromJson(response.body(), JsonElement.class);
    }

    private JsonObject post(String path, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        HttpResponse<String> response = execute(request);
        return GSON.fromJson(response.body(), JsonObject.class);
    }

    private String postNdjson(String path, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/x-ndjson")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        HttpResponse<String> response = execute(request);
        return response.body();
    }

    private void delete(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(timeout)
                .DELETE()
                .build();

        execute(request);
    }

    private HttpResponse<String> execute(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new GoodMemException(String.format(
                        "GoodMem API error (HTTP %d) for %s %s: %s",
                        response.statusCode(),
                        request.method(),
                        request.uri(),
                        response.body()));
            }

            return response;
        } catch (GoodMemException e) {
            throw e;
        } catch (IOException e) {
            throw new GoodMemException(String.format(
                    "Failed to connect to GoodMem API at %s: %s",
                    request.uri(), e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoodMemException("Request to GoodMem API was interrupted", e);
        }
    }

    private List<JsonObject> parseListResponse(JsonElement body, String arrayKey) {
        List<JsonObject> result = new ArrayList<>();
        JsonArray array;

        if (body.isJsonArray()) {
            array = body.getAsJsonArray();
        } else if (body.isJsonObject() && body.getAsJsonObject().has(arrayKey)) {
            array = body.getAsJsonObject().getAsJsonArray(arrayKey);
        } else {
            return result;
        }

        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                result.add(element.getAsJsonObject());
            }
        }
        return result;
    }
}
