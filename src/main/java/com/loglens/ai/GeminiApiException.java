package com.loglens.ai;

public class GeminiApiException extends Exception {

    private final int statusCode;

    public GeminiApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /** HTTP 상태코드. 네트워크 오류처럼 상태코드가 없으면 0 */
    public int statusCode() {
        return statusCode;
    }
}
