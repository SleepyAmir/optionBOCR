package com.example.ocrservice.dto;

public record OcrPageResponse(
        Integer pageNumber,
        String text
) {
}
