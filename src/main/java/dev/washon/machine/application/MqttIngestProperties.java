package dev.washon.machine.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MQTT ingest of camera OCR frames (dev.washon.machine.api.OcrMqttListener).
 * Off by default so local development and tests need no broker; the dev
 * profile turns it on and docker-compose.dev.yml supplies the connection via
 * {@code WASHON_MQTT_URL} / {@code WASHON_MQTT_USERNAME} / {@code WASHON_MQTT_PASSWORD}.
 *
 * @param enabled  whether to connect and subscribe at all
 * @param url      broker URL, e.g. {@code tcp://mosquitto:1883}
 * @param username broker account (null for an anonymous broker)
 * @param password broker password
 * @param clientId fixed client id: with a persistent session the broker keeps
 *                 QoS 1 frames for this id while the backend is restarting
 */
@ConfigurationProperties("washon.mqtt")
public record MqttIngestProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("tcp://localhost:1883") String url,
        String username,
        String password,
        @DefaultValue("washon-backend") String clientId) {
}
