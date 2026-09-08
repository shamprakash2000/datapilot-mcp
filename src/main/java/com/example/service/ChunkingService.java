package com.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    private static final int CHUNK_SIZE = 100;
    private static final int CHUNK_OVERLAP = 20;

    public List<String> chunk(String text) {
        String[] sentences = text.split("(?<=[.!?])\\s+");

        List<String> chunks = new ArrayList<>();
        List<String> currentWords = new ArrayList<>();

        for (String sentence : sentences) {
            String[] sentenceWords = sentence.trim().split("\\s+");

            if (currentWords.size() + sentenceWords.length > CHUNK_SIZE && !currentWords.isEmpty()) {
                chunks.add(String.join(" ", currentWords));
                int overlapStart = Math.max(0, currentWords.size() - CHUNK_OVERLAP);
                currentWords = new ArrayList<>(currentWords.subList(overlapStart, currentWords.size()));
            }

            currentWords.addAll(Arrays.asList(sentenceWords));
        }

        if (!currentWords.isEmpty()) {
            chunks.add(String.join(" ", currentWords));
        }

        log.info("Split document into {} chunks (size={}, overlap={})", chunks.size(), CHUNK_SIZE, CHUNK_OVERLAP);
        return chunks;
    }
}
