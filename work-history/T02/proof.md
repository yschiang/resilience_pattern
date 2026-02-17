## T02 DoD Proof Outputs

### Proof 1: Build app-a Docker image
```bash
$ docker build -t app-a:dev -f apps/app-a/Dockerfile .
```
```
#14 32.68 [INFO] BUILD SUCCESS
#14 32.68 [INFO] Total time:  31.148 s
#16 writing image sha256:e96922c0932973ca5ebb1c32d2093821b1b23ddb8a999c27222144f17ea8b959
#16 naming to docker.io/library/app-a:dev done
```
✓ PASS: app-a:dev image built successfully

### Proof 2: Run app-a container
```bash
$ docker run -d -p 8083:8080 -e B_SERVICE_URL=host.docker.internal:50051 --name test-a app-a:dev
$ sleep 5
```
```
515ec84eaba33762ae90865273bfa23ce47ff0284d96b4f5fc273d1cf2b66509
```

Container logs:
```
2026-02-15T04:29:50.144Z  INFO 1 --- [           main] com.demo.appa.Application : Starting Application
2026-02-15T04:29:53.890Z  INFO 1 --- [           main] com.demo.appa.BClient     : Initializing gRPC channel to B service: host.docker.internal:50051
2026-02-15T04:29:54.887Z  INFO 1 --- [           main] o.s.b.w.embedded.tomcat.TomcatWebServer : Tomcat started on port 8080 (http)
2026-02-15T04:29:55.008Z  INFO 1 --- [           main] com.demo.appa.Application : Started Application in 5.697 seconds
```
✓ PASS: Container started successfully

### Proof 3: Test /api/work endpoint
```bash
$ curl -s http://localhost:8083/api/work | jq .
```
```json
{
  "ok": true,
  "code": "SUCCESS",
  "latencyMs": 341
}
```
✓ PASS: JSON contains required fields: ok, code, latencyMs

### Proof 4: Test /actuator/prometheus endpoint
```bash
$ curl -s http://localhost:8083/actuator/prometheus | head -10
```
```
# HELP executor_queue_remaining_tasks The number of additional elements that this queue can ideally accept without blocking
# TYPE executor_queue_remaining_tasks gauge
executor_queue_remaining_tasks{name="applicationTaskExecutor",} 2.147483647E9
# HELP jvm_gc_overhead_percent An approximation of the percent of CPU time used by GC activities
# TYPE jvm_gc_overhead_percent gauge
jvm_gc_overhead_percent 9.424753323748641E-5
# HELP jvm_gc_live_data_size_bytes Size of long-lived heap memory pool after reclamation
# TYPE jvm_gc_live_data_size_bytes gauge
jvm_gc_live_data_size_bytes 0.0
```
✓ PASS: Prometheus metrics format output

## Implementation Details

- **Framework**: Spring Boot 3.2.1, Java 17
- **HTTP port**: 8080
- **Endpoints**: GET /api/work, GET /actuator/prometheus
- **Env var**: B_SERVICE_URL (default: localhost:50051)
- **Dependencies**: gRPC Java, Micrometer Prometheus

## All DoD Proofs Passed ✓

Commit: c246009
