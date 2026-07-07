# Testcontainers Standards

## Containers
Use reusable containers when possible.

## Networking
Never use fixed ports.

Always use:
- getMappedPort()
- dynamic properties

## Redis
Prefer:
- KeyDB
- Redis OSS

Do not use:
- embedded redis

## Lifecycle
Prefer:
- static shared containers
- singleton container patterns

## Waiting
Use:
- Wait.forListeningPort()
- Wait.forHealthcheck()

Never use:
- Thread.sleep()

## CI
Containers must run correctly in:
- GitHub Actions
- GitLab CI
- Kubernetes runners