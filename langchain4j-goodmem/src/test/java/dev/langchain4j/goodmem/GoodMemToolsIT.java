package dev.langchain4j.goodmem;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for GoodMem LangChain4j tools.
 * <p>
 * These tests run against a live GoodMem API instance. Set the following
 * environment variables before running:
 * <ul>
 *   <li>GOODMEM_BASE_URL - e.g. https://localhost:8080</li>
 *   <li>GOODMEM_API_KEY - your GoodMem API key</li>
 *   <li>GOODMEM_TEST_PDF - path to a PDF file for upload testing</li>
 *   <li>GOODMEM_EMBEDDER_ID - (optional) specific embedder ID to use</li>
 * </ul>
 * <p>
 * Run with:
 * <pre>
 * GOODMEM_BASE_URL=https://localhost:8080 \
 * GOODMEM_API_KEY=your-key \
 * GOODMEM_TEST_PDF=/path/to/file.pdf \
 * mvn test -pl langchain4j-goodmem -Dtest=GoodMemToolsIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "GOODMEM_API_KEY", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GoodMemToolsIT {

    private static final Gson GSON = new Gson();

    private static GoodMemTools tools;
    private static String embedderId;
    private static String spaceId;
    private static String textMemoryId;
    private static String pdfMemoryId;

    @BeforeAll
    static void setUp() {
        String baseUrl = System.getenv("GOODMEM_BASE_URL");
        String apiKey = System.getenv("GOODMEM_API_KEY");

        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://localhost:8080";
        }

        tools = GoodMemTools.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .verifySsl(false)
                .build();

        // Resolve embedder ID: use env var if set, otherwise pick from list
        String envEmbedder = System.getenv("GOODMEM_EMBEDDER_ID");
        if (envEmbedder != null && !envEmbedder.isEmpty()) {
            embedderId = envEmbedder;
        } else {
            String raw = tools.goodmemListEmbedders();
            JsonObject result = GSON.fromJson(raw, JsonObject.class);
            JsonArray embedders = result.getAsJsonArray("embedders");
            assertThat(embedders).isNotEmpty();

            // Try to find a non-OpenAI embedder first (Voyage, etc.) since
            // OpenAI embedders may have transient issues on some deployments
            for (int i = 0; i < embedders.size(); i++) {
                JsonObject emb = embedders.get(i).getAsJsonObject();
                String provider = emb.has("providerType")
                        ? emb.get("providerType").getAsString() : "";
                if (!"OPENAI".equals(provider)) {
                    embedderId = emb.get("embedderId").getAsString();
                    System.out.println("Selected embedder: "
                            + emb.get("displayName").getAsString()
                            + " (" + embedderId + ")");
                    break;
                }
            }
            // Fallback to first embedder if no non-OpenAI embedder found
            if (embedderId == null) {
                embedderId = embedders.get(0).getAsJsonObject()
                        .get("embedderId").getAsString();
            }
        }
    }

    @Test
    @Order(1)
    void listEmbedders() {
        String raw = tools.goodmemListEmbedders();
        JsonObject result = GSON.fromJson(raw, JsonObject.class);

        assertThat(result.get("success").getAsBoolean()).isTrue();
        assertThat(result.get("totalEmbedders").getAsInt()).isGreaterThan(0);

        JsonArray embedders = result.getAsJsonArray("embedders");
        assertThat(embedders).isNotEmpty();
    }

    @Test
    @Order(2)
    void listSpaces() {
        String raw = tools.goodmemListSpaces();
        JsonObject result = GSON.fromJson(raw, JsonObject.class);

        assertThat(result.get("success").getAsBoolean()).isTrue();
        assertThat(result.has("spaces")).isTrue();
    }

    @Test
    @Order(3)
    void createSpace() {
        String spaceName = "langchain4j-test-" + System.currentTimeMillis();
        String raw = tools.goodmemCreateSpace(spaceName, embedderId, null, null, null);
        JsonObject result = GSON.fromJson(raw, JsonObject.class);

        assertThat(result.get("success").getAsBoolean()).isTrue();
        assertThat(result.get("spaceId").getAsString()).isNotEmpty();
        assertThat(result.get("name").getAsString()).isEqualTo(spaceName);

        spaceId = result.get("spaceId").getAsString();
    }

    @Test
    @Order(4)
    void createSpaceReusesExisting() {
        if (spaceId == null) {
            createSpace();
        }

        // Find the space name by listing spaces
        String spacesRaw = tools.goodmemListSpaces();
        JsonObject spacesResult = GSON.fromJson(spacesRaw, JsonObject.class);
        JsonArray spaces = spacesResult.getAsJsonArray("spaces");

        String spaceName = null;
        for (int i = 0; i < spaces.size(); i++) {
            JsonObject space = spaces.get(i).getAsJsonObject();
            if (space.get("spaceId").getAsString().equals(spaceId)) {
                spaceName = space.get("name").getAsString();
                break;
            }
        }

        assertThat(spaceName).isNotNull();

        // Create again with same name - should reuse
        String raw2 = tools.goodmemCreateSpace(spaceName, embedderId, null, null, null);
        JsonObject result2 = GSON.fromJson(raw2, JsonObject.class);

        assertThat(result2.get("success").getAsBoolean()).isTrue();
        assertThat(result2.get("reused").getAsBoolean()).isTrue();
        assertThat(result2.get("spaceId").getAsString()).isEqualTo(spaceId);
    }

    @Test
    @Order(5)
    void createTextMemory() {
        if (spaceId == null) {
            createSpace();
        }

        String raw = tools.goodmemCreateMemory(
                spaceId,
                "LangChain4j is a Java framework for developing applications "
                        + "powered by large language models. It provides tools, chains, "
                        + "and agents for building AI-powered applications.",
                null);
        JsonObject result = GSON.fromJson(raw, JsonObject.class);

        assertThat(result.get("success").getAsBoolean()).isTrue();
        assertThat(result.get("memoryId").getAsString()).isNotEmpty();
        assertThat(result.get("contentType").getAsString()).isEqualTo("text/plain");

        textMemoryId = result.get("memoryId").getAsString();
    }

    @Test
    @Order(6)
    void createPdfMemory() {
        if (spaceId == null) {
            createSpace();
        }

        String testPdf = System.getenv("GOODMEM_TEST_PDF");
        if (testPdf == null || testPdf.isEmpty()) {
            System.out.println("GOODMEM_TEST_PDF not set, skipping PDF test");
            return;
        }

        String raw = tools.goodmemCreateMemory(spaceId, null, testPdf);
        JsonObject result = GSON.fromJson(raw, JsonObject.class);

        assertThat(result.get("success").getAsBoolean()).isTrue();
        assertThat(result.get("memoryId").getAsString()).isNotEmpty();
        assertThat(result.get("contentType").getAsString()).isEqualTo("application/pdf");

        pdfMemoryId = result.get("memoryId").getAsString();
    }

    @Test
    @Order(7)
    void getMemory() {
        if (textMemoryId == null) {
            createTextMemory();
        }

        String raw = tools.goodmemGetMemory(textMemoryId, true);
        JsonObject result = GSON.fromJson(raw, JsonObject.class);

        assertThat(result.get("success").getAsBoolean()).isTrue();
        assertThat(result.has("memory")).isTrue();
        assertThat(result.getAsJsonObject("memory")
                .get("memoryId").getAsString()).isEqualTo(textMemoryId);
    }

    @Test
    @Order(8)
    void retrieveMemories() {
        if (spaceId == null || textMemoryId == null) {
            createTextMemory();
        }

        String raw = tools.goodmemRetrieveMemories(
                "LangChain4j framework",
                spaceId,
                5,
                true,
                true);
        JsonObject result = GSON.fromJson(raw, JsonObject.class);

        assertThat(result.get("success").getAsBoolean()).isTrue();
        assertThat(result.get("totalResults").getAsInt()).isGreaterThan(0);

        // Verify chunk structure
        JsonArray results = result.getAsJsonArray("results");
        assertThat(results).isNotEmpty();
        JsonObject firstChunk = results.get(0).getAsJsonObject();
        assertThat(firstChunk.has("chunkText")).isTrue();
        assertThat(firstChunk.has("relevanceScore")).isTrue();
    }

    @Test
    @Order(9)
    void deleteMemory() {
        if (textMemoryId == null) {
            createTextMemory();
        }

        String raw = tools.goodmemDeleteMemory(textMemoryId);
        JsonObject result = GSON.fromJson(raw, JsonObject.class);

        assertThat(result.get("success").getAsBoolean()).isTrue();
        assertThat(result.get("memoryId").getAsString()).isEqualTo(textMemoryId);
    }

    @Test
    @Order(10)
    void cleanupPdfMemory() {
        if (pdfMemoryId != null) {
            String raw = tools.goodmemDeleteMemory(pdfMemoryId);
            JsonObject result = GSON.fromJson(raw, JsonObject.class);
            assertThat(result.get("success").getAsBoolean()).isTrue();
        }
    }
}
