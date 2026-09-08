package com.example.model;

import java.util.List;

public record ChunkData(
        String id,
        List<Float> vector,
        String text,
        String documentId,
        int chunkIndex,
        int totalChunks
) {}
