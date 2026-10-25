package dev.washon.machine.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.washon.machine.application.MqttIngestProperties;
import dev.washon.machine.application.OcrIngestService;
import dev.washon.machine.payload.request.OcrMqttMessage;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * MQTT counterpart of {@link OcrIngestApi}: subscribes to
 * {@code {prefix}/+/ocr} and hands every frame to the same
 * {@link OcrIngestService}, so the per-camera rate limit, frame_hash
 * idempotency and the state machine apply exactly as for POST /ingest/ocr.
 *
 * <p>Authentication is the broker's job: every camera has its own broker
 * account whose user name is its device_id, and the ACL
 * ({@code pattern write {prefix}/%u/#}) only lets it publish under that id.
 * The device id is therefore read from the topic and trusted (no API key);
 * it must still belong to a registered camera, and a payload
 * {@code device_id} that disagrees is rejected.
 *
 * <p>MQTT has no response channel, so a rejected or malformed frame is
 * logged and dropped. Nothing thrown from
 * {@link #messageArrived} may escape: Paho drops the connection if it does.
 *
 * <p>QoS 1 with a persistent session (fixed client id, cleanSession=false)
 * lets the broker hold frames that arrive while the backend restarts. Paho's
 * automatic reconnect only covers connections lost after a successful
 * connect, so the first connect is retried here until the broker is up.
 */
@Component
@ConditionalOnProperty(name = "washon.mqtt.enabled", havingValue = "true")
public class OcrMqttListener implements SmartLifecycle, MqttCallbackExtended {

    private static final int QOS = 1;
    private static final long INITIAL_CONNECT_RETRY_SECONDS = 5;

    private static final Logger log = LoggerFactory.getLogger(OcrMqttListener.class);

    private final OcrIngestService ocrIngestService;
    private final ObjectMapper objectMapper;
    private final MqttIngestProperties properties;
    private final Pattern ocrTopic;
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mqtt-connect-retry");
        thread.setDaemon(true);
        return thread;
    });

    private volatile MqttAsyncClient client;
    private volatile boolean running;

    public OcrMqttListener(OcrIngestService ocrIngestService, ObjectMapper objectMapper,
            MqttIngestProperties properties) {
        this.ocrIngestService = ocrIngestService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.ocrTopic = Pattern.compile(Pattern.quote(properties.topicPrefix()) + "/([^/]+)/ocr");
    }

    @Override
    public void start() {
        try {
            client = new MqttAsyncClient(properties.url(), properties.effectiveClientId(), new MemoryPersistence());
        } catch (MqttException e) {
            throw new IllegalStateException("Invalid MQTT configuration: " + properties.url(), e);
        }
        client.setCallback(this);
        running = true;
        connect();
    }

    private void connect() {
        if (!running) {
            return;
        }
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(false);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        options.setMaxReconnectDelay(30_000);
        if (StringUtils.hasText(properties.username())) {
            options.setUserName(properties.username());
            options.setPassword(properties.password() == null ? new char[0] : properties.password().toCharArray());
        }
        try {
            client.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    // Subscribing happens in connectComplete, which also runs on reconnects.
                }

                @Override
                public void onFailure(IMqttToken token, Throwable e) {
                    log.warn("MQTT connect to {} failed ({}); retrying in {}s",
                            properties.url(), e.getMessage(), INITIAL_CONNECT_RETRY_SECONDS);
                    scheduleConnectRetry();
                }
            });
        } catch (MqttException e) {
            log.warn("MQTT connect to {} failed ({}); retrying in {}s",
                    properties.url(), e.getMessage(), INITIAL_CONNECT_RETRY_SECONDS);
            scheduleConnectRetry();
        }
    }

    private void scheduleConnectRetry() {
        if (running) {
            retryExecutor.schedule(this::connect, INITIAL_CONNECT_RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        String filter = properties.ocrTopicFilter();
        log.info("MQTT {} to {}; subscribing to {}", reconnect ? "reconnected" : "connected", serverUri, filter);
        try {
            client.subscribe(filter, QOS);
        } catch (MqttException e) {
            log.error("MQTT subscribe to {} failed", filter, e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost ({}); reconnecting automatically", cause == null ? "unknown" : cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            handle(topic, message.getPayload());
        } catch (RuntimeException e) {
            log.error("Failed to process MQTT OCR frame on {}", topic, e);
        }
    }

    void handle(String topic, byte[] payload) {
        Matcher matcher = ocrTopic.matcher(topic);
        if (!matcher.matches()) {
            log.warn("Ignoring MQTT message on unexpected topic {}", topic);
            return;
        }
        String deviceId = matcher.group(1);

        OcrMqttMessage message;
        try {
            message = objectMapper.readValue(payload, OcrMqttMessage.class);
        } catch (IOException e) {
            log.warn("Rejected MQTT OCR frame from {}: invalid JSON", deviceId);
            return;
        }
        if (message.deviceId() != null && !message.deviceId().equals(deviceId)) {
            log.warn("Rejected MQTT OCR frame from {}: payload device_id does not match topic", deviceId);
            return;
        }

        try {
            ocrIngestService.ingestFromBrokerAuthenticatedDevice(deviceId, message.toIngestRequest(deviceId));
        } catch (ResponseStatusException e) {
            // 401 (device_id not registered as a camera) or 429 (rate limit).
            log.warn("Rejected MQTT OCR frame from {}: {} {}", deviceId, e.getStatusCode().value(), e.getReason());
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Subscribe-only client: nothing is published.
    }

    @Override
    public void stop() {
        running = false;
        retryExecutor.shutdownNow();
        MqttAsyncClient current = client;
        if (current == null) {
            return;
        }
        try {
            if (current.isConnected()) {
                current.disconnect().waitForCompletion(5_000);
            }
            current.close();
        } catch (MqttException e) {
            log.debug("MQTT disconnect failed", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
