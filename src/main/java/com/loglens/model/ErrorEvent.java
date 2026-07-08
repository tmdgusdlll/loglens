package com.loglens.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

public record ErrorEvent(String exceptionType, String location, String rawTrace, LocalDateTime timestamp) {

    /** 중복판정 키: 예외 타입 + 발생 위치의 SHA-256 해시 (ADR 0003) */
    public String dedupKey() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((exceptionType + "|" + location).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256은 모든 JVM에 필수 탑재", e);
        }
    }
}
