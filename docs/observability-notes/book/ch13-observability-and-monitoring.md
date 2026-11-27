# Ch 13: Observability and monitoring

Started: 2026-11-26 · Finished:

## Basic concepts (primer, before reading)
<!-- Not from the book: a map to hang the sections on. Correct it while reading. -->

- **Monitoring vs observability**: monitoring checks for failures I predicted (a dashboard, an alert on a known threshold). Observability is the property that lets me ask a new question about the system from its outputs alone, without shipping new code.
- **Telemetry** is the data an application emits about itself. Three kinds, each answering a different question:
  - **Logs**: what happened, as discrete events with a timestamp. Detailed, expensive to store and search. (13.1)
  - **Metrics**: how much and how often, as numbers sampled over time. Cheap, good for alerts and trends, but no detail about a single request. (13.3)
  - **Traces**: where the time went for one request, as a tree of spans across services. (13.4)
- **Health** is a fourth, simpler signal: a yes/no answer for the platform. **Liveness** = restart me if this fails; **readiness** = stop sending me traffic if this fails. The platform's probe reads the Actuator response and acts on it directly: no collector, no storage, no Grafana. (13.2)
- **The three telemetry signals share one pipeline** (health does not): the app *exposes* it → something *collects* it → a backend *stores* it → Grafana *queries* it.

  | Signal | App side | Collector | Storage | Query |
  |---|---|---|---|---|
  | Logs | Logback to stdout | Fluent Bit | Loki | Grafana (LogQL) |
  | Metrics | Actuator + Micrometer | Prometheus scrapes | Prometheus | Grafana (PromQL) |
  | Traces | OpenTelemetry | OTLP export | Tempo | Grafana |

- **Push vs pull**: logs and traces are pushed out of the app; Prometheus pulls metrics by scraping an HTTP endpoint (`/actuator/prometheus`) on an interval. So a metric is only as fresh as the last scrape.
- **Spring Boot Actuator** is the app-side entry point for all of this: HTTP endpoints for health, metrics, and management info (13.2, 13.3, 13.5).
- **Micrometer** is to metrics what SLF4J is to logging: a vendor-neutral facade. Code records a counter, gauge, or timer; a registry translates it into Prometheus' format.
- **Metric types**: a **counter** only goes up (requests served), a **gauge** goes up and down (queue size right now), a **timer** records count and duration (latency).
- **Labels (tags)** split one metric into several series, such as `status="UNKNOWN"`. Every distinct label value is a separate time series, so labels must have few possible values: never a user ID or order ID.
- **Correlation** ties the signals together: a trace ID written into every log line lets me jump from a slow trace to its logs and back. A metric carries no trace ID of its own: getting from an alert on a metric to an example trace needs an **exemplar**, a trace ID attached to a metric sample.

## Running notes
<!-- One heading per book section, filled in while reading -->
<!-- plain = idea in my own words (p. xxx) · ? question · ! surprise · → to try -->

### 13.1 Logging with Spring Boot, Loki, and Fluent Bit
-

### 13.2 Health probes with Spring Boot Actuator and Kubernetes
-

### 13.3 Metrics and monitoring with Spring Boot Actuator, Prometheus, and Grafana
-

### 13.4 Distributed tracing with OpenTelemetry and Tempo
-

### 13.5 Application management and monitoring with Spring Boot Actuator
-

## Code and config worth keeping
<!-- Only snippets I'd reuse; note the page number -->

## Wrap-up (after finishing)

**In three sentences:**

**Open questions** (collected from the ? lines):
-

**To try** (collected from the → lines):
- [ ]

**Connections** to other chapters or to this project:
-
