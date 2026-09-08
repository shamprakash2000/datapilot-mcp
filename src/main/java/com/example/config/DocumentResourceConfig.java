package com.example.config;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * MCP Resources — a different concept from MCP Tools.
 *
 * Tools:     LLM *calls* these to DO something (function call pattern).
 *            Example: ask_documents("what is our refund policy?")
 *
 * Resources: LLM *reads* these like files (URI-addressed, read-only).
 *            Example: reading document://list to see what docs are available
 *            before deciding whether to call ask_documents at all.
 *
 * MCP has two resource types:
 *   SyncResourceSpecification         — exact URI match (resources/list)
 *   SyncResourceTemplateSpecification — URI template with {params} (resources/templates/list)
 *
 * This class exposes:
 *   document://list     (exact)    — catalog of all ingested documents
 *   document://{id}     (template) — metadata for one specific document
 *
 * Keeping them as different types prevents the {id} template from swallowing
 * the exact document://list URI during routing.
 */
@Configuration
public class DocumentResourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DocumentResourceConfig.class);

    // Exact resource — registered under resources/list
    @Bean
    public List<McpServerFeatures.SyncResourceSpecification> documentListResource(JdbcTemplate jdbc) {
        McpSchema.Resource listResource = McpSchema.Resource.builder()
                .uri("document://list")
                .name("Knowledge Base Document List")
                .description("Lists all documents currently ingested into the knowledge base, " +
                        "with their IDs, chunk counts, and ingestion timestamps.")
                .mimeType("text/plain")
                .build();

        McpServerFeatures.SyncResourceSpecification listSpec =
                new McpServerFeatures.SyncResourceSpecification(listResource, (exchange, request) -> {
                    log.info("MCP resource read: document://list");
                    try {
                        List<Map<String, Object>> docs = jdbc.queryForList(
                                "SELECT id, chunk_count, ingested_at FROM knowledge_documents ORDER BY ingested_at DESC"
                        );

                        String content;
                        if (docs.isEmpty()) {
                            content = "No documents ingested yet. Use the ingest_document tool to add documents.";
                        } else {
                            StringBuilder sb = new StringBuilder("Ingested documents:\n\n");
                            for (Map<String, Object> doc : docs) {
                                sb.append("- ").append(doc.get("id"))
                                  .append(" (").append(doc.get("chunk_count")).append(" chunks, ingested ")
                                  .append(doc.get("ingested_at")).append(")\n");
                            }
                            content = sb.toString().trim();
                        }

                        return new McpSchema.ReadResourceResult(List.of(
                                new McpSchema.TextResourceContents("document://list", "text/plain", content)
                        ));
                    } catch (Exception e) {
                        log.error("Error reading document://list: {}", e.getMessage());
                        return new McpSchema.ReadResourceResult(List.of(
                                new McpSchema.TextResourceContents("document://list", "text/plain",
                                        "Error reading document list: " + e.getMessage())
                        ));
                    }
                });

        return List.of(listSpec);
    }

    // Template resource — registered under resources/templates/list
    // The host constructs the URI (e.g. "document://company-policy") and reads it;
    // we extract the id from the actual URI in the request.
    @Bean
    public List<McpServerFeatures.SyncResourceTemplateSpecification> documentTemplateResource(JdbcTemplate jdbc) {
        McpSchema.ResourceTemplate docTemplate = McpSchema.ResourceTemplate.builder()
                .uriTemplate("document://{id}")
                .name("Knowledge Base Document")
                .description("Returns metadata for a specific document in the knowledge base. " +
                        "Use document://list first to find available document IDs.")
                .mimeType("text/plain")
                .build();

        McpServerFeatures.SyncResourceTemplateSpecification docSpec =
                new McpServerFeatures.SyncResourceTemplateSpecification(docTemplate, (exchange, request) -> {
                    String uri = request.uri();
                    String documentId = uri.replace("document://", "");
                    log.info("MCP resource template read: document://{}", documentId);

                    try {
                        List<Map<String, Object>> results = jdbc.queryForList(
                                "SELECT id, chunk_count, ingested_at FROM knowledge_documents WHERE id = ?",
                                documentId
                        );

                        String content;
                        if (results.isEmpty()) {
                            content = "Document '" + documentId + "' not found in knowledge base. " +
                                      "Use document://list to see available documents.";
                        } else {
                            Map<String, Object> doc = results.get(0);
                            content = "Document: " + doc.get("id") + "\n" +
                                      "Chunks: " + doc.get("chunk_count") + "\n" +
                                      "Ingested at: " + doc.get("ingested_at") + "\n\n" +
                                      "Use ask_documents(question) to search content within this document.";
                        }

                        return new McpSchema.ReadResourceResult(List.of(
                                new McpSchema.TextResourceContents(uri, "text/plain", content)
                        ));
                    } catch (Exception e) {
                        log.error("Error reading document://{}: {}", documentId, e.getMessage());
                        return new McpSchema.ReadResourceResult(List.of(
                                new McpSchema.TextResourceContents(uri, "text/plain",
                                        "Error reading document metadata: " + e.getMessage())
                        ));
                    }
                });

        return List.of(docSpec);
    }
}
