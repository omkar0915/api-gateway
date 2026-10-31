# API Gateway and Rate Limiter

A reactive API gateway built with Java 21, Spring Boot 4.1.1, and Spring Cloud Gateway 5.0.3 (Spring Cloud 2025.1.3). It routes `/api/**` to a configured backend, applies a Redis token bucket per client, and returns a small JSON error when the gateway itself fails.

Automated tests use WireMock. They do not call the public internet. Rate-limit tests use Testcontainers with `redis:7-alpine` when Docker is available. When Docker is not available, they start the Redis 5.0.14 binary bundled by embedded-redis and still execute the same assertions.

## Architecture

```text
Client
  |
  |  HTTP
  v
Request correlation (WebFilter)
  |
  v
Route match: Path=/api/**
  |
  +-- no match --> 404 JSON
  |
  v
RedisRateLimiter (token bucket, unless the test profile is active)
  |
  +-- denied --> 429 JSON
  |
  v
StripPrefix=1
  |
  v
Backend HTTP call (connect and response timeouts)
  |
  +-- timeout --> 504 JSON
  +-- connection failure --> 503 JSON
  |
  v
Backend response, passed through unchanged
```

The process is a Spring WebFlux application. It does not use the servlet stack. Redis is reached with Lettuce through `spring-boot-starter-data-redis-reactive`. The rate limiter is Spring Cloud Gateway's `RequestRateLimiter` filter backed by `RedisRateLimiter`, not a custom algorithm.

There is one route, `api-backend`. The rate limiter is a default filter on every profile except `test`, so routing tests can prove forwarding without Redis. The `test` profile is for the test suite, not for production.

## Request lifecycle

1. The correlation filter reads `X-Request-Id`. A value that matches `[A-Za-z0-9._-]{1,64}` is kept. Any other value is replaced with a UUID. The id is stored on the exchange, returned on the response, and sent to the backend.
2. Gateway matches `Path=/api/**`. `/unknown` never reaches Redis or the backend.
3. `ClientIdKeyResolver` chooses the bucket key.
4. `RedisRateLimiter` runs a Lua script in Redis. The script refills a token bucket and decides whether this request may spend one token. The bucket key is `request_rate_limiter.{api-backend.<client key>}`.
5. `StripPrefix=1` removes the first path segment. `GET /api/hello/world` is forwarded as `GET /hello/world`.
6. The gateway calls the backend with the configured connect and response timeouts.
7. A normal backend response, including a backend 404, is returned as the backend sent it. Gateway-generated failures use the JSON error body described below.

An access log line is written for non-actuator requests:

```text
event=gateway_request method=GET path=/api/hello status=200 durationMs=12 requestId=req-123
```

The path does not include the query string. Client ids, backend URLs, and exception messages are not written to that line.

## Rate limiting

The filter is a token bucket.

| Setting | Property | Default |
| --- | --- | --- |
| Tokens added per second | `app.gateway.rate-limit.replenish-rate` | 2 |
| Maximum tokens in the bucket | `app.gateway.rate-limit.burst-capacity` | 2 |
| Tokens consumed per request | `requestedTokens` | 1 |

`burst-capacity` must be greater than or equal to `replenish-rate`. Gateway rejects the application at startup otherwise.

With the defaults, a client can send two requests immediately. The third request in that same second receives HTTP 429. About one second later the bucket has refilled by two tokens. Refill uses Redis `TIME`, in whole seconds, so a request that crosses a second boundary can receive a new token earlier than a wall-clock wait suggests.

Allowed and denied responses include:

- `X-RateLimit-Remaining`
- `X-RateLimit-Replenish-Rate`
- `X-RateLimit-Burst-Capacity`
- `X-RateLimit-Requested-Tokens`

Restarting the gateway does not clear buckets. Flushing Redis does.

### Client identity

`ClientIdKeyResolver` chooses the bucket key in this order:

1. The `X-Client-Id` header, after trimming, when it is 1 to 128 characters and contains no control characters or spaces.
2. The remote IP address, from `InetAddress.getHostAddress()` so the gateway does not do a reverse DNS lookup.
3. The literal key `anonymous` when the request has no usable header and no remote address.

This is not authentication.

- Anyone can send a new `X-Client-Id` and receive a new bucket.
- Callers who omit the header share a bucket by source address. Localhost and NAT hide many users behind one address.
- Callers with no address share the `anonymous` bucket.
- A production gateway should derive the key from an authenticated principal. This project does not add authentication.

### Redis is unavailable

`RedisRateLimiter` fails open. If Redis cannot run the script, the gateway logs the error, allows the request, and sets `X-RateLimit-Remaining` to `-1`. The client does not receive the Redis exception. A Redis outage removes the limit until Redis answers again. Lettuce uses a 1 second connect timeout and a 1 second command timeout so that fail-open does not wait on the longer client defaults.

`/actuator/health` includes the Redis health indicator. With the default `show-details: never`, the public body is only `{"status":"UP"}` or `{"status":"DOWN"}`. Health is `DOWN` when Redis is down, even though proxied traffic is allowed.

## Errors

Gateway-generated errors use this JSON body and `Content-Type: application/json`:

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded.",
  "requestId": "req-123"
}
```

| Status | When | Message |
| --- | --- | --- |
| 404 | No route matches | No route matches the request path. |
| 429 | Token bucket denies the request | Rate limit exceeded. |
| 503 | Backend connection fails or resets | The backend is unavailable. |
| 504 | Backend exceeds the response timeout, or the connect attempt times out | The backend did not respond in time. |
| 500 | Any other gateway failure | The gateway could not complete the request. |

The body does not include exception class names, stack traces, Redis errors, or the backend address. Those details stay in the server log as the exception class and status only.

A response produced by the backend is not rewritten. If the backend returns 404 with its own body, the client receives that response.

## Observability

Actuator exposes only `health`, `info`, and `metrics`. `env`, `beans`, and heap dumps are not exposed. `info` publishes `info.app.name` and `info.app.description`.

Gateway request metrics are available at `/actuator/metrics`. The meter name contains `gateway`.

Log levels are `INFO` for this application and for Spring Cloud Gateway, and `WARN` for Reactor Netty client traffic, so request bodies and access URLs are not dumped at info.

## Configuration

Environment variables override the defaults through Spring relaxed binding.

| Environment variable | Default |
| --- | --- |
| `APP_GATEWAY_BACKEND_URI` | `https://httpbin.org` |
| `APP_GATEWAY_RATE_LIMIT_REPLENISH_RATE` | `2` |
| `APP_GATEWAY_RATE_LIMIT_BURST_CAPACITY` | `2` |
| `APP_GATEWAY_CONNECT_TIMEOUT_MS` | `2000` |
| `APP_GATEWAY_RESPONSE_TIMEOUT` | `3s` |
| `SPRING_DATA_REDIS_HOST` | `localhost` |
| `SPRING_DATA_REDIS_PORT` | `6379` |
| `SPRING_DATA_REDIS_TIMEOUT` | `1s` |

`https://httpbin.org` is the default backend for a manual demo. The test suite replaces it with WireMock.

There is no Redis password in this project. The Compose Redis port is published without authentication. Do not expose that port on a shared network.

## Docker

Docker Desktop, or another engine that provides the `docker` command, is required for Compose. It is not required to compile or to run the tests.

```bash
docker compose up --build
```

Compose starts `redis:7-alpine` and waits until `redis-cli ping` succeeds. The gateway image is a multi-stage build: JDK 21 compiles the jar, and a JRE 21 image runs it as user `gateway`. The image health check calls `/actuator/health`.

The gateway listens on port 8080 and uses the Redis service name `redis`. Stop it with:

```bash
docker compose down
```

To run from a local Maven build instead of Compose, start Redis on `localhost:6379` and then:

```bash
mvn spring-boot:run
```

## API examples

These examples assume Compose, or a local gateway on port 8080 with Redis and the default httpbin backend.

Forward a request. `StripPrefix=1` turns `/api/get` into `/get` on the backend.

```bash
curl -i -H "X-Client-Id: demo" http://localhost:8080/api/get
```

Send the same header twice more. The third call in that second returns 429:

```bash
curl -i -H "X-Client-Id: demo" http://localhost:8080/api/get
curl -i -H "X-Client-Id: demo" http://localhost:8080/api/get
```

A different client id has its own bucket:

```bash
curl -i -H "X-Client-Id: other" http://localhost:8080/api/get
```

Unknown route:

```bash
curl -i http://localhost:8080/unknown
```

Backend timeout. httpbin's `/delay/5` is slower than the 3 second response timeout:

```bash
curl -i -H "X-Client-Id: demo" http://localhost:8080/api/delay/5
```

Backend unavailable. Point the gateway at a closed port and call any `/api/**` path:

```bash
curl -i -H "X-Client-Id: demo" http://localhost:8080/api/hello
```

with `APP_GATEWAY_BACKEND_URI=http://127.0.0.1:9`.

Health:

```bash
curl -s http://localhost:8080/actuator/health
```

## Tests

```bash
mvn clean test
```

| Test | What it checks |
| --- | --- |
| `RouteForwardingTest` | `/api/hello` becomes `/hello`, `/api/hello/world` becomes `/hello/world`, and `/hello` is not forwarded |
| `ClientIdKeyResolverTest` | Header, unsafe header, and anonymous key |
| `RateLimitGatewayTest` | Allowed request, 429, a second client id, and a successful request after refill |
| `RedisFailureGatewayTest` | Redis on a closed port fails open with `X-RateLimit-Remaining: -1` |
| `GatewayErrorHandlingTest` | Unknown route JSON, passthrough of a backend 404, response timeout, connection reset |
| `GatewayObservabilityTest` | Request id forwarding, rejection of an unsafe id, health, info, metrics, and hidden actuator endpoints |
| `ApiGatewayApplicationTests` | Application context starts |
| `GatewayConfigurationTests` | Default backend URI and rate-limit settings |

The `test` profile disables the rate limiter and the Redis health indicator. Rate-limit tests do not use that profile.

On a machine without Docker, `RateLimitGatewayTest` logs `embedded Redis on 127.0.0.1:<port> because Docker is not available`. The test is not skipped. With Docker on `PATH`, the same test starts `redis:7-alpine` through Testcontainers.

## Decisions and trade-offs

- Spring Cloud Gateway's Redis token bucket is used instead of a hand-written limiter. The only custom rate-limit code is the key resolver, because the built-in resolver needs a security principal and this application has none.
- Redis failure follows Gateway's fail-open behavior. Failing closed would protect the backend during a Redis outage and would also reject all traffic when Redis is down. That would be a custom change to `RedisRateLimiter`.
- The correlation id is a `WebFilter`, not a gateway `GlobalFilter`, so requests that match no route still get an id.
- Error bodies are written without copying exception messages. Operators still get the exception class in the log.
- Actuator details stay off. Health does not list Redis host names or component diagnostics.
- The project stays on WebFlux. Adding `spring-boot-starter-web` would put a servlet stack beside the gateway.

## Limitations

- `X-Client-Id` can be spoofed. IP fallback merges users who share an address.
- Redis failure allows traffic.
- Local Redis has no password and no TLS.
- Refill resolution is one second.
- Backend responses are not converted to the gateway error JSON.
- Query strings are forwarded and are omitted from the access log, but a backend or an upstream proxy can still log them.
- The embedded Redis fallback used in tests is Redis 5.0.14 on Windows. Compose and Testcontainers use Redis 7.
