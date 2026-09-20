package com.example.service;

import com.example.model.ChunkData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        StringBuilder vectorsJson = new StringBuilder("[");
        for (int i = 0; i < chunks.size(); i++) {
            ChunkData chunk = chunks.get(i);
            String safeText = chunk.text().replace("\"", "\\\"").replace("\n", " ");
            vectorsJson.append("""
                    {"id":"%s","values":%s,"metadata":{"text":"%s","documentId":"%s","chunkIndex":%d,"totalChunks":%d}}
                    """.formatted(
                    chunk.id(), chunk.vector().toString(), safeText,
                    chunk.documentId(), chunk.chunkIndex(), chunk.totalChunks()
            ).trim());
            if (i < chunks.size() - 1) vectorsJson.append(",");
        }
        vectorsJson.append("]");

        String requestBody = "{\"vectors\":" + vectorsJson + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/vectors/upsert"))
                .header("Content-Type", "application/json")
                .header("Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("Pinecone batch upsert ({} chunks): {}", chunks.size(), response.body());
    }

    public void deleteByIds(List<String> ids) throws Exception {
        String idsJson = ids.stream()
                .map(id -> "\"" + id + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String requestBody = "{\"ids\":[" + idsJson + "]}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/vectors/delete"))
                .header("Content-Type", "application/json")
                .header("Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("Pinecone delete ({} vectors): {}", ids.size(), response.body());
    }

    public List<String> search(List<Float> queryVector, int topK) throws Exception {
        String requestBody = """
                {
                  "vector": %s,
                  "topK": %d,
                  "includeMetadata": true
                }
                """.formatted(queryVector.toString(), topK);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/query"))
                .header("Content-Type", "application/json")
                .header("Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode matches = root.path("matches");

        // Below 0.70 the match is semantically too distant to be useful context for the LLM.
        final float SCORE_THRESHOLD = 0.70f;

        List<String> results = new ArrayList<>();
        for (JsonNode match : matches) {
            float score = match.path("score").floatValue();
            if (score < SCORE_THRESHOLD) {
                log.debug("Skipping match — score {} below threshold {}", score, SCORE_THRESHOLD);
                continue;
            }
            results.add(match.path("metadata").path("text").asText());
        }
        log.info("Pinecone search returned {} results above threshold", results.size());
        return results;
    }
}
