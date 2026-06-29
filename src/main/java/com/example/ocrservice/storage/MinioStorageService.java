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

@Service
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;

    /**
     * FIX — ensureBucket cache:
     * قبلاً هر بار که upload صدا می‌شد، یه HTTP call به MinIO می‌رفت تا
     * بررسی کنه bucket وجود داره یا نه. الان اسم bucketهایی که یه بار
     * verify شدن داخل این Set کش می‌شن و تا restart سرویس دیگه HTTP call
     * نمی‌ره. ConcurrentHashMap.newKeySet() thread-safe هست.
     */
    private final Set<String> verifiedBuckets = ConcurrentHashMap.newKeySet();

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
                    .contentType(contentType == null || contentType.isBlank()
                            ? "application/octet-stream"
                            : contentType)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file to MinIO: " + e.getMessage(), e);
        }
    }

    /**
     * Overload بدون contentType — برای جاهایی که contentType از قبل در
     * دسترس نیست. در صورت امکان از overload زیر استفاده کن تا نیاز به
     * statObject نباشه.
     */
    public StoredObject download(String bucketName, String objectKey) {
        return download(bucketName, objectKey, null);
    }

    /**
     * FIX — single HTTP call:
     * نسخه قبلی هم getObject (برای stream) هم statObject (برای metadata)
     * صدا می‌زد — دو connection همزمان به MinIO.
     *
     * الان:
     *   - فقط getObject صدا می‌شه (یه HTTP call)
     *   - size از data.length محاسبه می‌شه (همیشه دقیقه چون همه byte ها
     *     خونده شدن)
     *   - contentType از caller می‌گیریم؛ caller قبلاً این مقدار رو یا
     *     از MongoDB (file.getContentType) یا از request body داره
     *
     * @param knownContentType  مقدار file.getContentType() یا
     *                          request.contentType() رو پاس بده؛
     *                          null برای fallback به application/octet-stream
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

            // size = data.length چون همه bytes رو خوندیم —
            // نیازی به stat.size() که مستلزم call جداست نیست
            return new StoredObject(data, contentType, (long) data.length);

        } catch (Exception e) {
            throw new RuntimeException("Failed to download file from MinIO: " + e.getMessage(), e);
        }
    }

    /**
     * FIX — bucket caching:
     * اگه bucketName قبلاً در این session verify شده باشه زودتر return
     * می‌کنه. اولین بار هنوز bucketExists صدا می‌شه ولی بعد از اون دیگه نه.
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