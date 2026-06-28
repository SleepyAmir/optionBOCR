package com.example.ocrservice.service;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Scrubs common Iranian PII from the persisted OCR text. The original file in
 * MinIO can still contain PII, so access control must be enforced by the main
 * backend/orchestrator.
 */
@Service
public class PiiSanitizerService {

    private static final Pattern IRAN_PHONE = Pattern.compile("(?<!\\d)(?:\\+?98|0)?9\\d{9}(?!\\d)");
    private static final Pattern IRAN_BANK_CARD = Pattern.compile("(?<!\\d)\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}(?!\\d)");

    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String digitNormalized = normalizeDigitsOnly(text);
        String scrubbed = IRAN_PHONE.matcher(digitNormalized).replaceAll("[شماره موبایل سانسور شد]");
        return IRAN_BANK_CARD.matcher(scrubbed).replaceAll("[شماره کارت بانکی محرمانه]");
    }

    private String normalizeDigitsOnly(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            sb.append(switch (c) {
                case '\u0660', '\u06F0' -> '0';
                case '\u0661', '\u06F1' -> '1';
                case '\u0662', '\u06F2' -> '2';
                case '\u0663', '\u06F3' -> '3';
                case '\u0664', '\u06F4' -> '4';
                case '\u0665', '\u06F5' -> '5';
                case '\u0666', '\u06F6' -> '6';
                case '\u0667', '\u06F7' -> '7';
                case '\u0668', '\u06F8' -> '8';
                case '\u0669', '\u06F9' -> '9';
                default -> c;
            });
        }
        return sb.toString();
    }
}
