package com.example.tools;

import com.example.model.ChunkData;
import com.example.service.ChunkingService;
import com.example.service.EmbeddingService;
import com.example.service.PineconeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RagMcpTools {

    private static final Logger log = LoggerFactory.getLogger(RagMcpTools.class);

    private final EmbeddingService embeddingService;
    private final PineconeService pineconeService;
    private final ChunkingService chunkingService;
    private final JdbcTemplate jdbcTemplate;

    public RagMcpTools(EmbeddingService embeddingService, PineconeService pineconeService,
                       ChunkingService chunkingService, JdbcTemplate jdbcTemplate) {
        this.embeddingService = embeddingService;
        this.pineconeService = pineconeService;
        this.chunkingService = chunkingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "List all documents that have been ingested into the knowledge base. " +
            "Returns document IDs, chunk counts, and ingestion timestamps. " +
            "Use this when the user wants to know what documents are stored.")
    public String listDocuments() {
        log.info("MCP tool called: listDocuments()");
        try {
            List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, chunk_count, ingested_at FROM knowledge_documents ORDER BY ingested_at DESC"
            );

            if (rows.isEmpty()) {
                return "No documents have been ingested into the knowledge base yet.";
            }

            StringBuilder sb = new StringBuilder("Documents in the knowledge base:\n\n");
            sb.append("| Document ID | Chunks | Ingested At |\n");
            sb.append("|---|---|---|\n");
            for (java.util.Map<String, Object> row : rows) {
                sb.append("| ").append(row.get("id"))
                  .append(" | ").append(row.get("chunk_count"))
                  .append(" | ").append(row.get("ingested_at"))
                  .append(" |\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("listDocuments error: {}", e.getMessage());
            return "Error listing documents: " + e.getMessage();
        }
    }

    @Tool(description = "Delete a document from the knowledge base by its document ID. " +
            "Removes all chunks from Pinecone and the metadata record from the database. " +
            "Use this when the user wants to remove a document or replace it with a corrected version.")
    public String deleteDocument(String documentId) {
        log.info("MCP tool called: deleteDocument(documentId={})", documentId);
        try {
            List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT chunk_count FROM knowledge_documents WHERE id = ?", documentId
            );

            if (rows.isEmpty()) {
                return "Document '" + documentId + "' not found in the knowledge base.";
            }

            int chunkCount = ((Number) rows.get(0).get("chunk_count")).intValue();

            List<String> chunkIds = new ArrayList<>();
            for (int i = 0; i < chunkCount; i++) {
                chunkIds.add(documentId + "-chunk-" + i);
            }

            pineconeService.deleteByIds(chunkIds);

            jdbcTemplate.update("DELETE FROM knowledge_documents WHERE id = ?", documentId);

            log.info("Deleted document '{}' ({} chunks)", documentId, chunkCount);
            return String.format("Document '%s' deleted successfully (%d chunks removed from knowledge base).",
                    documentId, chunkCount);
        } catch (Exception e) {
            log.error("deleteDocument error for '{}': {}", documentId, e.getMessage());
            return "Error deleting document '" + documentId + "': " + e.getMessage();
        }
    }

    @Tool(description = "Search the knowledge base for documents relevant to a question. " +
            "Returns the most semantically similar text passages found. " +
            "Use this when the user asks about topics that may be in stored documents.")
    public String askDocuments(String question) {
        log.info("MCP tool called: askDocuments({})", question);
        try {
            List<Float> queryVector = embeddingService.embed(question);
            List<String> results = pineconeService.search(queryVector, 5);

            if (results.isEmpty()) {
                return "No relevant documents found for: " + question;
            }

            StringBuilder sb = new StringBuilder("Relevant passages from the knowledge base:\n\n");
            for (int i = 0; i < results.size(); i++) {
                sb.append("[").append(i + 1).append("] ").append(results.get(i)).append("\n\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("askDocuments error: {}", e.getMessage());
            return "Error searching knowledge base: " + e.getMessage();
        }
    }

    @Tool(description = "Ingest a document into the knowledge base. " +
            "Chunks the text, generates embeddings, stores them in Pinecone, " +
            "and records the document metadata. " +
            "documentId must be unique (e.g. 'company-policy-v2', 'product-faq'). " +
            "Returns a confirmation with the number of chunks created.")
    public String ingestDocument(String documentId, String text) {
        log.info("MCP tool called: ingestDocument(documentId={})", documentId);
        try {
            List<String> chunks = chunkingService.chunk(text);

            List<String> chunkTexts = chunks;
            List<List<Float>> vectors = embeddingService.batchEmbed(chunkTexts);

            List<ChunkData> chunkData = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                chunkData.add(new ChunkData(
                        documentId + "-chunk-" + i,
                        vectors.get(i),
                        chunks.get(i),
                        documentId,
                        i,
                        chunks.size()
                ));
            }

            pineconeService.batchUpsert(chunkData);

            // Record metadata in PostgreSQL so MCP Resources can list documents without scanning Pinecone
            jdbcTemplate.update(
                    "INSERT INTO knowledge_documents (id, chunk_count) VALUES (?, ?) " +
                    "ON CONFLICT (id) DO UPDATE SET chunk_count = EXCLUDED.chunk_count, ingested_at = NOW()",
                    documentId, chunks.size()
            );

            log.info("Ingested document '{}' as {} chunks", documentId, chunks.size());
            return String.format("Document '%s' ingested successfully: %d chunks created and stored in knowledge base.",
                    documentId, chunks.size());
        } catch (Exception e) {
            log.error("ingestDocument error for '{}': {}", documentId, e.getMessage());
            return "Error ingesting document '" + documentId + "': " + e.getMessage();
        }
    }
}
