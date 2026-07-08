package com.loglens.report;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class HttpWebhookClient implements WebhookClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String webhookUrl;

    public HttpWebhookClient(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void post(String jsonPayload) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Slack webhook HTTP " + response.statusCode());
        }
    }
}
