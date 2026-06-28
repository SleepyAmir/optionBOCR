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

@Mapper(componentModel = "spring")
public abstract class OcrMapper {

    @Mapping(target = "id", source = "file.id")
    @Mapping(target = "createdAt", source = "file.createdAt")
    @Mapping(target = "updatedAt", source = "file.updatedAt")
    @Mapping(target = "extractedText", source = "result.extractedText")
    @Mapping(target = "pages", source = "result.pages")
    public abstract OcrDocumentResponse toDocumentResponse(OcrFile file, OcrResult result);

    @Mapping(target = "extractedText", constant = "")
    @Mapping(target = "pages", expression = "java(java.util.List.of())")
    public abstract OcrDocumentResponse toDocumentResponseWithoutResult(OcrFile file);

    public abstract OcrDocumentSummaryResponse toSummaryResponse(OcrFile file);

    @Mapping(target = "fileName", expression = "java(resolveFileName(file))")
    @Mapping(target = "data", source = "data")
    public abstract OcrFileResponse toFileResponse(OcrFile file, byte[] data);

    @Mapping(target = "id", source = "file.id")
    @Mapping(target = "createdAt", source = "file.createdAt")
    @Mapping(target = "snippets", source = "snippets")
    public abstract OcrSearchResultResponse toSearchResponse(OcrFile file, List<String> snippets);

    public abstract OcrPageResponse toPageResponse(OcrPage page);

    public abstract List<OcrPageResponse> toPageResponses(List<OcrPage> pages);

    protected String resolveFileName(OcrFile file) {
        if (file.getOriginalFileName() != null && !file.getOriginalFileName().isBlank()) {
            return file.getOriginalFileName();
        }
        return "ocr-document-" + file.getId();
    }
}
