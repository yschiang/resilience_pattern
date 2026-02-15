package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	pb "app-b/gen"

	"google.golang.org/grpc"
	"google.golang.org/grpc/health"
	"google.golang.org/grpc/health/grpc_health_v1"
)

var (
	requestsTotal int64
	busy          int32
	workerMutex   sync.Mutex // Enforces single-thread concurrency
	delayMS       int
)

type server struct {
	pb.UnimplementedDemoServiceServer
}

func (s *server) Work(ctx context.Context, req *pb.WorkRequest) (*pb.WorkReply, error) {
	// Enforce single-thread processing
	workerMutex.Lock()
	defer workerMutex.Unlock()

	atomic.StoreInt32(&busy, 1)
	defer atomic.StoreInt32(&busy, 0)

	atomic.AddInt64(&requestsTotal, 1)

	start := time.Now()

	// Simulate work with configurable delay
	time.Sleep(time.Duration(delayMS) * time.Millisecond)

	latency := time.Since(start).Milliseconds()

	return &pb.WorkReply{
		Ok:        true,
		Code:      "SUCCESS",
		LatencyMs: latency,
	}, nil
}

func metricsHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")

	fmt.Fprintf(w, "# HELP b_busy Whether worker is currently busy (0 or 1)\n")
	fmt.Fprintf(w, "# TYPE b_busy gauge\n")
	fmt.Fprintf(w, "b_busy %d\n", atomic.LoadInt32(&busy))

	fmt.Fprintf(w, "# HELP b_requests_total Total number of requests processed\n")
	fmt.Fprintf(w, "# TYPE b_requests_total counter\n")
	fmt.Fprintf(w, "b_requests_total %d\n", atomic.LoadInt64(&requestsTotal))
}

func main() {
	// Read B_DELAY_MS from environment
	delayMS = 5 // default
	if envDelay := os.Getenv("B_DELAY_MS"); envDelay != "" {
		if parsed, err := strconv.Atoi(envDelay); err == nil {
			delayMS = parsed
		}
	}

	log.Printf("Starting app-b with B_DELAY_MS=%d", delayMS)

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
