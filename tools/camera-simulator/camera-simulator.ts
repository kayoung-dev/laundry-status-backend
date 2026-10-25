// Mock camera simulator (US-003).
//
// Sends a canned sequence of OCR payloads to POST /ingest/ocr -- or, with
// --mqtt-url, publishes them to {prefix}/{device_id}/ocr over MQTT --
// so backend (and later frontend) work can proceed without a real camera or
// the AI OCR engine sitting behind it.
//
// Usage:
//   npm run simulate -- <scenario> [options]
//
// Scenarios:
//   decreasing-countdown  Normal in-use pattern: a washer/dryer counting down.
//   finish                Countdown reaching zero, then a blank display: the
//                         machine goes straight back to available.
//   error                 An E- or F-prefixed error code appears on the display.
//   stale                 A couple of normal frames, then the camera goes silent
//                         (script just stops sending -- staleness detection is a
//                         receiver-side timeout concern, see US-004).
//   wash-cycle            P2-002 wash-stage convention: wash -> rinse -> spin ->
//                         available, using the "W:mm"/"R:mm"/"S:mm" stage-prefixed
//                         countdown text (see OcrReading.STAGE_PATTERN).
//
// Options (env vars or flags, flags take precedence):
//   --device-id <id>     Camera device_id to send as (default: seed-device-1,
//                         env DEVICE_ID)
//   --api-key <key>      X-Api-Key header value, HTTP only (default: read from
//                         backend/.seed-api-key, or env API_KEY)
//   --base-url <url>     Base URL of the running backend/ instance
//                         (default: http://localhost:8081, env BASE_URL)
//   --mqtt-url <url>     Publish over MQTT instead of HTTP, e.g.
//                         mqtts://mqtt.washon.site:8883 (env MQTT_URL)
//   --mqtt-username <u>  Broker account (default: the device id -- each camera
//                         has its own account, env MQTT_USERNAME)
//   --mqtt-password <p>  That account's password (env MQTT_PASSWORD)
//   --topic-prefix <p>   Topic prefix (default: dev/washon, env MQTT_TOPIC_PREFIX);
//                         frames go to {prefix}/{device_id}/ocr
//
// Examples:
//   npm run simulate -- decreasing-countdown
//   npm run simulate -- error --device-id my-device --api-key abc123
//   BASE_URL=http://localhost:4000 npm run simulate -- finish
//   MQTT_PASSWORD=... npm run simulate -- wash-cycle --mqtt-url mqtts://mqtt.washon.site:8883 \
//     --device-id wm-test

import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import mqtt from "mqtt";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// tools/camera-simulator/ -> backend repo root, where the seed profile writes .seed-api-key.
const BACKEND_ROOT = path.resolve(__dirname, "..", "..");
const SEED_API_KEY_PATH = path.join(BACKEND_ROOT, ".seed-api-key");

interface Frame {
  raw_text: string;
  confidence: number;
  /** Delay (ms) to wait BEFORE sending this frame (after the previous one). */
  delayMs: number;
}

interface Scenario {
  description: string;
  frames: Frame[];
}

const SCENARIOS: Record<string, Scenario> = {
  "decreasing-countdown": {
    description: "Normal in-use pattern: washer/dryer counting down.",
    frames: [
      { raw_text: "28", confidence: 0.97, delayMs: 0 },
      { raw_text: "25", confidence: 0.96, delayMs: 800 },
      { raw_text: "22", confidence: 0.98, delayMs: 800 },
      { raw_text: "19", confidence: 0.95, delayMs: 800 },
      { raw_text: "16", confidence: 0.97, delayMs: 800 },
    ],
  },
  finish: {
    description: "Countdown reaches zero, then the display goes blank: in_use -> available (no done state).",
    frames: [
      // 3 frames to cross the in-use consensus threshold first.
      { raw_text: "05", confidence: 0.96, delayMs: 0 },
      { raw_text: "04", confidence: 0.95, delayMs: 800 },
      { raw_text: "02", confidence: 0.95, delayMs: 800 },
      // A zero or blank display ends the cycle: available immediately.
      { raw_text: "00", confidence: 0.94, delayMs: 800 },
      { raw_text: "", confidence: 0.9, delayMs: 800 },
    ],
  },
  error: {
    description: "An E- or F-prefixed error code appears on the display.",
    frames: [
      { raw_text: "12", confidence: 0.96, delayMs: 0 },
      { raw_text: "E01", confidence: 0.92, delayMs: 1000 },
    ],
  },
  stale: {
    description:
      "A couple of normal frames, then the camera goes silent (script exits without sending more). " +
      "Staleness itself is a receiver-side timeout concern (US-004), not something this script asserts.",
    frames: [
      { raw_text: "20", confidence: 0.97, delayMs: 0 },
      { raw_text: "18", confidence: 0.96, delayMs: 800 },
    ],
  },
  "wash-cycle": {
    description:
      "P2-002 wash-stage convention: wash -> rinse -> spin -> available, using the stage-prefixed " +
      'countdown text "W:mm" / "R:mm" / "S:mm" (see backend OcrReading.STAGE_PATTERN).',
    frames: [
      // Wash: 3 frames to cross the in-use consensus threshold (AC1).
      { raw_text: "W:20", confidence: 0.97, delayMs: 0 },
      { raw_text: "W:16", confidence: 0.96, delayMs: 800 },
      { raw_text: "W:12", confidence: 0.98, delayMs: 800 },
      // Rinse, then spin: remainingMin keeps dropping across each stage
      // change, so the consensus window (which only requires a never-rising
      // sequence, not stage agreement) doesn't treat the switch as noise.
      // One frame per stage is enough -- stage tracks the latest frame the
      // same way remainingMin does, once the countdown consensus itself is
      // established (see StateDecision.decide).
      { raw_text: "R:9", confidence: 0.96, delayMs: 800 },
      { raw_text: "S:5", confidence: 0.95, delayMs: 800 },
      // End: a dark display means the cycle ended -> available. Longer pause here (both realistic, and to stay under the
      // per-camera ingest rate limit -- OcrIngestService.BUCKET_CAPACITY is
      // 5 frames per burst).
      { raw_text: "", confidence: 0.9, delayMs: 2000 },
    ],
  },
};

function parseArgs(argv: string[]) {
  const scenario = argv[0];
  const opts: Record<string, string> = {};
  for (let i = 1; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--device-id") opts.deviceId = argv[++i];
    else if (arg === "--api-key") opts.apiKey = argv[++i];
    else if (arg === "--base-url") opts.baseUrl = argv[++i];
    else if (arg === "--mqtt-url") opts.mqttUrl = argv[++i];
    else if (arg === "--mqtt-username") opts.mqttUsername = argv[++i];
    else if (arg === "--mqtt-password") opts.mqttPassword = argv[++i];
    else if (arg === "--topic-prefix") opts.topicPrefix = argv[++i];
    else {
      console.error(`Unknown argument: ${arg}`);
      process.exit(1);
    }
  }
  return { scenario, opts };
}

function readSeedApiKey(): string | undefined {
  if (!existsSync(SEED_API_KEY_PATH)) return undefined;
  return readFileSync(SEED_API_KEY_PATH, "utf8").trim();
}

function frameHash(deviceId: string, rawText: string, capturedAt: string): string {
  return createHash("sha256")
    .update(`${deviceId}:${rawText}:${capturedAt}:${Math.random()}`)
    .digest("hex")
    .slice(0, 16);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

type OcrPayload = {
  device_id: string;
  captured_at: string;
  confidence: number;
  raw_text: string;
  frame_hash: string;
};

/** Sends one frame; resolves to a short result string, rejects on failure. */
type Transport = { target: string; send(payload: OcrPayload): Promise<string>; close(): Promise<void> };

function httpTransport(baseUrl: string, apiKey: string): Transport {
  const url = `${baseUrl.replace(/\/$/, "")}/ingest/ocr`;
  return {
    target: `POST ${url}`,
    async send(payload) {
      const response = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Api-Key": apiKey },
        body: JSON.stringify(payload),
      });
      const bodyText = await response.text();
      if (!response.ok) throw new Error(`${response.status} ${bodyText}`);
      return `${response.status} OK ${bodyText}`;
    },
    async close() {},
  };
}

// MQTT has no response: a frame the backend rejects (unregistered device,
// rate limit) only shows up in the backend log. QoS 1 means the broker
// acknowledged receipt. The broker authenticates the camera, so no API key.
async function mqttTransport(url: string, username: string, password: string | undefined, topicPrefix: string,
    deviceId: string): Promise<Transport> {
  const topic = `${topicPrefix}/${deviceId}/ocr`;
  const client = await mqtt.connectAsync(url, { username, password, reconnectPeriod: 0, connectTimeout: 10_000 });
  return {
    target: `MQTT ${url} topic ${topic}`,
    async send(payload) {
      await client.publishAsync(topic, JSON.stringify(payload), { qos: 1 });
      return "published (QoS 1 acked by broker)";
    },
    async close() {
      await client.endAsync();
    },
  };
}

async function main() {
  const { scenario: scenarioName, opts } = parseArgs(process.argv.slice(2));

  if (!scenarioName || !SCENARIOS[scenarioName]) {
    console.error(`Usage: npm run simulate -- <scenario> [options]\n`);
    console.error("Available scenarios:");
    for (const [name, s] of Object.entries(SCENARIOS)) {
      console.error(`  ${name.padEnd(22)} ${s.description}`);
    }
    process.exit(1);
  }

  const deviceId = opts.deviceId ?? process.env.DEVICE_ID ?? "seed-device-1";
  const apiKey = opts.apiKey ?? process.env.API_KEY ?? readSeedApiKey();
  const baseUrl = opts.baseUrl ?? process.env.BASE_URL ?? "http://localhost:8081";

  const mqttUrl = opts.mqttUrl ?? process.env.MQTT_URL;

  if (!mqttUrl && !apiKey) {
    console.error(
      "No API key found. Pass --api-key, set API_KEY, or run the backend with " +
        "--spring.profiles.active=seed to generate backend/.seed-api-key.",
    );
    process.exit(1);
  }

  const transport = mqttUrl
    ? await mqttTransport(mqttUrl, opts.mqttUsername ?? process.env.MQTT_USERNAME ?? deviceId,
        opts.mqttPassword ?? process.env.MQTT_PASSWORD,
        opts.topicPrefix ?? process.env.MQTT_TOPIC_PREFIX ?? "dev/washon", deviceId)
    : httpTransport(baseUrl, apiKey!);

  const scenario = SCENARIOS[scenarioName];

  console.log(`Scenario: ${scenarioName} -- ${scenario.description}`);
  console.log(`Target:   ${transport.target}`);
  console.log(`Device:   ${deviceId}\n`);

  for (const [index, frame] of scenario.frames.entries()) {
    if (frame.delayMs > 0) {
      await sleep(frame.delayMs);
    }

    const capturedAt = new Date().toISOString();
    const payload: OcrPayload = {
      device_id: deviceId,
      captured_at: capturedAt,
      confidence: frame.confidence,
      raw_text: frame.raw_text,
      frame_hash: frameHash(deviceId, frame.raw_text, capturedAt),
    };

    const label = `[frame ${index + 1}/${scenario.frames.length}] raw_text=${JSON.stringify(frame.raw_text)} frame_hash=${payload.frame_hash}`;
    try {
      console.log(`${label} -> ${await transport.send(payload)}`);
    } catch (err) {
      console.error(`${label} -> ${err instanceof Error ? err.message : err}`);
      process.exitCode = 1;
    }
  }
  await transport.close();

  if (scenarioName === "stale") {
    console.log("\nDone sending frames for 'stale' scenario. Camera now goes silent (script exits).");
  } else {
    console.log("\nDone.");
  }
}

void main();
