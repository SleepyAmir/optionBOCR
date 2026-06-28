package com.example.ocrservice.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * OCR output for one OcrFile. Kept in MongoDB because it is structured metadata
 * and text, while the original binary file lives in MinIO.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ocr_results")
public class OcrResult {

    @Id
    private String id;

    @Indexed(unique = true)
    private String fileId;

    @TextIndexed
    private String extractedText;

    /** Search-friendly normalized copy. Python/OpenSearch can use the same idea. */
    @Indexed
    private String normalizedText;

    /** Page-level text for RAG citation and Python chunking. */
    private List<OcrPage> pages;

    @Indexed
    private LocalDateTime createdAt;
}
