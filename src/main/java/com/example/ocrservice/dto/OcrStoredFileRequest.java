package com.example.ocrservice.dto;

import jakarta.validation.constraints.NotBlank;

/** Request used when the main Spring orchestrator already uploaded the file to MinIO. */
public record OcrStoredFileRequest(
        @NotBlank String bucketName,
        @NotBlank String objectKey,
        String originalFileName,
        String contentType,
        Long fileSize,
        Boolean persistResult
) {
}
