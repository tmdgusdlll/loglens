package com.loglens.report;

import java.io.IOException;

public interface WebhookClient {

    void post(String jsonPayload) throws IOException, InterruptedException;
}
