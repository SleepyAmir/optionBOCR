package com.example.ocrservice.dto;

import com.example.ocrservice.document.OcrStatus;

import java.time.LocalDateTime;
import java.util.List;

public record OcrDocumentResponse(
        String id,
        String originalFileName,
        String contentType,
        Long fileSize,
        Integer pageCount,
        String bucketName,
        String objectKey,
        OcrStatus status,
        String errorMessage,
        String extractedText,
        List<OcrPageResponse> pages,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
