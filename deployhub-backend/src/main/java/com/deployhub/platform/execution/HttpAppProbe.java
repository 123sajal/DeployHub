package com.deployhub.platform.execution;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** One GET with short timeouts; redirects are not followed (a 3xx already shows the app is up). */
@Component
public class HttpAppProbe implements AppProbe {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public int status(int hostPort, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + hostPort + path))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException notYet) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
