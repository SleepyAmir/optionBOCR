package com.example.ocrservice.mapper;

import com.example.ocrservice.document.OcrFile;
import com.example.ocrservice.document.OcrPage;
import com.example.ocrservice.document.OcrResult;
import com.example.ocrservice.dto.OcrDocumentResponse;
import com.example.ocrservice.dto.OcrDocumentSummaryResponse;
import com.example.ocrservice.dto.OcrFileResponse;
import com.example.ocrservice.dto.OcrPageResponse;
import com.example.ocrservice.dto.OcrSearchResultResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/*==============================*
 *          OcrMapper           *
 *==============================*/
/**
 * Converts internal domain/documents into API response DTOs.
 *
 * Why this class exists:
 * - prevents controller/service code from manually copying fields everywhere
 * - keeps response-shaping logic in one place
 * - makes the API layer easier to evolve independently from persistence models
 *
 * Important boundary:
 * - mapping belongs here
 * - search logic / OCR logic / persistence logic does NOT belong here
 */
@Mapper(componentModel = "spring")
public abstract class OcrMapper {

    /*==============================*
     *   File + result -> document  *
     *==============================*/
    /**
     * Builds the full OCR document response from:
     * - file metadata (`OcrFile`)
     * - OCR result (`OcrResult`)
     */
    @Mapping(target = "id", source = "file.id")
    @Mapping(target = "createdAt", source = "file.createdAt")
    @Mapping(target = "updatedAt", source = "file.updatedAt")
    @Mapping(target = "extractedText", source = "result.extractedText")
    @Mapping(target = "pages", source = "result.pages")
    public abstract OcrDocumentResponse toDocumentResponse(OcrFile file, OcrResult result);

    /*==============================*
     *   File only -> document DTO  *
     *==============================*/
    /**
     * Builds a response when a file exists but OCR result is missing.
     *
     * This can happen when:
     * - OCR failed
     * - a result was not persisted yet
     * - metadata exists independently of extracted text
     */
    @Mapping(target = "extractedText", constant = "")
    @Mapping(target = "pages", expression = "java(java.util.List.of())")
    public abstract OcrDocumentResponse toDocumentResponseWithoutResult(OcrFile file);

    /*==============================*
     *      Summary projections     *
     *==============================*/
    /**
     * Converts file metadata into a lighter summary response.
     *
     * Used for listing pages where the full OCR text is unnecessary.
     */
    public abstract OcrDocumentSummaryResponse toSummaryResponse(OcrFile file);

    /*==============================*
     *       File download DTO      *
     *==============================*/
    /**
     * Wraps file metadata and binary bytes into a download-friendly DTO.
     */
    @Mapping(target = "fileName", expression = "java(resolveFileName(file))")
    @Mapping(target = "data", source = "data")
    public abstract OcrFileResponse toFileResponse(OcrFile file, byte[] data);

    /*==============================*
     *       Search result DTO      *
     *==============================*/
    /**
     * Builds a search response by combining:
     * - file metadata
     * - precomputed snippets from the search layer
     */
    @Mapping(target = "id", source = "file.id")
    @Mapping(target = "createdAt", source = "file.createdAt")
    @Mapping(target = "snippets", source = "snippets")
    public abstract OcrSearchResultResponse toSearchResponse(OcrFile file, List<String> snippets);

    /*==============================*
     *         Page mappings        *
     *==============================*/
    /** Converts one internal page model to API page DTO. */
    public abstract OcrPageResponse toPageResponse(OcrPage page);

    /** Converts multiple internal pages to API page DTO list. */
    public abstract List<OcrPageResponse> toPageResponses(List<OcrPage> pages);

    /*==============================*
     *       Small helper logic     *
     *==============================*/
    /**
     * Returns a safe filename for downloads.
     *
     * If the original name is missing, we generate a fallback using the file id
     * so callers still receive a meaningful download name.
     */
    protected String resolveFileName(OcrFile file) {
        if (file.getOriginalFileName() != null && !file.getOriginalFileName().isBlank()) {
            return file.getOriginalFileName();
        }
        return "ocr-document-" + file.getId();
    }
}
