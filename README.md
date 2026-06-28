# OCR Service — MongoDB + MinIO + OpenSearch-ready

Standalone OCR microservice for a larger Spring/Python AI platform.

## Architecture role

This service is intentionally focused on OCR/text extraction:

```text
Main Spring Backend / Orchestrator
        -> sends file or MinIO objectKey
OCR Service
        -> extracts Persian/English text + page-level text
MongoDB
        -> stores document metadata/status and OCR result
MinIO
        -> stores original uploaded files
Python AI/RAG Service
        -> chunks text, creates embeddings, indexes/searches in OpenSearch
OpenSearch
        -> full-text / vector / hybrid search index for Python
```

OpenSearch is included in `docker-compose.yml` for the full local stack, but the recommended production responsibility is: **Python owns chunking/embedding/indexing/search**, while OCR returns clean text/pages.

## Features

- Java 21 / Spring Boot
- Tess4J/Tesseract OCR
- Persian + English OCR (`fas+eng`)
- Image and PDF support
- Fast digital PDF text extraction before scanned-page OCR fallback
- MongoDB metadata/result storage
- MinIO object storage for original uploaded files instead of Mongo `byte[]`
- Page-level OCR output for Python RAG citations/chunking
- Persian-aware normalization for keyword search
- Basic PII sanitization for Iranian mobile/card numbers before persisting OCR text
- REST API + simple Thymeleaf test UI
- Docker Compose stack: MongoDB, MinIO, OpenSearch, OpenSearch Dashboards, OCR service

## Main endpoints

### Health

```http
GET /api/v1/ocr/health
```

### Upload + store + OCR

Stores original file in MinIO, stores metadata/result in MongoDB.

```http
POST /api/v1/ocr/documents
Content-Type: multipart/form-data
file=...
```

### Extract only

Stateless endpoint for the main Spring orchestrator. Does not persist file/result.

```http
POST /api/v1/ocr/extract
Content-Type: multipart/form-data
file=...
```

### Extract from existing MinIO object

Useful when the main Spring backend already uploaded the file to MinIO.

```http
POST /api/v1/ocr/extract/stored
Content-Type: application/json

{
  "bucketName": "ocr-documents",
  "objectKey": "ocr/2026/06/28/example.pdf",
  "originalFileName": "example.pdf",
  "contentType": "application/pdf",
  "fileSize": 123456,
  "persistResult": true
}
```

### Other endpoints

```http
GET /api/v1/ocr/documents?page=0&size=10
GET /api/v1/ocr/documents/{id}
GET /api/v1/ocr/documents/{id}/file
GET /api/v1/ocr/documents/{id}/search?keyword=...
GET /api/v1/ocr/search?keyword=...&page=0&size=10
```

The `/search` endpoints are for demo/debug keyword search. In the final platform, Python + OpenSearch should do the real AI/search/RAG retrieval.

## Run with Docker Compose

```bash
docker compose up --build
```

URLs:

```text
OCR UI:                  http://localhost:8090/
Swagger:                 http://localhost:8090/swagger-ui.html
MongoDB from host:        mongodb://localhost:27018/ocr_db
MinIO API:                http://localhost:9000
MinIO Console:            http://localhost:9001
OpenSearch:               http://localhost:9200
OpenSearch Dashboards:    http://localhost:5601
```

MinIO login:

```text
username: minioadmin
password: minioadmin
```

## Run locally without Docker for the Java app

You still need MongoDB and MinIO running. You can start only infra:

```bash
docker compose up -d mongodb minio opensearch opensearch-dashboards
```

Then run Java locally:

```bash
SPRING_DATA_MONGODB_URI=mongodb://localhost:27018/ocr_db \
MINIO_ENDPOINT=http://localhost:9000 \
MINIO_ACCESS_KEY=minioadmin \
MINIO_SECRET_KEY=minioadmin \
MINIO_BUCKET=ocr-documents \
mvn spring-boot:run
```

On Windows PowerShell:

```powershell
$env:SPRING_DATA_MONGODB_URI="mongodb://localhost:27018/ocr_db"
$env:MINIO_ENDPOINT="http://localhost:9000"
$env:MINIO_ACCESS_KEY="minioadmin"
$env:MINIO_SECRET_KEY="minioadmin"
$env:MINIO_BUCKET="ocr-documents"
mvn spring-boot:run
```

## Example curl

```bash
curl http://localhost:8090/api/v1/ocr/health
```

```bash
curl -X POST http://localhost:8090/api/v1/ocr/documents \
  -F "file=@sample.pdf"
```

```bash
curl -X POST http://localhost:8090/api/v1/ocr/extract \
  -F "file=@sample.pdf"
```

```bash
curl "http://localhost:8090/api/v1/ocr/search?keyword=دانشگاه&page=0&size=10"
```

## Notes for the Python AI service

Recommended flow:

1. OCR returns `pages[]` and `extractedText`.
2. Python normalizes text similarly to `PersianTextNormalizer`.
3. Python chunks page text.
4. Python creates embeddings.
5. Python indexes chunks into OpenSearch with metadata:

```json
{
  "documentId": "...",
  "chunkId": "...",
  "pageNumber": 2,
  "text": "...",
  "embedding": [0.1, 0.2],
  "fileName": "...",
  "tenantId": "...",
  "userId": "..."
}
```

## Production TODOs

- Add auth/service-to-service authentication.
- Add async job queue for large PDFs.
- Add virus scanning / stricter file signature validation.
- Add page count and OCR timeout limits.
- Add integration tests.
- Configure OpenSearch security for non-local environments.
