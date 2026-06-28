package com.example.ocrservice.dto;

import java.util.List;

/**
 * Stateless OCR output. Useful when another Spring service owns storage and
 * only needs OCR text/pages to forward to Python AI/RAG.
 */
public record OcrExtractionResponse(
        String originalFileName,
        String contentType,
        Long fileSize,
        Integer pageCount,
        String language,
        String extractedText,
        List<OcrPageResponse> pages,
        Long processingTimeMs
) {
}
