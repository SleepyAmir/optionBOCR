package com.example.ocrservice.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*==============================*
 *         MinioConfig          *
 *==============================*/
/**
 * Creates the MinIO client used by the storage layer.
 *
 * Why this class exists:
 * - keeps MinIO connection setup centralized
 * - makes the client injectable anywhere in the application
 * - isolates environment/config handling from business logic
 *
 * Architectural role:
 * - config classes create infrastructure beans
 * - service classes should consume these beans, not build them manually
 */
@Configuration
public class MinioConfig {

    /*==============================*
     *   Externalized properties    *
     *==============================*/
    /** MinIO endpoint, usually local Docker or a remote S3-compatible host. */
    @Value("${minio.endpoint:http://localhost:9000}")
    private String endpoint;

    /** Access key for MinIO authentication. */
    @Value("${minio.access-key:minioadmin}")
    private String accessKey;

    /** Secret key for MinIO authentication. */
    @Value("${minio.secret-key:minioadmin}")
    private String secretKey;

    /*==============================*
     *      Infrastructure bean     *
     *==============================*/
    /**
     * Provides a reusable MinIO client bean.
     *
     * This bean is used by {@code MinioStorageService} for:
     * - file upload
     * - file download
     * - bucket existence checks / creation
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
