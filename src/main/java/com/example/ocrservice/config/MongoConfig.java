package com.example.ocrservice.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*==============================*
 *         MongoConfig          *
 *==============================*/
/**
 * Explicit MongoDB client configuration.
 *
 * Why this class exists:
 * - guarantees that the configured connection string is actually used
 * - avoids ambiguity in containerized environments
 * - makes Mongo client creation explicit and readable for new contributors
 *
 * Historical reason:
 * In Docker/container scenarios, relying on implicit defaults can easily cause
 * the app to accidentally try connecting to localhost:27017 inside the app
 * container, which is usually wrong. This config removes that uncertainty.
 */
@Configuration
public class MongoConfig {

    /*==============================*
     *   Externalized properties    *
     *==============================*/
    /**
     * MongoDB connection string.
     *
     * Resolution order is controlled by Spring property resolution, typically:
     * 1. environment variable
     * 2. application.yaml
     * 3. default value below
     */
    @Value("${spring.data.mongodb.uri:mongodb://localhost:27017/ocr_db}")
    private String mongoUri;

    /*==============================*
     *      Infrastructure bean     *
     *==============================*/
    /**
     * Creates the MongoDB client bean used by Spring Data MongoDB.
     *
     * Keeping this explicit helps first-time readers understand exactly how the
     * application reaches MongoDB, especially in Dockerized development.
     */
    @Bean
    public MongoClient mongoClient() {
        ConnectionString connectionString = new ConnectionString(mongoUri);
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .build();
        return MongoClients.create(settings);
    }
}
