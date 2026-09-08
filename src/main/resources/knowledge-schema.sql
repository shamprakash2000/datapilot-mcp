-- Tracks documents ingested into Pinecone via the ingest_document MCP tool.
-- Used by MCP Resources (document://list, document://{id}) to expose doc metadata
-- to the LLM host without requiring a Pinecone metadata scan.
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id          VARCHAR(255) PRIMARY KEY,
    chunk_count INT          NOT NULL,
    ingested_at TIMESTAMP    DEFAULT NOW()
);
