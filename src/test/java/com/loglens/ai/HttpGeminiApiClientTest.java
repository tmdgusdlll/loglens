package com.loglens.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpGeminiApiClientTest {

    @Test
    void 요청_본문에_프롬프트와_생성_설정이_들어간다() {
        String body = HttpGeminiApiClient.buildRequestBody("따옴표 \" 와\n줄바꿈 포함 프롬프트");

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String text = root.getAsJsonArray("contents").get(0).getAsJsonObject()
                .getAsJsonArray("parts").get(0).getAsJsonObject()
                .get("text").getAsString();
        assertEquals("따옴표 \" 와\n줄바꿈 포함 프롬프트", text);

        JsonObject config = root.getAsJsonObject("generationConfig");
        assertEquals(0.2, config.get("temperature").getAsDouble(), 0.0001);
        assertEquals("application/json", config.get("responseMimeType").getAsString());
    }

    @Test
    void 응답_봉투에서_생성_텍스트를_추출한다() throws Exception {
        String response = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"cause\\":\\"x\\"}"}],"role":"model"}}]}
                """;
        assertEquals("{\"cause\":\"x\"}", HttpGeminiApiClient.extractText(response));
    }

    @Test
    void 예상_구조가_아닌_응답은_GeminiApiException을_던진다() {
        assertThrows(GeminiApiException.class,
                () -> HttpGeminiApiClient.extractText("{\"error\":{\"message\":\"bad\"}}"));
    }
}
