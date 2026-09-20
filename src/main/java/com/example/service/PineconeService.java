package com.example.service;

import com.example.model.ChunkData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Service
public class PineconeService {

    private static final Logger log = LoggerFactory.getLogger(PineconeService.class);

    @Value("${pinecone.api.key}")
    private String apiKey;

    @Value("${pinecone.host}")
    private String host;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void batchUpsert(List<ChunkData> chunks) throws Exception {
        // Build the upsert payload via Jackson so all text values are properly escaped.
        // Hand-rolled string concatenation breaks on backslashes, tabs, and other control chars.
        ArrayNode vectors = objectMapper.createArrayNode();
        for (ChunkData chunk : chunks) {
            ObjectNode metadata = objectMapper.createObjectNode()
                    .put("text", chunk.text())
                    .put("documentId", chunk.documentId())
                    .put("chunkIndex", chunk.chunkIndex())
                    .put("totalChunks", chunk.totalChunks());

            ArrayNode values = objectMapper.createArrayNode();
            for (Float v : chunk.vector()) values.add(v);

            ObjectNode vector = objectMapper.createObjectNode();
            vector.put("id", chunk.id());
            vector.set("values", values);
            vector.set("metadata", metadata);
            vectors.add(vector);
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.set("vectors", vectors);
        String requestBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/vectors/upsert"))
                .header("Content-Type", "application/json")
                .header("Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Pinecone upsert failed (" + response.statusCode() + "): " + response.body());
        }
        log.info("Pinecone batch upsert ({} chunks): {}", chunks.size(), response.body());
    }

    public List<String> search(List<Float> queryVector, int topK) throws Exception {
        // Build the query payload via Jackson for the same reason.
        ArrayNode values = objectMapper.createArrayNode();
        for (Float v : queryVector) values.add(v);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("vector", values);
        body.put("topK", topK);
        body.put("includeMetadata", true);
        String requestBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/query"))
                .header("Content-Type", "application/json")
                .header("Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode matches = root.path("matches");

        // Below 0.60 the match is semantically too distant to be useful context for the LLM.
        final float SCORE_THRESHOLD = 0.60f;

        List<String> results = new ArrayList<>();
        for (JsonNode match : matches) {
            float score = match.path("score").floatValue();
            log.debug("Pinecone match id={} score={}", match.path("id").asText(), score);
            if (score < SCORE_THRESHOLD) continue;
            results.add(match.path("metadata").path("text").asText());
        }
        log.info("Pinecone search returned {}/{} results above threshold {}", results.size(), matches.size(), SCORE_THRESHOLD);
        return results;
    }
}
