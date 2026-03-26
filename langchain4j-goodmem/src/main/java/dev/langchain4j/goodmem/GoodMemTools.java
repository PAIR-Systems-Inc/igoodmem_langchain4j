package dev.langchain4j.goodmem;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;

/**
 * GoodMem tools for LangChain4j agents.
 * <p>
 * Provides {@code @Tool}-annotated methods for creating spaces, storing memories,
 * performing semantic retrieval, and managing memories in GoodMem. Each tool method
 * returns a JSON string that the LLM can parse and act upon.
 * <p>
 * Usage:
 * <pre>{@code
 * GoodMemTools tools = GoodMemTools.builder()
 *         .baseUrl("https://localhost:8080")
 *         .apiKey("your-api-key")
 *         .build();
 *
 * // Register with an AI service
 * Assistant assistant = AiServices.builder(Assistant.class)
 *         .chatLanguageModel(model)
 *         .tools(tools)
 *         .build();
 * }</pre>
 */
public class GoodMemTools {

    private static final Gson GSON = new Gson();

    private final GoodMemClient client;

    private GoodMemTools(GoodMemClient client) {
        this.client = client;
    }

    /**
     * Create a new GoodMem space or reuse an existing one.
     * A space is a logical container for organizing related memories,
     * configured with an embedder for vector search.
     *
     * @param name              a unique name for the space
     * @param embedderId        the ID of the embedder model that converts text into vector representations
     * @param chunkingStrategy  the chunking strategy: 'recursive', 'sentence', or 'none'
     * @param chunkSize         maximum chunk size in characters (for recursive/sentence)
     * @param chunkOverlap      overlap between consecutive chunks in characters
     * @return JSON string with the operation result including spaceId
     */
    @Tool("Create a new GoodMem space or reuse an existing one. "
            + "A space is a logical container for organizing related memories, "
            + "configured with an embedder for vector search.")
    public String goodmemCreateSpace(
            @P("A unique name for the space") String name,
            @P("The ID of the embedder model that converts text into vector representations for similarity search") String embedderId,
            @P(value = "The chunking strategy for text processing: 'recursive', 'sentence', or 'none'", required = false) String chunkingStrategy,
            @P(value = "Maximum chunk size in characters (for recursive/sentence strategies)", required = false) Integer chunkSize,
            @P(value = "Overlap between consecutive chunks in characters", required = false) Integer chunkOverlap) {
        try {
            JsonObject result = client.createSpace(
                    name,
                    embedderId,
                    chunkingStrategy != null ? chunkingStrategy : "recursive",
                    chunkSize != null ? chunkSize : 512,
                    chunkOverlap != null ? chunkOverlap : 50);
            return GSON.toJson(result);
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    /**
     * Store a document as a new memory in a GoodMem space.
     * Accepts a local file path or plain text. The memory is chunked and embedded asynchronously.
     *
     * @param spaceId     the UUID of the space to store the memory in
     * @param textContent plain text content to store as memory
     * @param filePath    local file path to upload as memory (PDF, DOCX, image, etc.)
     * @return JSON string with the operation result including memoryId
     */
    @Tool("Store a document as a new memory in a GoodMem space. "
            + "Accepts a local file path or plain text. "
            + "The memory is chunked and embedded asynchronously.")
    public String goodmemCreateMemory(
            @P("The UUID of the space to store the memory in") String spaceId,
            @P(value = "Plain text content to store as memory. If both filePath and textContent are provided, the file takes priority.", required = false) String textContent,
            @P(value = "Local file path to upload as memory (PDF, DOCX, image, etc.). Content type is auto-detected.", required = false) String filePath) {
        try {
            JsonObject result = client.createMemory(spaceId, textContent, filePath, null);
            return GSON.toJson(result);
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    /**
     * Perform similarity-based semantic retrieval across one or more GoodMem spaces.
     * Returns matching chunks ranked by relevance.
     *
     * @param query                    a natural language query for semantic search
     * @param spaceIds                 one or more space UUIDs separated by commas
     * @param maxResults               maximum number of matching chunks to return
     * @param includeMemoryDefinition  fetch the full memory metadata alongside matched chunks
     * @param waitForIndexing          retry for up to 60 seconds when no results are found
     * @return JSON string with the retrieval results
     */
    @Tool("Perform similarity-based semantic retrieval across one or more GoodMem spaces. "
            + "Returns matching chunks ranked by relevance.")
    public String goodmemRetrieveMemories(
            @P("A natural language query used to find semantically similar memory chunks") String query,
            @P("One or more space UUIDs to search across, separated by commas (e.g., 'id1,id2')") String spaceIds,
            @P(value = "Maximum number of matching chunks to return", required = false) Integer maxResults,
            @P(value = "Fetch the full memory metadata alongside the matched chunks", required = false) Boolean includeMemoryDefinition,
            @P(value = "Retry for up to 60 seconds when no results are found (use when memories were just added)", required = false) Boolean waitForIndexing) {
        try {
            JsonObject result = client.retrieveMemories(
                    query,
                    spaceIds,
                    maxResults != null ? maxResults : 5,
                    includeMemoryDefinition != null ? includeMemoryDefinition : true,
                    waitForIndexing != null ? waitForIndexing : true);
            return GSON.toJson(result);
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    /**
     * Fetch a specific GoodMem memory by its ID, including metadata,
     * processing status, and optionally the original content.
     *
     * @param memoryId       the UUID of the memory to fetch
     * @param includeContent fetch the original document content in addition to metadata
     * @return JSON string with the memory data
     */
    @Tool("Fetch a specific GoodMem memory by its ID, including metadata, "
            + "processing status, and optionally the original content.")
    public String goodmemGetMemory(
            @P("The UUID of the memory to fetch") String memoryId,
            @P(value = "Fetch the original document content of the memory in addition to its metadata", required = false) Boolean includeContent) {
        try {
            JsonObject result = client.getMemory(
                    memoryId,
                    includeContent != null ? includeContent : true);
            return GSON.toJson(result);
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    /**
     * Permanently delete a GoodMem memory and its associated chunks and vector embeddings.
     *
     * @param memoryId the UUID of the memory to delete
     * @return JSON string with the deletion result
     */
    @Tool("Permanently delete a GoodMem memory and its associated chunks and vector embeddings.")
    public String goodmemDeleteMemory(
            @P("The UUID of the memory to delete") String memoryId) {
        try {
            JsonObject result = client.deleteMemory(memoryId);
            return GSON.toJson(result);
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    /**
     * List all available GoodMem embedder models.
     * Use the returned embedder ID when creating a new space.
     *
     * @return JSON string with the list of embedders
     */
    @Tool("List all available GoodMem embedder models. "
            + "Use the returned embedder ID when creating a new space.")
    public String goodmemListEmbedders() {
        try {
            List<JsonObject> embedders = client.listEmbedders();
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.add("embedders", GSON.toJsonTree(embedders));
            result.addProperty("totalEmbedders", embedders.size());
            return GSON.toJson(result);
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    /**
     * List all GoodMem spaces. Returns each space with its ID, name,
     * embedder configuration, and access settings.
     *
     * @return JSON string with the list of spaces
     */
    @Tool("List all GoodMem spaces. Returns each space with its ID, name, "
            + "embedder configuration, and access settings.")
    public String goodmemListSpaces() {
        try {
            List<JsonObject> spaces = client.listSpaces();
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.add("spaces", GSON.toJsonTree(spaces));
            result.addProperty("totalSpaces", spaces.size());
            return GSON.toJson(result);
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    private static String errorJson(Exception e) {
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", e.getMessage());
        return GSON.toJson(error);
    }

    /**
     * Creates a new {@link Builder} for constructing a {@link GoodMemTools} instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link GoodMemTools}.
     */
    public static final class Builder {

        private String baseUrl;
        private String apiKey;
        private Duration timeout = Duration.ofSeconds(30);
        private boolean verifySsl = true;

        private Builder() {
        }

        /**
         * Sets the GoodMem API base URL.
         *
         * @param baseUrl the base URL (e.g., "https://localhost:8080")
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the GoodMem API key.
         *
         * @param apiKey the API key for authentication
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the request timeout. Defaults to 30 seconds.
         *
         * @param timeout the timeout duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets whether to verify SSL certificates. Defaults to true.
         *
         * @param verifySsl true to verify SSL certificates
         * @return this builder
         */
        public Builder verifySsl(boolean verifySsl) {
            this.verifySsl = verifySsl;
            return this;
        }

        /**
         * Builds a new {@link GoodMemTools} instance.
         *
         * @return the configured tools instance
         */
        public GoodMemTools build() {
            ensureNotBlank(baseUrl, "baseUrl");
            ensureNotBlank(apiKey, "apiKey");
            GoodMemClient client = new GoodMemClient(baseUrl, apiKey, timeout, verifySsl);
            return new GoodMemTools(client);
        }
    }
}
