package com.example.ocrservice.storage;

public record StoredObject(
        byte[] data,
        String contentType,
        Long size
) {
}
