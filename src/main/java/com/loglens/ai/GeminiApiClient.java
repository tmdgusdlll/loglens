package com.loglens.ai;

public interface GeminiApiClient {

    /** 프롬프트를 보내고 모델이 생성한 텍스트를 돌려받는다 */
    String generate(String prompt) throws GeminiApiException;
}
