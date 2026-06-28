package com.example.ocrservice.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;

    @Value("${minio.bucket:ocr-documents}")
    private String defaultBucket;

    public String defaultBucket() {
        return defaultBucket;
    }

    public String upload(byte[] data, String originalFileName, String contentType) {
        String objectKey = buildObjectKey(originalFileName);
        upload(defaultBucket, objectKey, data, contentType);
        return objectKey;
    }

    public void upload(String bucketName, String objectKey, byte[] data, String contentType) {
        try {
            ensureBucket(bucketName);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file to MinIO: " + e.getMessage(), e);
        }
    }

    public StoredObject download(String bucketName, String objectKey) {
        try (var stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .build())) {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build());
            return new StoredObject(stream.readAllBytes(), stat.contentType(), stat.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to download file from MinIO: " + e.getMessage(), e);
        }
    }

    private void ensureBucket(String bucketName) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
    }

    private String buildObjectKey(String originalFileName) {
        String safeName = originalFileName == null || originalFileName.isBlank()
                ? "uploaded-file"
                : originalFileName.replaceAll("[^a-zA-Z0-9._\\-()\u0600-\u06FF ]", "_");
        LocalDate today = LocalDate.now();
        return "ocr/%04d/%02d/%02d/%s-%s".formatted(
                today.getYear(), today.getMonthValue(), today.getDayOfMonth(), UUID.randomUUID(), safeName);
    }
}
