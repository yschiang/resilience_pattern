## T06 DoD Proof Outputs

### Proof 1: Build app-b Docker image
```bash
$ docker build -t app-b:dev -f apps/app-b/Dockerfile .
```
```
#18 exporting to image
#18 exporting layers 0.0s done
#18 writing image sha256:a91054c940bc... done
#18 naming to docker.io/library/app-b:dev done
#18 DONE 0.1s
```
✓ PASS: app-b:dev image built successfully

### Proof 2: Run container with B_DELAY_MS=100
```bash
$ docker run -d -p 50052:50051 -p 8081:8080 -e B_DELAY_MS=100 --name test-b app-b:dev
$ sleep 3
```
```
fdcce1f10f6373f2dacd6f67cfb211f437192b94db927962c569927e3dc98b6b
```
✓ PASS: Container started successfully

### Proof 3: Check metrics endpoint
```bash
$ curl -s http://localhost:8081/metrics | grep -E "^b_busy|^b_requests_total"
```
```
b_busy 0
b_requests_total 0
```

Full metrics output:
```
# HELP b_busy Whether worker is currently busy (0 or 1)
# TYPE b_busy gauge
b_busy 0
# HELP b_requests_total Total number of requests processed
# TYPE b_requests_total counter
b_requests_total 0
```
✓ PASS: Both b_busy and b_requests_total metrics present

### Proof 4: Cleanup
```bash
$ docker rm -f test-b
```
```
test-b
```
✓ PASS: Cleanup successful

## Implementation Details

- **Language**: Go (single-thread semantics via sync.Mutex)
- **gRPC port**: 50051
- **Metrics port**: 8080
- **Env var**: B_DELAY_MS (default=5)
- **Proto**: proto/demo.proto (DemoService.Work RPC)

## All DoD Proofs Passed ✓

Commit: 1a4f607
