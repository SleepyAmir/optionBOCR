package com.example.ocrservice.controller;

import com.example.ocrservice.dto.ApiResponse;
import com.example.ocrservice.dto.OcrDocumentResponse;
import com.example.ocrservice.dto.OcrDocumentSummaryResponse;
import com.example.ocrservice.dto.OcrExtractionResponse;
import com.example.ocrservice.dto.OcrFileResponse;
import com.example.ocrservice.dto.OcrSearchResultResponse;
import com.example.ocrservice.dto.OcrStoredFileRequest;
import com.example.ocrservice.service.OcrService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/*==============================*
 *         OcrController        *
 *==============================*/
/**
 * Main REST controller for the OCR service.
 *
 * Why this class exists:
 * - exposes the OCR API to external callers
 * - keeps HTTP-specific concerns out of the service layer
 * - delegates all business logic to {@link OcrService}
 *
 * What a first-time reader should know:
 * - this class should stay thin
 * - it should only translate HTTP requests/responses
 * - actual OCR, storage, and search behavior lives in the service layer
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ocr")
public class OcrController {

    /*==============================*
     *      Injected dependencies   *
     *==============================*/
    /**
     * The single entry point for OCR business logic.
     *
     * The controller never talks directly to MongoDB, MinIO, Tesseract,
     * or PDFBox. All of that is intentionally hidden behind this service.
     */
    private final OcrService ocrService;

    /*==============================*
     *         Health endpoint      *
     *==============================*/
    /**
     * Lightweight health endpoint.
     *
     * Intended use:
     * - local smoke test
     * - docker/k8s readiness probes
     * - quick verification by upstream services
     */
    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.ok("OCR service is running");
    }

    /*==============================*
     *      Upload + persist OCR    *
     *==============================*/
    /**
     * Full persistence flow.
     *
     * What this endpoint does:
     * 1. receives a multipart file
     * 2. stores original file in MinIO
     * 3. stores metadata/result in MongoDB
     * 4. returns the final OCR document response
     *
     * Typical caller:
     * - manual UI/demo usage
     * - a backend that wants OCR + storage handled by this service
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OcrDocumentResponse> extractDocument(@RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.ok("OCR extraction completed", ocrService.extractAndSave(file));
    }

    /*==============================*
     *      Stateless OCR only      *
     *==============================*/
    /**
     * Stateless extraction endpoint.
     *
     * Why this endpoint exists:
     * - sometimes the main Spring platform already owns persistence
     * - sometimes another service only needs extracted text/pages
     * - this avoids coupling OCR to storage when not needed
     *
     * Result:
     * - no MongoDB persistence
     * - no MinIO upload
     * - OCR response only
     */
    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<OcrExtractionResponse> extractOnly(@RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.ok("OCR extraction completed", ocrService.extractOnly(file));
    }

    /*==============================*
     *   OCR from existing object   *
     *==============================*/
    /**
     * Extracts text from a file that already exists in MinIO.
     *
     * Why this endpoint is important:
     * - in a larger architecture, upload may happen in another service
     * - the OCR service then receives only bucket/objectKey
     * - this avoids re-uploading the binary file over HTTP
     */
    @PostMapping(value = "/extract/stored", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<OcrDocumentResponse> extractStored(@Valid @RequestBody OcrStoredFileRequest request) {
        return ApiResponse.ok("OCR extraction completed", ocrService.extractFromStoredFile(request));
    }

    /*==============================*
     *    Document browsing APIs    *
     *==============================*/
    /**
     * Returns persisted OCR documents in reverse creation order.
     *
     * Intended for:
     * - admin/debug screens
     * - simple document browsing
     * - quick verification of recent OCR activity
     */
    @GetMapping("/documents")
    public ApiResponse<Page<OcrDocumentSummaryResponse>> getRecentDocuments(Pageable pageable) {
        return ApiResponse.ok(ocrService.getRecentDocuments(pageable));
    }

    /**
     * Returns a single OCR document by id.
     *
     * Depending on persistence state, the response may include:
     * - metadata only
     * - metadata + OCR result
     */
    @GetMapping("/documents/{id}")
    public ApiResponse<OcrDocumentResponse> getDocument(@PathVariable String id) {
        return ApiResponse.ok(ocrService.getDocument(id));
    }

    /*==============================*
     *   Original file download API *
     *==============================*/
    /**
     * Downloads the original uploaded file.
     *
     * Important behavior:
     * - file bytes come from MinIO, not MongoDB
     * - metadata is used to set filename and content type correctly
     */
    @GetMapping("/documents/{id}/file")
    public ResponseEntity<byte[]> downloadOriginalFile(@PathVariable String id) {
        OcrFileResponse file = ocrService.getOriginalFile(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.data());
    }

    /*==============================*
     *      Search within one doc   *
     *==============================*/
    /**
     * Searches for a keyword inside one persisted OCR document.
     *
     * Output is snippet-oriented rather than full search ranking.
     * This makes it useful for UI/debug scenarios.
     */
    @GetMapping("/documents/{id}/search")
    public ApiResponse<OcrSearchResultResponse> searchInsideDocument(@PathVariable String id, @RequestParam String keyword) {
        return ApiResponse.ok(ocrService.searchInsideDocument(id, keyword));
    }

    /*==============================*
     *    Search across all docs    *
     *==============================*/
    /**
     * Demo/debug keyword search across persisted OCR results.
     *
     * Important architecture note:
     * - this is not the final AI/RAG search layer
     * - production retrieval should live in Python + OpenSearch
     * - this endpoint is intentionally simple and operationally useful
     */
    @GetMapping("/search")
    public ApiResponse<Page<OcrSearchResultResponse>> searchAllDocuments(@RequestParam String keyword, Pageable pageable) {
        return ApiResponse.ok(ocrService.searchAllDocuments(keyword, pageable));
    }
}
