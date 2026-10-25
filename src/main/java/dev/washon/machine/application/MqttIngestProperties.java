package dev.washon.machine.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * MQTT ingest of camera OCR frames (dev.washon.machine.api.OcrMqttListener).
 * Off by default so local development and tests need no broker; the dev
 * profile turns it on and the server's compose file supplies the connection
 * via {@code WASHON_MQTT_URL} / {@code WASHON_MQTT_USERNAME} /
 * {@code WASHON_MQTT_PASSWORD} / {@code WASHON_MQTT_TOPIC_PREFIX}.
 *
 * @param enabled     whether to connect and subscribe at all
 * @param url         broker URL, e.g. {@code tcp://mosquitto:1883}
 * @param username    broker account (null for an anonymous broker)
 * @param password    broker password
 * @param clientId    MQTT client id; defaults to the username (then
 *                    {@code washon-backend}). It must stay fixed -- the broker
 *                    keeps QoS 1 frames for it while the backend restarts --
 *                    and differ per environment sharing one broker
 * @param topicPrefix cameras publish to {@code {topicPrefix}/{device_id}/ocr},
 *                    e.g. {@code dev/washon/wm-a1b2c3/ocr}
 */
@ConfigurationProperties("washon.mqtt")
public record MqttIngestProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("tcp://localhost:1883") String url,
        String username,
        String password,
        String clientId,
        @DefaultValue("washon") String topicPrefix) {

    public String effectiveClientId() {
        if (StringUtils.hasText(clientId)) {
            return clientId;
        }
        return StringUtils.hasText(username) ? username : "washon-backend";
    }

    /** Subscription filter matching every device's OCR topic. */
    public String ocrTopicFilter() {
        return topicPrefix + "/+/ocr";
    }
}
