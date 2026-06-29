# OCR Service — MongoDB + MinIO + OpenSearch-ready

Standalone OCR microservice for a larger Spring/Python AI platform.

This service is intentionally focused on **OCR and text extraction only**. It is designed to sit beside a **main Spring Boot platform/orchestrator** and a separate **Python AI/RAG service**.

---

## 1) What this service does

This service is responsible for:

- receiving an image or PDF
- extracting Persian/English text
- extracting page-level text for downstream AI/RAG usage
- storing original uploaded files in MinIO
- storing OCR metadata/result in MongoDB
- returning clean text to other services
- exposing simple debug/demo keyword search over OCR text

This service is **not** responsible for:

- user authentication / authorization
- business workflow orchestration
- embeddings generation
- chunking for RAG
- vector search / hybrid retrieval
- LLM prompt orchestration

Those responsibilities should live in the **main Spring platform** and the **Python AI service**.

---

## 2) Architecture role

```text
Frontend
   -> sends document upload / OCR request

Main Spring Backend / Orchestrator
   -> authenticates user
   -> validates business workflow
   -> uploads file itself OR forwards file
   -> calls OCR service
   -> optionally calls Python AI service after OCR

OCR Service
   -> extracts Persian/English text
   -> returns extractedText + pages[]
   -> stores original file in MinIO when needed
   -> stores metadata/result in MongoDB when needed

MongoDB
   -> stores OCR metadata/result

MinIO
   -> stores original uploaded files

Python AI / RAG Service
   -> receives OCR text/pages as JSON
   -> normalizes/chunks text
   -> creates embeddings
   -> indexes/searches in OpenSearch

OpenSearch
   -> full-text / vector / hybrid index used by Python AI service
```

### Recommended service boundary

#### Main Spring platform should own:
- auth / service-to-service auth
- tenant/user context
- business rules
- orchestration between OCR and Python
- async workflow/job management
- audit logs
- API gateway/BFF responsibilities

#### OCR service should own:
- file intake for OCR
- PDF/image text extraction
- OCR fallback for scanned PDFs
- page-level text extraction
- initial Persian normalization for searchability
- basic PII scrubbing before persistence
- MinIO storage integration
- MongoDB OCR metadata/result persistence

#### Python AI service should own:
- chunking
- embeddings
- OpenSearch indexing
- retrieval / RAG
- AI summarization / classification / extraction pipelines

---

## 3) Internal extraction logic

The service uses the following extraction strategy:

### For images
- validate basic upload presence
- read image
- run Tesseract OCR (`fas+eng` by default)
- return one logical page in `pages[]`

### For PDFs
1. Try **digital text extraction first** using PDFBox
2. Extract text page-by-page
3. If enough digital text exists, return it directly
4. Otherwise fallback to **rendering each page as image + Tesseract OCR**

This makes the service faster and cheaper for digital PDFs while still supporting scanned PDFs.

---

## 4) Data/storage model

### MongoDB collections

#### `ocr_files`
Stores file metadata only:

- `id`
- `originalFileName`
- `contentType`
- `fileSize`
- `pageCount`
- `bucketName`
- `objectKey`
- `status`
- `errorMessage`
- `createdAt`
- `updatedAt`

#### `ocr_results`
Stores OCR output:

- `id`
- `fileId`
- `extractedText`
- `normalizedText`
- `pages[]`
- `createdAt`

### MinIO
Stores the original binary file itself.

This means:
- MongoDB does **not** store raw uploaded file bytes
- MinIO stores the original file
- MongoDB stores references (`bucketName`, `objectKey`) and OCR output

---

## 5) Key engineering behaviors

### 5.1 OCR persistence flow
Endpoint:

```http
POST /api/v1/ocr/documents
Content-Type: multipart/form-data
```

Flow:

1. receive multipart file
2. save file metadata in MongoDB with `PROCESSING`
3. upload original file to MinIO
4. extract OCR text/pages
5. sanitize text before persistence
6. save OCR result in MongoDB
7. update file status to `COMPLETED`
8. return document + OCR result as JSON

If OCR/storage fails:
- file status becomes `FAILED`
- `errorMessage` is saved

---

### 5.2 Stateless extraction flow
Endpoint:

```http
POST /api/v1/ocr/extract
Content-Type: multipart/form-data
```

Flow:

1. receive multipart file
2. extract OCR text/pages
3. sanitize text
4. return OCR result as JSON
5. do **not** persist file/result in MongoDB
6. do **not** upload to MinIO

This is the recommended endpoint for a main Spring orchestrator that only wants OCR output and manages persistence elsewhere.

---

### 5.3 Extract from already stored MinIO object
Endpoint:

```http
POST /api/v1/ocr/extract/stored
Content-Type: application/json
```

Flow:

1. receive `bucketName` + `objectKey`
2. download object from MinIO
3. run extraction
4. depending on `persistResult`:
   - persist result in MongoDB
   - or return transient OCR response only

This is useful when the main Spring backend already uploaded the file itself and wants OCR service to process it by reference.

---

## 6) Main REST API

Base path:

```text
/api/v1/ocr
```

---

### 6.1 Health

```http
GET /api/v1/ocr/health
```

#### Response

```json
{
  "success": true,
  "message": "Success",
  "data": "OCR service is running"
}
```

---

### 6.2 Upload + store + OCR

```http
POST /api/v1/ocr/documents
Content-Type: multipart/form-data
file=...
```

#### Request type
- `multipart/form-data`
- field name: `file`

#### What it does
- uploads original file to MinIO
- stores metadata/result in MongoDB
- returns OCR result JSON

#### Example response

```json
{
  "success": true,
  "message": "OCR extraction completed",
  "data": {
    "id": "6860f7b4ab9d2e0e8e532111",
    "originalFileName": "sample.pdf",
    "contentType": "application/pdf",
    "fileSize": 123456,
    "pageCount": 3,
    "bucketName": "ocr-documents",
    "objectKey": "ocr/2026/06/29/4f7d1e4a-9d65-4a9d-86d6-sample.pdf",
    "status": "COMPLETED",
    "errorMessage": null,
    "extractedText": "--- Page 1 --- ...",
    "pages": [
      {
        "pageNumber": 1,
        "text": "متن صفحه اول"
      },
      {
        "pageNumber": 2,
        "text": "متن صفحه دوم"
      }
    ],
    "createdAt": "2026-06-29T12:15:30",
    "updatedAt": "2026-06-29T12:15:35"
  }
}
```

---

### 6.3 Extract only

```http
POST /api/v1/ocr/extract
Content-Type: multipart/form-data
file=...
```

#### Request type
- `multipart/form-data`
- field name: `file`

#### What it does
- performs OCR only
- does not store file/result
- returns extracted text/pages

#### Example response

```json
{
  "success": true,
  "message": "OCR extraction completed",
  "data": {
    "originalFileName": "sample.pdf",
    "contentType": "application/pdf",
    "fileSize": 123456,
    "pageCount": 3,
    "language": "fas+eng",
    "extractedText": "--- Page 1 --- ...",
    "pages": [
      {
        "pageNumber": 1,
        "text": "متن صفحه اول"
      },
      {
        "pageNumber": 2,
        "text": "متن صفحه دوم"
      }
    ],
    "processingTimeMs": 1842
  }
}
```

---

### 6.4 Extract from existing MinIO object

```http
POST /api/v1/ocr/extract/stored
Content-Type: application/json
```

#### Example request JSON

```json
{
  "bucketName": "ocr-documents",
  "objectKey": "ocr/2026/06/29/example.pdf",
  "originalFileName": "example.pdf",
  "contentType": "application/pdf",
  "fileSize": 123456,
  "persistResult": true
}
```

#### Request fields

- `bucketName`: MinIO bucket name
- `objectKey`: MinIO object key
- `originalFileName`: original filename for response/metadata
- `contentType`: optional but recommended
- `fileSize`: optional but recommended
- `persistResult`: 
  - `true` or omitted => persist metadata/result in MongoDB
  - `false` => do not persist result, only return OCR output

#### Example response

```json
{
  "success": true,
  "message": "OCR extraction completed",
  "data": {
    "id": "6860f7b4ab9d2e0e8e532222",
    "originalFileName": "example.pdf",
    "contentType": "application/pdf",
    "fileSize": 123456,
    "pageCount": 2,
    "bucketName": "ocr-documents",
    "objectKey": "ocr/2026/06/29/example.pdf",
    "status": "COMPLETED",
    "errorMessage": null,
    "extractedText": "--- Page 1 --- ...",
    "pages": [
      {
        "pageNumber": 1,
        "text": "صفحه یک"
      },
      {
        "pageNumber": 2,
        "text": "صفحه دو"
      }
    ],
    "createdAt": "2026-06-29T12:30:10",
    "updatedAt": "2026-06-29T12:30:18"
  }
}
```

> Note: when `persistResult=false`, the response still contains an `id` for structural consistency, but that id is transient and does not represent a stored MongoDB document.

---

### 6.5 List recent documents

```http
GET /api/v1/ocr/documents?page=0&size=10
```

#### Example response shape

```json
{
  "success": true,
  "message": "Success",
  "data": {
    "content": [
      {
        "id": "6860f7b4ab9d2e0e8e532111",
        "originalFileName": "sample.pdf",
        "contentType": "application/pdf",
        "fileSize": 123456,
        "pageCount": 3,
        "bucketName": "ocr-documents",
        "objectKey": "ocr/2026/06/29/...",
        "status": "COMPLETED",
        "errorMessage": null,
        "createdAt": "2026-06-29T12:15:30",
        "updatedAt": "2026-06-29T12:15:35"
      }
    ],
    "pageable": {},
    "totalPages": 1,
    "totalElements": 1,
    "last": true,
    "size": 10,
    "number": 0,
    "sort": {},
    "numberOfElements": 1,
    "first": true,
    "empty": false
  }
}
```

---

### 6.6 Get one document

```http
GET /api/v1/ocr/documents/{id}
```

Returns metadata + OCR result if available.

---

### 6.7 Download original file

```http
GET /api/v1/ocr/documents/{id}/file
```

Returns the original binary file from MinIO.

---

### 6.8 Search inside one document

```http
GET /api/v1/ocr/documents/{id}/search?keyword=دانشگاه
```

#### Example response

```json
{
  "success": true,
  "message": "Success",
  "data": {
    "id": "6860f7b4ab9d2e0e8e532111",
    "originalFileName": "sample.pdf",
    "pageCount": 3,
    "snippets": [
      "... دانشگاه تهران ...",
      "... پذیرش دانشگاه ..."
    ],
    "createdAt": "2026-06-29T12:15:30"
  }
}
```

---

### 6.9 Search across all persisted documents

```http
GET /api/v1/ocr/search?keyword=دانشگاه&page=0&size=10
```

#### Important note
This endpoint is intended for **demo/debug keyword search only**.

It uses normalized MongoDB regex search over persisted OCR text.
For production AI retrieval, use **Python + OpenSearch** instead.

---

## 7) JSON contract with other services

### 7.1 What the main Spring platform may send to OCR

#### Option A — raw file upload
The main Spring service can forward a raw user file as:

```http
POST /api/v1/ocr/extract
Content-Type: multipart/form-data
file=...
```

#### Option B — MinIO reference JSON
If the main Spring service already uploaded the file to MinIO, it can send:

```json
{
  "bucketName": "ocr-documents",
  "objectKey": "ocr/2026/06/29/example.pdf",
  "originalFileName": "example.pdf",
  "contentType": "application/pdf",
  "fileSize": 123456,
  "persistResult": false
}
```

---

### 7.2 What OCR returns to the main Spring platform

Typical OCR JSON payload returned by this service:

```json
{
  "originalFileName": "example.pdf",
  "contentType": "application/pdf",
  "fileSize": 123456,
  "pageCount": 2,
  "language": "fas+eng",
  "extractedText": "--- Page 1 --- ...",
  "pages": [
    {
      "pageNumber": 1,
      "text": "متن صفحه 1"
    },
    {
      "pageNumber": 2,
      "text": "متن صفحه 2"
    }
  ],
  "processingTimeMs": 1842
}
```

The main Spring service can then:
- store workflow state
- attach `tenantId`, `userId`, `requestId`
- forward OCR output to Python AI service
- persist additional business metadata

---

### 7.3 What the main Spring platform may send to Python AI service

Suggested downstream JSON from Spring to Python:

```json
{
  "documentId": "6860f7b4ab9d2e0e8e532111",
  "tenantId": "tenant-001",
  "userId": "user-123",
  "fileName": "example.pdf",
  "contentType": "application/pdf",
  "pageCount": 2,
  "extractedText": "--- Page 1 --- ...",
  "pages": [
    {
      "pageNumber": 1,
      "text": "متن صفحه 1"
    },
    {
      "pageNumber": 2,
      "text": "متن صفحه 2"
    }
  ]
}
```

---

### 7.4 What Python AI service may produce

Suggested indexed chunk payload shape:

```json
{
  "documentId": "6860f7b4ab9d2e0e8e532111",
  "chunkId": "chunk-001",
  "pageNumber": 1,
  "text": "بخشی از متن صفحه 1",
  "embedding": [0.12, -0.04, 0.88],
  "fileName": "example.pdf",
  "tenantId": "tenant-001",
  "userId": "user-123"
}
```

---

## 8) Persian/English normalization and privacy behavior

### Persian-aware normalization
The service keeps a normalized copy of OCR text for search consistency.
Normalization covers cases like:

- Arabic `ي` -> Persian `ی`
- Arabic `ك` -> Persian `ک`
- digit normalization
- invisible marks / ZWNJ handling
- basic Arabic diacritics removal
- lowercase for English text

### PII sanitization
Before persistence, the service performs basic scrubbing of:
- Iranian mobile numbers
- Iranian 16-digit bank card numbers

Important:
- original binary file in MinIO may still contain PII
- proper access control must be enforced by the main Spring platform

---

## 9) Local run with Docker Compose

### Start core stack

```bash
docker compose up --build
```

This starts:
- OCR service
- MongoDB
- MinIO

### Start full stack including OpenSearch

```bash
docker compose --profile search up --build
```

This additionally starts:
- OpenSearch
- OpenSearch Dashboards

---

## 10) Actual Docker ports in this repository

The current `docker-compose.yml` exposes these host ports:

```text
OCR API / UI:             http://localhost:43817
Swagger UI:               http://localhost:43817/swagger-ui.html
MongoDB from host:        mongodb://localhost:43818/ocr_db
MinIO API:                http://localhost:43819
MinIO Console:            http://localhost:43820
OpenSearch:               http://localhost:43821
OpenSearch metrics port:  http://localhost:43822
OpenSearch Dashboards:    http://localhost:43823
```

### MinIO login

```text
username: minioadmin
password: minioadmin
```

---

## 11) Run Java app locally without Docker

You still need MongoDB and MinIO running.

### Start infra containers only

```bash
docker compose up -d mongodb minio
```

If you also want the optional search stack:

```bash
docker compose --profile search up -d mongodb minio opensearch opensearch-dashboards
```

### Run Java locally

```bash
SPRING_DATA_MONGODB_URI=mongodb://localhost:43818/ocr_db \
MINIO_ENDPOINT=http://localhost:43819 \
MINIO_ACCESS_KEY=minioadmin \
MINIO_SECRET_KEY=minioadmin \
MINIO_BUCKET=ocr-documents \
mvn spring-boot:run
```

### Windows PowerShell

```powershell
$env:SPRING_DATA_MONGODB_URI="mongodb://localhost:43818/ocr_db"
$env:MINIO_ENDPOINT="http://localhost:43819"
$env:MINIO_ACCESS_KEY="minioadmin"
$env:MINIO_SECRET_KEY="minioadmin"
$env:MINIO_BUCKET="ocr-documents"
mvn spring-boot:run
```

---

## 12) Example curl

### Health

```bash
curl http://localhost:43817/api/v1/ocr/health
```

### Upload + persist

```bash
curl -X POST http://localhost:43817/api/v1/ocr/documents \
  -F "file=@sample.pdf"
```

### Extract only

```bash
curl -X POST http://localhost:43817/api/v1/ocr/extract \
  -F "file=@sample.pdf"
```

### Extract from existing stored object

```bash
curl -X POST http://localhost:43817/api/v1/ocr/extract/stored \
  -H "Content-Type: application/json" \
  -d '{
    "bucketName": "ocr-documents",
    "objectKey": "ocr/2026/06/29/example.pdf",
    "originalFileName": "example.pdf",
    "contentType": "application/pdf",
    "fileSize": 123456,
    "persistResult": true
  }'
```

### Search demo endpoint

```bash
curl "http://localhost:43817/api/v1/ocr/search?keyword=دانشگاه&page=0&size=10"
```

---

## 13) Swagger / UI

- Swagger UI: `http://localhost:43817/swagger-ui.html`
- Simple test/demo UI: `http://localhost:43817/`

---

## 14) Engineering notes and current limitations

Current implementation strengths:
- split between metadata and OCR result
- MinIO for file storage
- PDF digital extraction before OCR fallback
- page-level OCR output
- Persian-aware normalized search support
- basic PII scrubbing
- stateless and persisted OCR modes

Current limitations / future production work:
- add service-to-service auth
- add strict file signature validation
- add page count / OCR timeout limits
- add async queue/job mode for large files
- add integration and unit tests
- add tracing / correlation IDs
- add cleanup policy for failed/orphaned files
- harden OpenSearch security in non-local environments

---

## 15) Recommended production integration pattern

Recommended flow for a larger platform:

1. frontend uploads file to main Spring platform
2. main Spring platform authenticates user and sets tenant context
3. file is stored in MinIO
4. main Spring calls OCR `/extract/stored`
5. OCR returns `extractedText` and `pages[]`
6. main Spring forwards OCR result to Python AI service
7. Python chunks text and creates embeddings
8. Python indexes/searches in OpenSearch
9. main Spring exposes final business/search APIs to frontend

This keeps service responsibilities clean and avoids turning OCR service into a full orchestration layer.

---

## 16) About

This repository is an OCR-focused microservice intended to be one building block inside a broader Spring + Python AI platform.