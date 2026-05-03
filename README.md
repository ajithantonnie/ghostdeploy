# GhostDeploy — Runtime API Behavior Intelligence

> **Zero-config Spring Boot starter that silently watches your APIs, learns their behavior, and alerts you the moment something breaks the contract — before your users notice.**

[![Maven Central](https://img.shields.io/maven-central/v/dev.antonnie/ghostdeploy-starter)](https://central.sonatype.com/artifact/dev.antonnie/ghostdeploy-starter)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)](https://spring.io/projects/spring-boot)

---

## 🚀 Overview

Here's a scenario that happens more than it should: a third-party service quietly drops a field from its response payload. Your code compiles fine, tests pass, deployments succeed — and then, hours or days later, a customer reports broken functionality. Or worse, nobody notices until revenue is impacted.

This is a **silent API failure** — and it's one of the hardest bugs to catch.

**GhostDeploy** solves this by becoming an invisible co-pilot for your APIs. Drop it into your project as a single dependency, and it starts observing every response your application produces. Over time it builds a behavioral baseline — what fields normally appear, what types they carry, how often values are null, what status codes are typical. The moment observed behavior diverges significantly from that baseline, it fires a structured alert.

No YAML required. No annotations. No integration effort.

---

## ✨ Features

| Feature | Description |
|---|---|
| **Zero Configuration** | Activates automatically on classpath detection. Works out of the box. |
| **Runtime Contract Learning** | Builds a statistical model of your API's behavior from live traffic. |
| **Field Drop Detection** | Alerts when a historically stable field goes missing from a response. |
| **Schema Drift Detection** | Tracks structural changes using fast MurmurHash3 fingerprinting. |
| **Type Drift Detection** | Supports polymorphic fields; only alerts when a new type's share crosses 20%. |
| **Null Rate Spike Detection** | Tracks null frequency per field and alerts on anomalous spikes. |
| **Extra Field Detection** | Flags newly appearing fields after the baseline is stable. |
| **Response Time Anomaly** | Fires when response time exceeds 3× the historical average. |
| **Status Code Drift** | Detects unexpected 4xx/5xx patterns. |
| **Low-Noise Alerting** | Warm-up period + confidence thresholds + 10-minute debouncing = no alert fatigue. |
| **Async & Safe** | All heavy processing is offloaded from the request thread. |
| **GZIP Support** | Detects and decompresses gzip-encoded responses before analysis. |
| **Micrometer Integration** | Exposes `ghostdeploy.requests.processed` and `ghostdeploy.anomalies.detected` metrics. |
| **Contract Snapshot** | Optional endpoint to export the learned API schema as JSON. |

---

## 📦 Installation

Add the single dependency to your `pom.xml`:

```xml
<dependency>
  <groupId>dev.antonnie</groupId>
  <artifactId>ghostdeploy-starter</artifactId>
  <version>0.0.1</version>
</dependency>
```

That's it. GhostDeploy registers itself, wraps your responses, and starts learning.

> **Java 17+ and Spring Boot 3.x required.**

---

## ⚙️ How It Works

GhostDeploy operates in three quiet phases:

### 1. Intercept
A `Filter` wraps every HTTP response in a `ContentCachingResponseWrapper`. After the response is written, a thin interceptor copies the raw bytes to a local buffer — entirely synchronous, always on the request thread.

### 2. Learn (Async)
The byte buffer is handed off to a bounded `ThreadPoolTaskExecutor`. On a worker thread, GhostDeploy:
- Parses the JSON body
- Decompresses GZIP if needed (with a configurable byte limit)
- Extracts all field paths with their types using recursive traversal
- Normalizes dynamic path segments (e.g., numeric IDs → `*`)
- Records field presence, type frequency, null rates, and status codes
- Computes a MurmurHash3 fingerprint of the canonical field structure

### 3. Detect
Once the warm-up period ends (default: 200 requests), GhostDeploy starts comparing each new observation against the learned baseline, emitting structured JSON alerts via SLF4J and persisting them to the database.

---

## 🧠 Example

**Normal response:**
```json
{ "userId": 42, "name": "Alice", "status": "ACTIVE", "balance": 1250.00 }
```

**Broken response (field dropped):**
```json
{ "userId": 42, "status": "ACTIVE" }
```

**GhostDeploy alert (structured JSON log):**
```json
{
  "endpointKey": "my-service:/api/v1/users",
  "issueType": "FIELD_DROP",
  "description": "Field 'name' is missing. Historical presence: 98.5%",
  "severity": "HIGH",
  "detectedAt": "2026-05-02T16:43:00"
}
```

GhostDeploy also detects **type drift**:

**Normal:** `"price": 19.99` (NUMBER)  
**After a backend change:** `"price": "19.99"` (STRING)

```json
{
  "issueType": "TYPE_DRIFT",
  "description": "Field 'price' type changed to STRING. New type share: 21.3%",
  "severity": "HIGH"
}
```

---

## 🔒 Configuration (Optional)

GhostDeploy works with zero configuration, but everything is tunable via `application.yml` or `application.properties`:

```yaml
ghostdeploy:
  enabled: true                    # Set to false to disable entirely (default: true)
  sampling-rate: 0.5               # Only process 50% of requests (default: 0.5)
  max-body-size-bytes: 10240       # Skip bodies larger than 10KB (default: 10240)
  warmup-requests: 200             # Don't alert until this many requests seen (default: 200)
  max-endpoints-tracked: 500       # LRU-evict oldest endpoints after this limit (default: 500)
  max-fields-per-endpoint: 50      # LRU-evict oldest fields after this limit (default: 50)
  endpoint-ttl-minutes: 30         # Evict endpoints not seen in this window (default: 30)
  field-ttl-minutes: 60            # Evict fields not seen in this window (default: 60)
  alert-debounce-minutes: 10       # Suppress duplicate alerts for this window (default: 10)
  exclude-paths:
    - /health
    - /actuator
    - /error
    - /ghostdeploy

  threshold:
    field-expected-presence: 0.90  # Alert on drop if field was present >90% historically (default: 0.90)
    field-drop: 0.20               # Alert if drop rate crosses 20% (default: 0.20)
    ignore-frequency: 0.10         # Ignore fields with frequency <10% (default: 0.10)
    type-drift-sensitivity: 0.20   # Alert if new type crosses 20% share (default: 0.20)
    null-rate-spike: 0.30          # Alert if null appears but historical rate was <30% (default: 0.30)
    min-samples: 30                # Minimum observations before a field is "trusted" (default: 30)
    min-presence-rate: 0.30        # Field must have appeared >30% of the time to be tracked (default: 0.30)

  contracts:
    enabled: false                 # Expose /ghostdeploy/contracts endpoint (default: false)

  async:
    queue-capacity: 1000           # Drop tasks silently if queue is full (default: 1000)
```

---

## 📊 Endpoints

### `GET /ghostdeploy/contracts`

> **Disabled by default.** Enable via `ghostdeploy.contracts.enabled=true`.

Returns a compact JSON snapshot of the learned API schema for every tracked endpoint:

```json
{
  "my-service:/api/v1/users": {
    "userId": {
      "presenceRate": 1.0,
      "types": { "NUMBER": 1000 }
    },
    "name": {
      "presenceRate": 0.98,
      "types": { "STRING": 980 }
    }
  }
}
```

This is useful for generating documentation, drift regression tests, or building your own observability tooling on top.

---

## ⚡ Performance

GhostDeploy is designed to have **near-zero impact** on your application's throughput:

- **Sampling**: Only a fraction of requests are analyzed (configurable, default 50%). The sampling decision is made synchronously before any work is dispatched.
- **Async processing**: JSON parsing, GZIP decompression, field extraction, and anomaly detection all run on a separate bounded thread pool — never blocking the request thread.
- **Fail-safe queue**: If the internal queue fills up (e.g., during a traffic spike), tasks are silently dropped using `ThreadPoolExecutor.DiscardPolicy`. Your application is never the bottleneck.
- **Body size limit**: Responses larger than `maxBodySizeBytes` (default 10KB) are skipped for deep inspection. Stats are still recorded.
- **MurmurHash3**: Schema fingerprinting uses a fast non-cryptographic hash — no regex, no DOM walking in the hot path.

---

## 🧪 Testing

GhostDeploy ships with a comprehensive test suite targeting **≥95% line coverage** and **≥90% branch coverage**, enforced by JaCoCo at build time.

Tests cover:
- All anomaly detection types with happy paths and edge cases
- Debouncing behavior and alert suppression
- GZIP decompression with byte limit protection
- LRU endpoint and field eviction
- TTL-based sweeper cleanup
- Schema fingerprint determinism and sensitivity
- Dynamic key masking (`/users/123/orders` → `/users/*/orders`)
- Non-JSON, empty, and oversized response handling
- Interceptor thread safety and payload copying
- Contract snapshot correctness

Run the full suite with:
```bash
mvn clean verify
```

A coverage report is generated at `target/site/jacoco/index.html`.

---

## 🛠 Roadmap

- [ ] **UI Dashboard** — Visual representation of learned contracts and drift history
- [ ] **External Storage** — Redis and PostgreSQL backends for distributed deployments  
- [ ] **Multi-Service Correlation** — Track contract changes across service boundaries  
- [ ] **Webhook Alerts** — Push anomaly events to Slack, PagerDuty, or custom webhooks  
- [ ] **WebFlux Support** — Reactive stack compatibility for `Mono`/`Flux` responses  
- [ ] **OpenAPI Export** — Generate an OpenAPI spec from the learned contract  

---

## 🤝 Contributing

Contributions are very welcome. Here's how to get started:

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Write tests for your change — coverage must stay above 95%
4. Commit with a clear message: `git commit -m 'feat: describe your change'`
5. Open a pull request

Please keep the production-grade quality bar high. Advanced detection logic, edge case handling, and careful memory management are what make GhostDeploy different from a simple request logger.

---

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.

---

*Built by [antonnie](https://github.com/ajithantonnie). Questions? Reach out at support@antonnie.dev*
