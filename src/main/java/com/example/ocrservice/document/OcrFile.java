package com.example.ocrservice.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Document metadata only. The original uploaded file is NOT stored as byte[] in
 * MongoDB anymore; it is stored in MinIO and Mongo keeps only bucket/objectKey.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ocr_files")
public class OcrFile {

    @Id
    private String id;

    private String originalFileName;

    private String contentType;

    private Long fileSize;

    private Integer pageCount;

    private String bucketName;

    @Indexed
    private String objectKey;

    @Indexed
    private OcrStatus status;

    private String errorMessage;

    @Indexed
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
