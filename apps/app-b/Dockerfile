# Multi-stage build for app-b (Go implementation for faster P0)
FROM golang:1.21-alpine AS builder

# Install protoc and plugins
RUN apk add --no-cache protobuf-dev && \
    go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.31 && \
    go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.3

WORKDIR /build/app

# Copy proto files and generate code into app's gen directory
COPY proto/ /build/proto/
RUN mkdir -p /build/app/gen && \
    protoc --go_out=/build/app/gen --go_opt=paths=source_relative \
           --go-grpc_out=/build/app/gen --go-grpc_opt=paths=source_relative \
           -I/build/proto /build/proto/demo.proto

# Copy go.mod and source
COPY apps/app-b/go.mod ./
COPY apps/app-b/*.go ./

# Download dependencies (go mod tidy to update go.sum) and build
RUN go mod tidy && \
    go mod download && \
    CGO_ENABLED=0 GOOS=linux go build -o /app-b .

# Runtime image
FROM alpine:3.19

RUN apk add --no-cache ca-certificates curl

# Copy built binary
COPY --from=builder /app-b /usr/local/bin/app-b

# Expose ports
EXPOSE 50051 8080

# Set default environment variables
ENV B_DELAY_MS=5

WORKDIR /app

# Run the service
CMD ["/usr/local/bin/app-b"]
