package com.example.ocrservice.dto;

import com.example.ocrservice.document.OcrStatus;

import java.time.LocalDateTime;

public record OcrDocumentSummaryResponse(
        String id,
        String originalFileName,
        String contentType,
        Long fileSize,
        Integer pageCount,
        String bucketName,
        String objectKey,
        OcrStatus status,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
