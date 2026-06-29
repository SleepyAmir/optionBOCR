package com.example.ocrservice.service.impl;

import com.example.ocrservice.document.OcrFile;
import com.example.ocrservice.document.OcrPage;
import com.example.ocrservice.document.OcrResult;
import com.example.ocrservice.document.OcrStatus;
import com.example.ocrservice.dto.OcrDocumentResponse;
import com.example.ocrservice.dto.OcrDocumentSummaryResponse;
import com.example.ocrservice.dto.OcrExtractionResponse;
import com.example.ocrservice.dto.OcrFileResponse;
import com.example.ocrservice.dto.OcrSearchResultResponse;
import com.example.ocrservice.dto.OcrStoredFileRequest;
import com.example.ocrservice.exception.ResourceNotFoundException;
import com.example.ocrservice.mapper.OcrMapper;
import com.example.ocrservice.repository.OcrFileRepository;
import com.example.ocrservice.repository.OcrResultRepository;
import com.example.ocrservice.service.OcrService;
import com.example.ocrservice.service.PersianTextNormalizer;
import com.example.ocrservice.service.PiiSanitizerService;
import com.example.ocrservice.service.TextSearchService;
import com.example.ocrservice.storage.MinioStorageService;
import com.example.ocrservice.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/*==============================*
 *        OcrServiceImpl        *
 *==============================*/
/**
 * Core business implementation of the OCR service.
 *
 * This is the main orchestration class of the project.
 * A first-time reader should think of this class as the place where:
 * - OCR workflow is coordinated
 * - persistence decisions are made
 * - PDF/image extraction strategies are selected
 * - MinIO + MongoDB + OCR engine are connected together
 *
 * Important note:
 * This class is intentionally more orchestration-heavy than a tiny CRUD
 * service, because OCR requires several infrastructure steps around the core
 * extraction itself.
 */
@Service
@RequiredArgsConstructor
public class OcrServiceImpl implements OcrService {

    /*==============================*
     *      Injected dependencies   *
     *==============================*/
    /** Repository for file metadata documents. */
    private final OcrFileRepository fileRepository;

    /** Repository for extracted OCR results. */
    private final OcrResultRepository resultRepository;

    /** Maps persistence/domain models to API responses. */
    private final OcrMapper ocrMapper;

    /** Owns keyword/snippet building behavior for OCR search. */
    private final TextSearchService textSearchService;

    /** Scrubs basic PII before text is persisted or returned. */
    private final PiiSanitizerService piiSanitizerService;

    /** Handles original binary file storage in MinIO. */
    private final MinioStorageService minioStorageService;

    /*==============================*
     *   OCR-related configuration  *
     *==============================*/
    /** Path to tessdata files required by Tesseract. */
    @Value("${ocr.tessdata-path:}")
    private String tessdataPath;

    /** OCR language set used by Tesseract, defaulting to Persian + English. */
    @Value("${ocr.language:fas+eng}")
    private String language;

    /** Rendering DPI used when scanned PDFs are converted to images. */
    @Value("${ocr.pdf-dpi:250}")
    private int pdfDpi;

    /*==============================*
     *   Public API: persist flow   *
     *==============================*/
    /**
     * Full OCR flow with persistence.
     *
     * Step-by-step behavior:
     * 1. validate uploaded file presence
     * 2. create Mongo metadata with PROCESSING status
     * 3. upload original file to MinIO
     * 4. extract text/pages
     * 5. sanitize + save OCR result in Mongo
     * 6. mark file as COMPLETED and return final response
     *
     * Failure behavior:
     * - file status is changed to FAILED
     * - error message is persisted for inspection
     */
    @Override
    public OcrDocumentResponse extractAndSave(MultipartFile file) throws IOException {
        validateFile(file);
        byte[] bytes = file.getBytes();
        String contentType = normalizeContentType(file.getContentType(), file.getOriginalFilename());
        LocalDateTime now = LocalDateTime.now();

        OcrFile savedFile = fileRepository.save(OcrFile.builder()
                .originalFileName(file.getOriginalFilename())
                .contentType(contentType)
                .fileSize(file.getSize())
                .bucketName(minioStorageService.defaultBucket())
                .status(OcrStatus.PROCESSING)
                .createdAt(now)
                .updatedAt(now)
                .build());

        try {
            String objectKey = minioStorageService.upload(bytes, file.getOriginalFilename(), contentType);
            savedFile.setObjectKey(objectKey);
            fileRepository.save(savedFile);

            ExtractionResult extraction = extractText(bytes, contentType, file.getOriginalFilename());
            OcrResult result = saveCleanResult(savedFile.getId(), extraction, now);

            savedFile.setPageCount(extraction.pageCount());
            savedFile.setStatus(OcrStatus.COMPLETED);
            savedFile.setUpdatedAt(LocalDateTime.now());
            OcrFile completed = fileRepository.save(savedFile);

            return ocrMapper.toDocumentResponse(completed, result);
        } catch (RuntimeException e) {
            savedFile.setStatus(OcrStatus.FAILED);
            savedFile.setErrorMessage(e.getMessage());
            savedFile.setUpdatedAt(LocalDateTime.now());
            fileRepository.save(savedFile);
            throw e;
        }
    }

    /*==============================*
     *  Public API: stateless flow  *
     *==============================*/
    /**
     * OCR-only flow without persistence.
     *
     * Why this method exists:
     * - lets an upstream orchestrator reuse OCR without delegating storage
     * - ideal for service-to-service integration with a main platform
     */
    @Override
    public OcrExtractionResponse extractOnly(MultipartFile file) throws IOException {
        validateFile(file);
        long start = System.currentTimeMillis();
        String contentType = normalizeContentType(file.getContentType(), file.getOriginalFilename());
        ExtractionResult extraction = extractText(file.getBytes(), contentType, file.getOriginalFilename());
        String cleanText = piiSanitizerService.sanitize(extraction.text());
        List<OcrPage> cleanPages = sanitizePages(extraction.pages());
        return new OcrExtractionResponse(
                file.getOriginalFilename(),
                contentType,
                file.getSize(),
                extraction.pageCount(),
                language,
                cleanText,
                ocrMapper.toPageResponses(cleanPages),
                System.currentTimeMillis() - start
        );
    }

    /*==============================*
     *  Public API: stored-object   *
     *==============================*/
    /**
     * OCR flow for a file that already exists in MinIO.
     *
     * This method exists for distributed architectures where another service
     * already performed the upload and now sends only a storage reference.
     *
     * Important behavior:
     * - `persistResult=true`  => save metadata/result in Mongo
     * - `persistResult=false` => return transient response only
     */
    @Override
    public OcrDocumentResponse extractFromStoredFile(OcrStoredFileRequest request) {
        boolean persist = request.persistResult() == null || request.persistResult();

        StoredObject object = minioStorageService.download(
                request.bucketName(), request.objectKey(), request.contentType());

        String contentType = normalizeContentType(
                request.contentType() != null ? request.contentType() : object.contentType(),
                request.originalFileName());
        long size = request.fileSize() != null ? request.fileSize() : object.size();
        LocalDateTime now = LocalDateTime.now();

        OcrFile file = OcrFile.builder()
                .originalFileName(request.originalFileName())
                .contentType(contentType)
                .fileSize(size)
                .bucketName(request.bucketName())
                .objectKey(request.objectKey())
                .status(OcrStatus.PROCESSING)
                .createdAt(now)
                .updatedAt(now)
                .build();

        if (persist) {
            file = fileRepository.save(file);
        } else {
            // A transient UUID keeps response structure stable even when no
            // Mongo document is created.
            file.setId(UUID.randomUUID().toString());
        }

        try {
            ExtractionResult extraction = extractText(object.data(), contentType, request.originalFileName());
            file.setPageCount(extraction.pageCount());
            file.setStatus(OcrStatus.COMPLETED);
            file.setUpdatedAt(LocalDateTime.now());

            if (persist) {
                OcrResult result = saveCleanResult(file.getId(), extraction, now);
                file = fileRepository.save(file);
                return ocrMapper.toDocumentResponse(file, result);
            }

            String cleanText = piiSanitizerService.sanitize(extraction.text());
            OcrResult transientResult = OcrResult.builder()
                    .fileId(file.getId())
                    .extractedText(cleanText)
                    .normalizedText(PersianTextNormalizer.normalize(cleanText))
                    .pages(sanitizePages(extraction.pages()))
                    .createdAt(now)
                    .build();
            return ocrMapper.toDocumentResponse(file, transientResult);

        } catch (RuntimeException e) {
            file.setStatus(OcrStatus.FAILED);
            file.setErrorMessage(e.getMessage());
            file.setUpdatedAt(LocalDateTime.now());
            if (persist && file.getId() != null) {
                fileRepository.save(file);
            }
            throw e;
        }
    }

    /*==============================*
     *  Public API: read/download   *
     *==============================*/
    /** Returns one OCR document by id. */
    @Override
    public OcrDocumentResponse getDocument(String id) {
        OcrFile file = fileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OCR document not found with id: " + id));
        return resultRepository.findByFileId(id)
                .map(result -> ocrMapper.toDocumentResponse(file, result))
                .orElseGet(() -> ocrMapper.toDocumentResponseWithoutResult(file));
    }

    /**
     * Downloads the original file from MinIO using metadata stored in MongoDB.
     */
    @Override
    public OcrFileResponse getOriginalFile(String id) {
        OcrFile file = fileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OCR document not found with id: " + id));
        if (file.getBucketName() == null || file.getObjectKey() == null) {
            throw new ResourceNotFoundException("Original file location was not found for id: " + id);
        }
        StoredObject object = minioStorageService.download(
                file.getBucketName(), file.getObjectKey(), file.getContentType());
        return ocrMapper.toFileResponse(file, object.data());
    }

    /** Returns recent persisted OCR files in descending creation order. */
    @Override
    public Page<OcrDocumentSummaryResponse> getRecentDocuments(Pageable pageable) {
        return fileRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(ocrMapper::toSummaryResponse);
    }

    /*==============================*
     *   Public API: search logic   *
     *==============================*/
    /**
     * Searches for a keyword inside one persisted document and returns UI
     * snippets rather than full ranking/search metadata.
     */
    @Override
    public OcrSearchResultResponse searchInsideDocument(String id, String keyword) {
        validateKeyword(keyword);
        OcrFile file = fileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OCR document not found with id: " + id));
        OcrResult result = resultRepository.findByFileId(id)
                .orElseThrow(() -> new ResourceNotFoundException("OCR result not found for file id: " + id));
        List<String> snippets = textSearchService.buildSnippets(result.getExtractedText(), keyword);
        return ocrMapper.toSearchResponse(file, snippets);
    }

    /**
     * Searches across all persisted OCR results.
     *
     * Implementation detail:
     * - query is executed against normalized Mongo text
     * - matching files are then batch-fetched to avoid N+1 queries
     */
    @Override
    public Page<OcrSearchResultResponse> searchAllDocuments(String keyword, Pageable pageable) {
        validateKeyword(keyword);
        String normalizedKeyword = PersianTextNormalizer.normalize(keyword).trim();
        if (normalizedKeyword.isEmpty()) {
            throw new IllegalArgumentException("keyword is required");
        }
        String escapedKeyword = Pattern.quote(normalizedKeyword);

        Page<OcrResult> resultPage =
                resultRepository.searchByNormalizedTextRegex(escapedKeyword, pageable);

        List<String> fileIds = resultPage.getContent().stream()
                .map(OcrResult::getFileId)
                .toList();

        Map<String, OcrFile> fileMap = fileRepository.findAllById(fileIds).stream()
                .collect(Collectors.toMap(OcrFile::getId, f -> f));

        return resultPage.map(result -> {
            OcrFile file = fileMap.get(result.getFileId());
            if (file == null) {
                throw new ResourceNotFoundException(
                        "OCR file missing for result with fileId: " + result.getFileId());
            }
            List<String> snippets =
                    textSearchService.buildSnippets(result.getExtractedText(), keyword);
            return ocrMapper.toSearchResponse(file, snippets);
        });
    }

    /*==============================*
     *   Persistence helper logic   *
     *==============================*/
    /**
     * Persists a sanitized OCR result and its normalized searchable copy.
     */
    private OcrResult saveCleanResult(String fileId, ExtractionResult extraction, LocalDateTime now) {
        String cleanText = piiSanitizerService.sanitize(extraction.text());
        return resultRepository.save(OcrResult.builder()
                .fileId(fileId)
                .extractedText(cleanText)
                .normalizedText(PersianTextNormalizer.normalize(cleanText))
                .pages(sanitizePages(extraction.pages()))
                .createdAt(now)
                .build());
    }

    /**
     * Sanitizes each page individually so page-level output remains safe and
     * consistent with the persisted full extracted text.
     */
    private List<OcrPage> sanitizePages(List<OcrPage> pages) {
        if (pages == null) {
            return List.of();
        }
        List<OcrPage> clean = new ArrayList<>();
        for (OcrPage page : pages) {
            String cleanText = piiSanitizerService.sanitize(page.getText());
            clean.add(OcrPage.builder()
                    .pageNumber(page.getPageNumber())
                    .text(cleanText)
                    .normalizedText(PersianTextNormalizer.normalize(cleanText))
                    .build());
        }
        return clean;
    }

    /*==============================*
     *    Extraction strategy hub   *
     *==============================*/
    /**
     * Chooses the extraction strategy based on file type.
     *
     * Current supported categories:
     * - PDF
     * - image formats
     */
    private ExtractionResult extractText(byte[] bytes, String contentType, String fileName) {
        if (isPdf(contentType, fileName)) {
            return extractFromPdf(bytes);
        }
        if (isImage(contentType, fileName)) {
            String text = extractFromImage(bytes);
            OcrPage page = OcrPage.builder()
                    .pageNumber(1)
                    .text(text)
                    .normalizedText(PersianTextNormalizer.normalize(text))
                    .build();
            return new ExtractionResult(text, 1, List.of(page));
        }
        throw new IllegalArgumentException(
                "Unsupported file type. Only image and PDF files are supported.");
    }

    /*==============================*
     *      Image OCR strategy      *
     *==============================*/
    /**
     * Runs OCR directly on an image.
     */
    private String extractFromImage(byte[] imageData) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                throw new IllegalArgumentException("Uploaded file is not a valid image");
            }
            return newTesseract().doOCR(image).trim();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OCR failed for image: " + e.getMessage(), e);
        }
    }

    /*==============================*
     *       PDF OCR strategy       *
     *==============================*/
    /**
     * Extracts text from PDF using a two-stage strategy:
     *
     * 1. Try fast digital text extraction first
     * 2. If the PDF appears scanned/non-digital, render pages as images and OCR
     *    them with Tesseract
     *
     * This strategy exists because many PDFs already contain machine-readable
     * text, and OCR would be slower and noisier in those cases.
     */
    private ExtractionResult extractFromPdf(byte[] pdfData) {
        try (PDDocument document = Loader.loadPDF(pdfData)) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            List<OcrPage> digitalPages = new ArrayList<>();
            StringBuilder digitalFullText = new StringBuilder();

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document).trim();
                if (!pageText.isBlank()) {
                    digitalFullText.append("\n\n--- Page ").append(page).append(" ---\n")
                            .append(pageText);
                }
                digitalPages.add(OcrPage.builder()
                        .pageNumber(page)
                        .text(pageText)
                        .normalizedText(PersianTextNormalizer.normalize(pageText))
                        .build());
            }

            if (digitalFullText.toString().trim().length() > 50) {
                return new ExtractionResult(
                        digitalFullText.toString().trim(), pageCount, digitalPages);
            }

            PDFRenderer renderer = new PDFRenderer(document);
            Tesseract tesseract = newTesseract();
            StringBuilder text = new StringBuilder();
            List<OcrPage> pages = new ArrayList<>();

            for (int page = 0; page < pageCount; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, pdfDpi, ImageType.RGB);
                String pageText = tesseract.doOCR(image).trim();
                text.append("\n\n--- Page ").append(page + 1).append(" ---\n").append(pageText);
                pages.add(OcrPage.builder()
                        .pageNumber(page + 1)
                        .text(pageText)
                        .normalizedText(PersianTextNormalizer.normalize(pageText))
                        .build());
            }

            return new ExtractionResult(text.toString().trim(), pageCount, pages);
        } catch (Exception e) {
            throw new RuntimeException("OCR failed for PDF: " + e.getMessage(), e);
        }
    }

    /*==============================*
     *     Tesseract preparation    *
     *==============================*/
    /**
     * Builds a fresh Tesseract instance with configured datapath/language.
     *
     * A fresh instance keeps OCR setup localized and avoids leaking state
     * between requests.
     */
    private Tesseract newTesseract() {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(resolveTessdataPath());
        tesseract.setLanguage(language);
        return tesseract;
    }

    /**
     * Resolves the Tesseract training-data path.
     *
     * Resolution strategy:
     * - prefer explicitly configured path
     * - otherwise fall back to classpath `tessdata`
     */
    private String resolveTessdataPath() {
        if (tessdataPath != null && !tessdataPath.isBlank()) {
            return new File(tessdataPath).getAbsolutePath();
        }
        URL resource = getClass().getClassLoader().getResource("tessdata");
        if (resource == null) {
            throw new RuntimeException(
                    "tessdata was not found. Set OCR_TESSDATA_PATH or add tessdata to classpath.");
        }
        try {
            return new File(resource.toURI()).getAbsolutePath();
        } catch (URISyntaxException e) {
            return resource.getPath();
        }
    }

    /*==============================*
     *  File-type detection helpers *
     *==============================*/
    /** Returns true if the file appears to be a PDF. */
    private boolean isPdf(String contentType, String fileName) {
        return "application/pdf".equalsIgnoreCase(contentType) || hasExtension(fileName, ".pdf");
    }

    /** Returns true if the file appears to be a supported image. */
    private boolean isImage(String contentType, String fileName) {
        return (contentType != null && contentType.toLowerCase().startsWith("image/"))
                || hasAnyExtension(fileName, ".png", ".jpg", ".jpeg",
                ".bmp", ".tif", ".tiff", ".webp");
    }

    /**
     * Produces a normalized content type when the client-provided one is absent
     * or unreliable.
     */
    private String normalizeContentType(String contentType, String fileName) {
        if (contentType != null && !contentType.isBlank()) {
            return contentType;
        }
        if (isPdf(null, fileName)) return "application/pdf";
        if (isImage(null, fileName)) return "image/*";
        return "application/octet-stream";
    }

    private boolean hasAnyExtension(String fileName, String... extensions) {
        for (String ext : extensions) {
            if (hasExtension(fileName, ext)) return true;
        }
        return false;
    }

    private boolean hasExtension(String fileName, String extension) {
        return fileName != null && fileName.toLowerCase().endsWith(extension);
    }

    /*==============================*
     *     Input validation        *
     *==============================*/
    /** Basic null/empty upload validation. */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded");
        }
    }

    /** Basic keyword validation used by search endpoints. */
    private void validateKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("keyword is required");
        }
    }

    /*==============================*
     *    Internal helper record    *
     *==============================*/
    /**
     * Small internal transport object used to move extraction output between
     * private methods without leaking implementation details into the public API.
     */
    private record ExtractionResult(String text, int pageCount, List<OcrPage> pages) {}
}
