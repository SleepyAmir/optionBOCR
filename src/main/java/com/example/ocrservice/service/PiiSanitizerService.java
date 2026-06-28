package com.example.ocrservice.service;

import org.springframework.stereotype.Service;
import java.util.regex.Pattern;

/**
 * Censoring service to scrub Personally Identifiable Information (PII)
 * such as Iranian mobile numbers and bank card digits before storing
 * OCR text in MongoDB. This protects the team from GDPR/privacy leaks.
 */
@Service
public class PiiSanitizerService {

    private static final Pattern IRAN_PHONE = Pattern.compile("09\\d{9}|(\\+98|0)?9\\d{9}");
    private static final Pattern IRAN_BANK_CARD = Pattern.compile("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b");

    public String sanitize(String text) {
        if (text == null || text.isBlank()) return "";
        String scrubbed = IRAN_PHONE.matcher(text).replaceAll("[شماره موبایل سانسور شد]");
        return IRAN_BANK_CARD.matcher(scrubbed).replaceAll("[شماره کارت بانکی محرمانه]");
    }
}
