package com.loglens.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class HttpGeminiApiClient implements GeminiApiClient {

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;

    public HttpGeminiApiClient(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String generate(String prompt) throws GeminiApiException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            // apiKey가 null/제어문자를 포함하는 등 잘못된 값이면 header()가 언체크 예외를 던진다.
            // ADR 0010: 상위에서 GeminiApiException만 잡으므로 여기서 반드시 변환해야 한다.
            throw new GeminiApiException(0, "요청 구성 실패: " + e.getMessage());
        }
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GeminiApiException(0, "네트워크 오류: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiApiException(0, "요청 중단됨");
        }
        if (response.statusCode() != 200) {
            throw new GeminiApiException(response.statusCode(), "HTTP " + response.statusCode());
        }
        return extractText(response.body());
    }

    static String buildRequestBody(String prompt) {
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0.2); // ADR 0005: 사실 기반 응답 유도
        generationConfig.addProperty("responseMimeType", "application/json");

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);
        return new Gson().toJson(body);
    }

    static String extractText(String responseBody) throws GeminiApiException {
        try {
            return JsonParser.parseString(responseBody).getAsJsonObject()
                    .getAsJsonArray("candidates").get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts").get(0).getAsJsonObject()
                    .get("text").getAsString();
        } catch (RuntimeException e) {
            throw new GeminiApiException(0, "응답 구조 파싱 실패");
        }
    }
}
