# langchain4j-goodmem

An integration package connecting [GoodMem](https://goodmem.ai) and [LangChain4j](https://github.com/langchain4j/langchain4j).

GoodMem is a memory layer for AI agents with support for semantic storage, retrieval, and summarization. This package exposes GoodMem operations as LangChain4j tools that can be used with any LangChain4j agent.

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-goodmem</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
```

## Tools

| Tool | Description |
|---|---|
| `goodmemCreateSpace` | Create a new space or reuse an existing one |
| `goodmemListSpaces` | List all spaces in your account |
| `goodmemCreateMemory` | Store text or files as memories |
| `goodmemRetrieveMemories` | Semantic similarity search across spaces |
| `goodmemGetMemory` | Fetch a specific memory by ID |
| `goodmemDeleteMemory` | Permanently delete a memory |
| `goodmemListEmbedders` | List available embedder models |

## Quick start

```java
import dev.langchain4j.goodmem.GoodMemTools;
import dev.langchain4j.service.AiServices;

// Create tools with your credentials
GoodMemTools goodMemTools = GoodMemTools.builder()
        .baseUrl("https://localhost:8080")
        .apiKey("your-api-key")
        .verifySsl(false)
        .build();

// Use with an AI service
interface Assistant {
    String chat(String message);
}

Assistant assistant = AiServices.builder(Assistant.class)
        .chatLanguageModel(model)
        .tools(goodMemTools)
        .build();
```

## Direct tool usage

You can also call tools directly without an agent:

```java
GoodMemTools tools = GoodMemTools.builder()
        .baseUrl("https://localhost:8080")
        .apiKey("your-api-key")
        .verifySsl(false)
        .build();

// List embedders to find an embedder ID
String embedders = tools.goodmemListEmbedders();

// Create a space
String space = tools.goodmemCreateSpace("my-space", "embedder-id", null, null, null);

// Store a text memory
String memory = tools.goodmemCreateMemory("space-id", "Important information to remember.", null);

// Store a PDF file as memory
String pdfMemory = tools.goodmemCreateMemory("space-id", null, "/path/to/document.pdf");

// Search memories
String results = tools.goodmemRetrieveMemories("search query", "space-id", 5, true, true);

// Get a specific memory
String memoryDetail = tools.goodmemGetMemory("memory-id", true);

// Delete a memory
String deleted = tools.goodmemDeleteMemory("memory-id");
```
