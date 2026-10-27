package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.config.BillingProperties;
import com.deployhub.platform.exceptions.PaymentVerificationException;
import com.deployhub.platform.exceptions.ServiceUnavailableException;
import com.deployhub.platform.services.Interface.IPaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Razorpay's REST API (https://razorpay.com/docs/api/) over plain HTTPS with Basic auth (key id : key secret). */
@Slf4j
@Service
public class RazorpayGateway implements IPaymentGateway {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Ids go into URL paths, so only Razorpay's own shape is accepted. */
    private static final Pattern ID = Pattern.compile("^(order|pay)_[A-Za-z0-9]{1,30}$");

    private final BillingProperties.Razorpay razorpay;
    private final HttpClient client;

    public RazorpayGateway(BillingProperties properties) {
        this.razorpay = properties.razorpay();
        this.client = HttpClient.newBuilder().connectTimeout(razorpay.timeout()).build();
    }

    @Override
    public String createOrder(long amount, String currency, String receipt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("receipt", receipt);
        return send("POST", "/v1/orders", body).path("id").asString();
    }

    @Override
    public GatewayPayment fetchPayment(String paymentId) {
        return toPayment(send("GET", "/v1/payments/" + checkedId(paymentId), null));
    }

    @Override
    public List<GatewayPayment> fetchOrderPayments(String orderId) {
        JsonNode items = send("GET", "/v1/orders/" + checkedId(orderId) + "/payments", null).path("items");
        return items.valueStream().map(RazorpayGateway::toPayment).toList();
    }

    @Override
    public GatewayPayment capture(String paymentId, long amount, String currency) {
        return toPayment(send("POST", "/v1/payments/" + checkedId(paymentId) + "/capture",
                Map.of("amount", amount, "currency", currency)));
    }

    private static GatewayPayment toPayment(JsonNode node) {
        return new GatewayPayment(node.path("id").asString(), node.path("order_id").asString(null),
                node.path("status").asString(), node.path("amount").asLong(), node.path("currency").asString());
    }

    private static String checkedId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new PaymentVerificationException("That is not a Razorpay order or payment id.");
        }
        return id;
    }

    private JsonNode send(String method, String path, Object body) {
        String credentials = razorpay.keyId() + ":" + razorpay.keySecret();
        HttpRequest request = HttpRequest.newBuilder(URI.create(razorpay.apiUrl() + path))
                .timeout(razorpay.timeout())
                .header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ServiceUnavailableException("Razorpay can't be reached right now. Please try again.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Interrupted while calling Razorpay.", e);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return JSON.readTree(response.body());
        }
        // Razorpay's error bodies say what was wrong; they never contain the secret.
        String description = JSON.readTree(response.body()).path("error").path("description").asString("");
        log.warn("Razorpay {} {} answered {}: {}", method, path, status, description);
        if (status == 401) {
            throw new ServiceUnavailableException("Payments are misconfigured: Razorpay rejected DeployHub's API keys.");
        }
        if (status >= 500 || status == 429) {
            throw new ServiceUnavailableException("Razorpay is having problems right now. Please try again.");
        }
        throw new PaymentVerificationException("Razorpay refused the request: " + description);
    }
}
