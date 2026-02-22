// Service B: Intentionally single-threaded gRPC backend for resilience pattern demo.
//
// LEARNING: Why single-threaded?
// - Demonstrates saturation and queueing behavior under overload
// - ~5 RPS per pod capacity (3 pods = 15 RPS total) vs 200 QPS load = 13× overload
// - Makes it easy to observe: queue buildup, tail latency, retry amplification, CB shedding
//
// Key patterns demonstrated:
// - FAIL_RATE: Configurable retryable error injection (RESOURCE_EXHAUSTED)
// - Idempotency: sync.Map cache with TTL prevents duplicate work on retry
// - Observability: Flow counters + latency histograms with outcome labels
// - Mutex worker: Single-threaded processing (intentional bottleneck)
//
// Metrics exposed on :8080/metrics:
// - Flow counters: received/started/completed/failed (show queue depth)
// - Latency histograms: request/processing/queue_wait (preserve tail latency)
// - Busy gauge: worker utilization (1=saturated, 0=idle)
package main

import (
	"context"
	"fmt"
	"log"
	"math/rand"
	"net"
	"net/http"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	pb "app-b/gen"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/health"
	"google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/status"
)

func init() {
	prometheus.MustRegister(requestLatencyHist)
	prometheus.MustRegister(processingLatencyHist)
	prometheus.MustRegister(queueWaitHist)
}

type cachedReply struct {
	reply   *pb.WorkReply
	expires time.Time
}

var (
	requestsTotal         int64
	busy                  int32
	workerMutex           sync.Mutex // Enforces single-thread concurrency
	delayMS               int
	failRate              float64  // from FAIL_RATE env var (0.0–1.0)
	seenRequests          sync.Map // map[string]cachedReply for idempotency

	// Workstream B observability counters
	requestsReceivedTotal  int64 // b_requests_received_total: all incoming requests
	requestsStartedTotal   int64 // b_requests_started_total: entered mutex-protected worker
	requestsCompletedTotal int64 // b_requests_completed_total: successful completions
	requestsFailedTotal    int64 // b_requests_failed_total{reason="fail_injection"}

	// LEARNING: Latency histograms with outcome labels preserve tail latency visibility.
	// Why three separate histograms?
	// - request_latency: End-to-end (includes queue wait + processing + cache hits)
	// - processing_latency: Only mutex-protected work (pure service time)
	// - queue_wait: Time waiting for mutex (shows saturation backlog)
	//
	// Why outcome labels?
	// - Without labels, fast-fail paths (cache_hit, failure) wash out slow success tail latency
	// - With labels, p99 success latency remains visible even when 50% traffic is cache hits
	//
	// Bucket design (1ms - 5000ms):
	// - Lower buckets (1-50ms): Capture fast-path (cache hits, fail injection, normal processing)
	// - Upper buckets (100-5000ms): Capture saturation (queue buildup under overload)
	//
	// Registered with prometheus default registry
	requestLatencyHist = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "b_request_latency_ms",
			Help:    "End-to-end handler latency in milliseconds (all outcomes: cache_hit, failure, success)",
			Buckets: []float64{1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000},
		},
		[]string{"outcome"},
	)

	processingLatencyHist = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "b_processing_latency_ms",
			Help:    "Worker processing latency in milliseconds (mutex-protected section only)",
			Buckets: []float64{1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000},
		},
		[]string{"outcome"},
	)

	queueWaitHist = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "b_queue_wait_ms",
			Help:    "Queue wait time in milliseconds (handler entry to mutex acquisition)",
			Buckets: []float64{0.5, 1, 2, 5, 10, 25, 50, 100, 250, 500, 1000},
		},
		[]string{"outcome"},
	)
)

type server struct {
	pb.UnimplementedDemoServiceServer
}

func (s *server) Work(ctx context.Context, req *pb.WorkRequest) (*pb.WorkReply, error) {
	// LEARNING: Defer-based latency measurement exploits Go's LIFO execution order.
	// First defer (declared here) executes LAST, capturing end-to-end duration including:
	// - Cache hit path (early return, no mutex)
	// - Fail injection path (early return, no mutex)
	// - Full processing path (queue wait + mutex-protected work)
	//
	// Why capture outcome by reference? Early returns can override it (cache_hit, failure).
	handlerStart := time.Now()
	outcome := "success"
	defer func() {
		requestLatencyHist.WithLabelValues(outcome).Observe(float64(time.Since(handlerStart).Milliseconds()))
	}()

	// Count every request entering the handler (cache hits, fail injection, normal)
	atomic.AddInt64(&requestsReceivedTotal, 1)

	// LEARNING: Idempotency cache prevents duplicate work on retry.
	// Client sends same requestId on retry → we return cached reply instantly.
	// This is CRITICAL for Scenario 2: without it, retry would amplify load 3×.
	// With it, retry attempts are free (cache hit, no mutex wait).
	if req.GetId() != "" {
		if cached, ok := seenRequests.Load(req.GetId()); ok {
			if cached.(cachedReply).expires.After(time.Now()) {
				outcome = "cache_hit"
				return cached.(cachedReply).reply, nil
			}
		}
	}

	// LEARNING: Retryable failure injection (RESOURCE_EXHAUSTED).
	// Why this status code? It's classified as retryable by App-A's GrpcErrorClassifier.
	// Scenario 2: 30% fail rate → client retries → visible errors drop to ~3% (0.3³).
	// Injected BEFORE mutex acquisition so failures don't consume worker capacity.
	if failRate > 0 && rand.Float64() < failRate {
		// Count injected failures before early return (no mutex acquired)
		atomic.AddInt64(&requestsFailedTotal, 1)
		outcome = "failure"
		return nil, status.Errorf(codes.ResourceExhausted, "rate limited")
	}

	// LEARNING: Queue wait measurement starts here, ends after mutex acquisition.
	// Under overload (200 QPS → 15 RPS capacity), queue wait dominates latency:
	// - Normal: queue_wait ~0ms, processing ~5ms
	// - Saturated: queue_wait 500ms+, processing still ~5ms
	queueStart := time.Now()

	// LEARNING: Single-thread mutex enforces ~5 RPS capacity per pod.
	// This is the INTENTIONAL BOTTLENECK that demonstrates saturation behavior.
	// Without this, demo wouldn't show queue buildup, tail latency, or CB trip.
	workerMutex.Lock()
	defer workerMutex.Unlock()

	// Observe queue wait immediately after acquiring mutex
	queueWaitHist.WithLabelValues("acquired").Observe(float64(time.Since(queueStart).Milliseconds()))

	// LEARNING: Processing latency defer placed AFTER mutex Lock.
	// Go's LIFO defer order means this executes BEFORE Unlock (captures mutex-held time only).
	// This isolates pure service time from queue wait, enabling us to prove:
	//   request_latency = queue_wait + processing_latency
	processingStart := time.Now()
	processingOutcome := "success"
	defer func() {
		processingLatencyHist.WithLabelValues(processingOutcome).Observe(float64(time.Since(processingStart).Milliseconds()))
	}()

	// Count requests that entered the single-thread worker (after mutex acquired)
	atomic.AddInt64(&requestsStartedTotal, 1)

	atomic.StoreInt32(&busy, 1)
	defer atomic.StoreInt32(&busy, 0)

	atomic.AddInt64(&requestsTotal, 1)

	start := time.Now()

	// Simulate work with configurable delay
	time.Sleep(time.Duration(delayMS) * time.Millisecond)

	latency := time.Since(start).Milliseconds()

	reply := &pb.WorkReply{
		Ok:        true,
		Code:      "SUCCESS",
		LatencyMs: latency,
	}
	// Cache reply for idempotency
	if req.GetId() != "" {
		seenRequests.Store(req.GetId(), cachedReply{
			reply:   reply,
			expires: time.Now().Add(30 * time.Second),
		})
	}
	// Count only work completed inside the mutex-protected section
	atomic.AddInt64(&requestsCompletedTotal, 1)
	// outcome and processingOutcome remain "success" (defaults set above)
	return reply, nil
}

// LEARNING: Metrics handler combines hand-rolled counters + promhttp histograms.
// Why not use prometheus client for everything?
// - Flow counters (received/started/completed/failed) need atomic increments in hot path
// - Using prometheus.NewCounter would add registry lock contention
// - Hand-rolled atomic.AddInt64 is lock-free and faster
//
// Why append promhttp output?
// - Histograms (requestLatencyHist, etc.) are already registered with prometheus
// - promhttp.HandlerFor() renders them in standard exposition format
// - Single /metrics endpoint exposes both: hand-rolled counters + histogram buckets
func metricsHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")

	fmt.Fprintf(w, "# HELP b_busy Whether worker is currently busy (0 or 1)\n")
	fmt.Fprintf(w, "# TYPE b_busy gauge\n")
	fmt.Fprintf(w, "b_busy %d\n", atomic.LoadInt32(&busy))

	fmt.Fprintf(w, "# HELP b_requests_total Total number of requests processed\n")
	fmt.Fprintf(w, "# TYPE b_requests_total counter\n")
	fmt.Fprintf(w, "b_requests_total %d\n", atomic.LoadInt64(&requestsTotal))

	fmt.Fprintf(w, "# HELP b_fail_rate Configured failure injection rate\n")
	fmt.Fprintf(w, "# TYPE b_fail_rate gauge\n")
	fmt.Fprintf(w, "b_fail_rate %.2f\n", failRate)

	fmt.Fprintf(w, "# HELP b_requests_received_total All requests entering the Work() handler\n")
	fmt.Fprintf(w, "# TYPE b_requests_received_total counter\n")
	fmt.Fprintf(w, "b_requests_received_total %d\n", atomic.LoadInt64(&requestsReceivedTotal))

	fmt.Fprintf(w, "# HELP b_requests_started_total Requests that acquired the worker mutex\n")
	fmt.Fprintf(w, "# TYPE b_requests_started_total counter\n")
	fmt.Fprintf(w, "b_requests_started_total %d\n", atomic.LoadInt64(&requestsStartedTotal))

	fmt.Fprintf(w, "# HELP b_requests_completed_total Successful completions inside mutex-protected worker\n")
	fmt.Fprintf(w, "# TYPE b_requests_completed_total counter\n")
	fmt.Fprintf(w, "b_requests_completed_total %d\n", atomic.LoadInt64(&requestsCompletedTotal))

	fmt.Fprintf(w, "# HELP b_requests_failed_total Requests failed by reason\n")
	fmt.Fprintf(w, "# TYPE b_requests_failed_total counter\n")
	fmt.Fprintf(w, "b_requests_failed_total{reason=\"fail_injection\"} %d\n", atomic.LoadInt64(&requestsFailedTotal))

	// Latency histograms are registered with the prometheus default registry;
	// append their output after the hand-rolled metrics above.
	promhttp.HandlerFor(prometheus.DefaultGatherer, promhttp.HandlerOpts{}).ServeHTTP(w, r)
}

func main() {
	// LEARNING: Configuration via environment variables enables per-scenario tuning.
	// B_DELAY_MS: Processing time per request (default 5ms)
	//   - Scenario 1-2: 5ms (fast B, capacity = 200 RPS/pod)
	//   - Scenario 3: 200ms (slow B, capacity = 5 RPS/pod → demonstrates saturation)
	// FAIL_RATE: Retryable error injection rate (default 0.0)
	//   - Scenario 1-2: 0.3 (30% errors → demonstrates retry effectiveness)
	//   - Scenario 3-4: 0.3 (combined with slow B → demonstrates CB trip)
	delayMS = 5 // default
	if envDelay := os.Getenv("B_DELAY_MS"); envDelay != "" {
		if parsed, err := strconv.Atoi(envDelay); err == nil {
			delayMS = parsed
		}
	}

	if envRate := os.Getenv("FAIL_RATE"); envRate != "" {
		if parsed, err := strconv.ParseFloat(envRate, 64); err == nil {
			failRate = parsed
		}
	}

	log.Printf("Starting app-b with B_DELAY_MS=%d FAIL_RATE=%.2f", delayMS, failRate)

	// LEARNING: Cleanup goroutine prevents idempotency cache from growing unbounded.
	// Why needed? Cache entries have 30s TTL but aren't auto-evicted on expiry.
	// Without cleanup, memory usage grows linearly with request rate.
	// This goroutine scans every 30s and deletes expired entries.
	go func() {
		for range time.Tick(30 * time.Second) {
			seenRequests.Range(func(k, v any) bool {
				if v.(cachedReply).expires.Before(time.Now()) {
					seenRequests.Delete(k)
				}
				return true
			})
		}
	}()

	// Start metrics HTTP server
	go func() {
		http.HandleFunc("/metrics", metricsHandler)
		log.Printf("Metrics server listening on :8080")
		if err := http.ListenAndServe(":8080", nil); err != nil {
			log.Fatalf("Failed to start metrics server: %v", err)
		}
	}()

	// Start gRPC server
	lis, err := net.Listen("tcp", ":50051")
	if err != nil {
		log.Fatalf("Failed to listen on port 50051: %v", err)
	}

	grpcServer := grpc.NewServer()
	pb.RegisterDemoServiceServer(grpcServer, &server{})

	// Register health check
	healthServer := health.NewServer()
	healthServer.SetServingStatus("", grpc_health_v1.HealthCheckResponse_SERVING)
	grpc_health_v1.RegisterHealthServer(grpcServer, healthServer)

	log.Printf("gRPC server listening on :50051")
	if err := grpcServer.Serve(lis); err != nil {
		log.Fatalf("Failed to serve gRPC: %v", err)
	}
}
