// Mock camera simulator (US-003).
//
// Posts a canned sequence of OCR payloads to POST /ingest/ocr, so backend
// (and later frontend) work can proceed without a real camera or the AI OCR
// engine sitting behind it.
//
// Usage:
//   npm run simulate -- <scenario> [options]
//
// Scenarios:
//   decreasing-countdown  Normal in-use pattern: a washer/dryer counting down.
//   done                  Countdown reaching near-zero, then a blank/zero display.
//   error                 An E- or F-prefixed error code appears on the display.
//   stale                 A couple of normal frames, then the camera goes silent
//                         (script just stops sending -- staleness detection is a
//                         receiver-side timeout concern, see US-004).
//   wash-cycle            P2-002 wash-stage convention: wash -> rinse -> spin ->
//                         done, using the "W:mm"/"R:mm"/"S:mm" stage-prefixed
//                         countdown text (see OcrReading.STAGE_PATTERN).
//
// Options (env vars or flags, flags take precedence):
//   --device-id <id>     Camera device_id to send as (default: seed-device-1,
//                         env DEVICE_ID)
//   --api-key <key>      X-Api-Key header value (default: read from
//                         backend/.seed-api-key, or env API_KEY)
//   --base-url <url>     Base URL of the running backend/ instance
//                         (default: http://localhost:8081, env BASE_URL)
//
// Examples:
//   npm run simulate -- decreasing-countdown
//   npm run simulate -- error --device-id my-device --api-key abc123
//   BASE_URL=http://localhost:4000 npm run simulate -- done

import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

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
  done: {
    description: "Countdown reaches near zero, then the display goes blank/zero.",
    frames: [
      { raw_text: "05", confidence: 0.96, delayMs: 0 },
      { raw_text: "02", confidence: 0.95, delayMs: 800 },
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
      "P2-002 wash-stage convention: wash -> rinse -> spin -> done, using the stage-prefixed " +
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
      // Done: dark display right after a countdown means the cycle just
      // ended. Longer pause here (both realistic, and to stay under the
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

  if (!apiKey) {
    console.error(
      "No API key found. Pass --api-key, set API_KEY, or run the backend with " +
        "--spring.profiles.active=seed to generate backend/.seed-api-key.",
    );
    process.exit(1);
  }

  const scenario = SCENARIOS[scenarioName];
  const url = `${baseUrl.replace(/\/$/, "")}/ingest/ocr`;

  console.log(`Scenario: ${scenarioName} -- ${scenario.description}`);
  console.log(`Target:   POST ${url}`);
  console.log(`Device:   ${deviceId}\n`);

  for (const [index, frame] of scenario.frames.entries()) {
    if (frame.delayMs > 0) {
      await sleep(frame.delayMs);
    }

    const capturedAt = new Date().toISOString();
    const payload = {
      device_id: deviceId,
      captured_at: capturedAt,
      confidence: frame.confidence,
      raw_text: frame.raw_text,
      frame_hash: frameHash(deviceId, frame.raw_text, capturedAt),
    };

    let response: Response;
    try {
      response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Api-Key": apiKey,
        },
        body: JSON.stringify(payload),
      });
    } catch (err) {
      console.error(`[frame ${index + 1}/${scenario.frames.length}] request failed:`, err);
      process.exitCode = 1;
      continue;
    }

    const bodyText = await response.text();
    const label = `[frame ${index + 1}/${scenario.frames.length}] raw_text=${JSON.stringify(frame.raw_text)} frame_hash=${payload.frame_hash}`;
    if (response.ok) {
      console.log(`${label} -> ${response.status} OK ${bodyText}`);
    } else {
      console.error(`${label} -> ${response.status} ${bodyText}`);
      process.exitCode = 1;
    }
  }

  if (scenarioName === "stale") {
    console.log("\nDone sending frames for 'stale' scenario. Camera now goes silent (script exits).");
  } else {
    console.log("\nDone.");
  }
}

void main();
