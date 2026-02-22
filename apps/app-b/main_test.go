package main

import (
	"context"
	"sync/atomic"
	"testing"

	pb "app-b/gen"
)

// resetState clears all counters and caches between tests.
func resetState() {
	atomic.StoreInt64(&requestsReceivedTotal, 0)
	atomic.StoreInt64(&requestsStartedTotal, 0)
	atomic.StoreInt64(&requestsCompletedTotal, 0)
	atomic.StoreInt64(&requestsFailedTotal, 0)
	atomic.StoreInt64(&requestsTotal, 0)
	atomic.StoreInt32(&busy, 0)
	seenRequests.Range(func(k, v any) bool {
		seenRequests.Delete(k)
		return true
	})
	failRate = 0
	delayMS = 0
}

func assertCounter(t *testing.T, name string, got, want int64) {
	t.Helper()
	if got != want {
		t.Errorf("%s: got %d, want %d", name, got, want)
	}
}

// TestNormalSuccess verifies that a successful request increments
// received, started, and completed — but not failed.
func TestNormalSuccess(t *testing.T) {
	resetState()
	s := &server{}

	_, err := s.Work(context.Background(), &pb.WorkRequest{Id: "req-1"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	assertCounter(t, "received", atomic.LoadInt64(&requestsReceivedTotal), 1)
	assertCounter(t, "started", atomic.LoadInt64(&requestsStartedTotal), 1)
	assertCounter(t, "completed", atomic.LoadInt64(&requestsCompletedTotal), 1)
	assertCounter(t, "failed", atomic.LoadInt64(&requestsFailedTotal), 0)
}

// TestFailInjection verifies that an injected failure increments received and
// failed — but not started or completed (exits before mutex).
func TestFailInjection(t *testing.T) {
	resetState()
	failRate = 1.0 // guarantee every request is injected
	s := &server{}

	_, err := s.Work(context.Background(), &pb.WorkRequest{Id: "req-fail"})
	if err == nil {
		t.Fatal("expected error from fail injection, got nil")
	}

	assertCounter(t, "received", atomic.LoadInt64(&requestsReceivedTotal), 1)
	assertCounter(t, "started", atomic.LoadInt64(&requestsStartedTotal), 0)
	assertCounter(t, "completed", atomic.LoadInt64(&requestsCompletedTotal), 0)
	assertCounter(t, "failed", atomic.LoadInt64(&requestsFailedTotal), 1)
}

// TestCacheHit verifies that a cache hit increments received (both calls) but
// does not increment started or completed for the second (cache-hit) call.
func TestCacheHit(t *testing.T) {
	resetState()
	s := &server{}
	req := &pb.WorkRequest{Id: "req-cached"}

	// First call: normal success, populates cache.
	if _, err := s.Work(context.Background(), req); err != nil {
		t.Fatalf("first call: unexpected error: %v", err)
	}

	// Second call: same ID → cache hit, early return before mutex.
	if _, err := s.Work(context.Background(), req); err != nil {
		t.Fatalf("second call (cache hit): unexpected error: %v", err)
	}

	assertCounter(t, "received", atomic.LoadInt64(&requestsReceivedTotal), 2)
	assertCounter(t, "started", atomic.LoadInt64(&requestsStartedTotal), 1)   // only first call
	assertCounter(t, "completed", atomic.LoadInt64(&requestsCompletedTotal), 1) // only first call
	assertCounter(t, "failed", atomic.LoadInt64(&requestsFailedTotal), 0)
}

// TestMetricRelationships verifies the invariants hold over many requests
// with probabilistic fail injection (empty ID = no caching).
func TestMetricRelationships(t *testing.T) {
	resetState()
	failRate = 0.5
	s := &server{}

	const n = 200
	for i := 0; i < n; i++ {
		s.Work(context.Background(), &pb.WorkRequest{}) // empty ID: no caching
	}

	recv := atomic.LoadInt64(&requestsReceivedTotal)
	started := atomic.LoadInt64(&requestsStartedTotal)
	completed := atomic.LoadInt64(&requestsCompletedTotal)
	failed := atomic.LoadInt64(&requestsFailedTotal)

	if recv != n {
		t.Errorf("received=%d, want %d", recv, n)
	}
	if recv < started+failed {
		t.Errorf("invariant violated: received(%d) < started(%d) + failed(%d)", recv, started, failed)
	}
	if started < completed {
		t.Errorf("invariant violated: started(%d) < completed(%d)", started, completed)
	}
}
