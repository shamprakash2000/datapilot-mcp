# datapilot-mcp — MCP Knowledge Server

Standalone Spring Boot app that exposes RAG (document search) and database query capabilities over the **Model Context Protocol (MCP)**. LLM-agnostic — any MCP host (DataPilot, Claude Desktop, Cursor, etc.) can connect and use the tools without changing a line of server code.

Part of the [DataPilot](https://github.com/shamprakash2000/datapilot) learning project (Phase 6).

---

## What It Does

Exposes 5 tools and 2 resources over MCP (SSE transport):

### Tools (LLM calls these)

| Tool | Description |
|---|---|
| `ask_documents` | Embeds the question, searches Pinecone by vector similarity, returns matching text passages |
| `ingest_document` | Chunks text, embeds via Gemini API, stores vectors in Pinecone, records metadata in PostgreSQL |
| `list_tables` | Returns the list of database tables the agent is allowed to query |
| `get_table_schema` | Returns column names, types, and 3 sample rows for a table |
| `execute_query` | Executes a SELECT query and returns results as a markdown table |

### Resources (LLM reads these)

| Resource URI | Description |
|---|---|
| `document://list` | Lists all documents ingested into the knowledge base with chunk counts and timestamps |
| `document://{id}` | Returns metadata for a specific document by its ID |

Safety enforced server-side (DB tools):
- Only `SELECT` allowed — `INSERT`, `UPDATE`, `DELETE`, `DROP` all rejected
- Only `da_products` and `da_orders` accessible — system tables blocked
- Results capped at 20 rows automatically

---

## Architecture

```
MCP Host (DataPilot / Claude Desktop / any MCP client)
    │
    │  MCP protocol — JSON-RPC 2.0 over HTTP/SSE
    ▼
datapilot-mcp (port 8082)
    │                    │
    ▼                    ▼
Pinecone            Neon PostgreSQL
(vectors + chunks)  (da_products, da_orders, knowledge_documents)
    │
    ▼
Gemini Embedding API
(gemini-embedding-001, 768 dimensions)
```

**No chat model inside this server.** The LLM lives in the host (Claude Desktop, DataPilot). This server only stores, retrieves, and queries — the host LLM reads the results and generates answers.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 17, Spring Boot 3.4.1 |
| MCP | Spring AI 1.1.8 (`spring-ai-starter-mcp-server-webmvc`) |
| Embedding | Gemini API (`gemini-embedding-001`, 768-dim via raw HTTP) |
| Vector store | Pinecone (raw HTTP) |
| Database | PostgreSQL on Neon (via `JdbcTemplate`) |
| Deployment | Render (Docker) |

---

## Local Setup

### Prerequisites
- Java 17+
- Maven 3.9+
- Neon PostgreSQL credentials (same DB as DataPilot — `da_products` and `da_orders` seeded by DataPilot)
- Pinecone index (`gemini-chat`, 768 dimensions — this is the actual index name in Pinecone)
- Gemini API key

### Environment Variables

```bash
GEMINI_API_KEY=your_gemini_api_key
PINECONE_API_KEY=your_pinecone_api_key
PINECONE_HOST=https://your-index-host.pinecone.io
NEON_HOST=your_neon_host
NEON_DB=your_db_name
NEON_USER=your_db_user
NEON_PASSWORD=your_db_password
```

### Run

```bash
mvn spring-boot:run
```

Server starts at `http://localhost:8082`

---

## MCP Endpoints

| Endpoint | Description |
|---|---|
| `GET /sse` | SSE stream — MCP clients connect here |
| `POST /mcp/message?sessionId=xxx` | Send JSON-RPC tool call messages |
| `GET /actuator/health` | Health check — returns `{"status":"UP"}` |

### Testing with MCP Inspector

```bash
npx @modelcontextprotocol/inspector --transport sse http://localhost:8082/sse
```

Browse tools, call `ingest_document` to add documents, then `ask_documents` to search them. Check the **Resources** tab for `document://list` and the **Resource Templates** tab for `document://{id}`.

---

## Render Deployment

### Environment Variables (set in Render dashboard)

```
GEMINI_API_KEY=...
PINECONE_API_KEY=...
PINECONE_HOST=...
NEON_HOST=...
NEON_DB=...
NEON_USER=...
NEON_PASSWORD=...
```

`PORT` is set automatically by Render — the app reads it via `${PORT:8082}`.
