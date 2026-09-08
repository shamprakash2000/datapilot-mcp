package com.example.config;

import com.example.tools.DatabaseMcpTools;
import com.example.tools.RagMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpServerConfig {

    // Registers both RAG tools (ask_documents, ingest_document) and DB tools
    // (list_tables, get_table_schema, execute_query) as a single MCP ToolCallbackProvider.
    // Spring AI's MCP server auto-config picks this up and exposes all 5 tools
    // via the tools/list endpoint during MCP handshake.
    @Bean
    public ToolCallbackProvider knowledgeToolCallbackProvider(RagMcpTools ragTools, DatabaseMcpTools dbTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(ragTools, dbTools)
                .build();
    }
}
