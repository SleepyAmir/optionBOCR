package com.example.ocrservice.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*==============================*
 *      MinioStorageService     *
 *==============================*/
/**
 * Encapsulates all interaction with MinIO object storage.
 *
 * Why this class exists:
 * - keeps storage-specific code out of OCR business logic
 * - gives the rest of the project a simple upload/download API
 * - centralizes bucket handling and object-key generation
 *
 * Architectural idea:
 * - original binary files live in MinIO
 * - MongoDB stores metadata and OCR results only
 */
@Service
@RequiredArgsConstructor
public class MinioStorageService {

    /*==============================*
     *      Injected dependencies   *
     *==============================*/
    /** Low-level MinIO SDK client created by {@code MinioConfig}. */
    private final MinioClient minioClient;

    /*==============================*
     *       Runtime optimizations  *
     *==============================*/
    /**
     * Keeps track of buckets already verified during the current JVM lifetime.
     *
     * Why this exists:
     * - avoids repeated bucket existence checks on every upload
     * - reduces unnecessary network calls to MinIO
     * - safe for concurrent access
     */
    private final Set<String> verifiedBuckets = ConcurrentHashMap.newKeySet();

    /*==============================*
     *   Externalized properties    *
     *==============================*/
    /** Default bucket used when callers do not explicitly choose one. */
    @Value("${minio.bucket:ocr-documents}")
    private String defaultBucket;

    /** Exposes the configured default bucket to other layers. */
    public String defaultBucket() {
        return defaultBucket;
    }

    /*==============================*
     *       Upload operations      *
     *==============================*/
    /**
     * Uploads data to the default bucket and returns the generated object key.
     *
     * Typical usage:
     * - OCR service receives an uploaded file
     * - this method stores the raw binary in MinIO
     * - the returned key is stored in Mongo metadata
     */
    public String upload(byte[] data, String originalFileName, String contentType) {
        String objectKey = buildObjectKey(originalFileName);
        upload(defaultBucket, objectKey, data, contentType);
        return objectKey;
    }

    /**
     * Uploads bytes to a specific bucket/object key.
     *
     * This lower-level overload is useful when a caller already knows exactly
     * where the object should live.
     */
    public void upload(String bucketName, String objectKey, byte[] data, String contentType) {
        try {
            ensureBucket(bucketName);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .contentType(contentType == null || contentType.isBlank()
                            ? "application/octet-stream"
                            : contentType)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file to MinIO: " + e.getMessage(), e);
        }
    }

    /*==============================*
     *      Download operations     *
     *==============================*/
    /**
     * Convenience overload when content type is not already known.
     *
     * Prefer the overload that accepts `knownContentType` when possible,
     * because upstream layers often already know the type from Mongo/request
     * data and this helps keep the storage call path simpler.
     */
    public StoredObject download(String bucketName, String objectKey) {
        return download(bucketName, objectKey, null);
    }

    /**
     * Downloads an object from MinIO and returns:
     * - bytes
     * - content type
     * - size
     *
     * Why `knownContentType` exists:
     * - earlier versions needed an extra metadata lookup
     * - now callers can pass content type they already know
     * - this avoids an unnecessary extra MinIO round trip
     */
    public StoredObject download(String bucketName, String objectKey, String knownContentType) {
        try (var stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .build())) {

            byte[] data = stream.readAllBytes();

            String contentType = (knownContentType != null && !knownContentType.isBlank())
                    ? knownContentType
                    : "application/octet-stream";

            return new StoredObject(data, contentType, (long) data.length);

        } catch (Exception e) {
            throw new RuntimeException("Failed to download file from MinIO: " + e.getMessage(), e);
        }
    }

    /*==============================*
     *      Bucket management       *
     *==============================*/
    /**
     * Ensures the target bucket exists before upload.
     *
     * Behavior:
     * - returns immediately if this bucket was already verified in memory
     * - otherwise checks existence in MinIO
     * - creates the bucket if it does not exist
     */
    private void ensureBucket(String bucketName) throws Exception {
        if (verifiedBuckets.contains(bucketName)) {
            return;
        }
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder().bucket(bucketName).build());
        }
        verifiedBuckets.add(bucketName);
    }

    /*==============================*
     *      Object-key strategy     *
     *==============================*/
    /**
     * Builds a predictable storage path for uploaded files.
     *
     * Format idea:
     * - date-based folders for easier browsing/housekeeping
     * - UUID to avoid collisions
     * - sanitized original filename for readability
     */
    private String buildObjectKey(String originalFileName) {
        String safeName = originalFileName == null || originalFileName.isBlank()
                ? "uploaded-file"
                : originalFileName.replaceAll("[^a-zA-Z0-9._\\-()\\u0600-\\u06FF ]", "_");
        LocalDate today = LocalDate.now();
        return "ocr/%04d/%02d/%02d/%s-%s".formatted(
                today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
                UUID.randomUUID(), safeName);
    }
}
