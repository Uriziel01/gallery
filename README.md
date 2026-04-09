# Google AI Edge Gallery - OpenAI-Compatible Server Extension

This fork adds an **OpenAI-compatible HTTP server endpoint** to Google AI Edge Gallery, enabling the app to serve as a local inference server for external clients.

## What's New

This implementation adds the ability to run an on-device OpenAI-compatible API server directly on your Android device, allowing external applications to interact with locally-running LLM models through standard OpenAI API patterns.

### Key Features

- **HTTP Server**: NanoHTTPD-based foreground service running on a configurable port
- **OpenAI API Endpoints**:
  - `GET /v1/models` - List available models
  - `POST /v1/chat/completions` - Chat completions (streaming & non-streaming)
  - `POST /v1/responses` - OpenAI Responses API
  - `GET /health` - Health check endpoint
- **Bearer Token Authentication**: Configurable API key protection
- **100% On-Device**: All inference happens locally on the device

## Purpose

This extension enables:

1. **Phone as a Server**: Use your Android device as a local AI inference server
2. **External Integrations**: Connect external tools, scripts, or applications to on-device models
3. **Development & Testing**: Test OpenAI-compatible applications against local models
4. **Privacy-First AI**: All data stays on-device - no cloud dependencies

## Architecture

```
External Client (curl/SDK)
        ↓
NanoHTTPD HTTP Server (Foreground Service)
        ↓
OpenAI Gateway (Request/Response Translation)
        ↓
LiteRT-LM (On-device inference)
```

## Configuration

The server can be configured via the app settings:
- **Port**: Custom port (default: 8080)
- **Bearer Token**: API key authentication

## API Examples

### List Models
```bash
curl -H "Authorization: Bearer YOUR_TOKEN" http://localhost:8080/v1/models
```

### Chat Completion
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"model": "gemma-3-1b-it", "messages": [{"role": "user", "content": "Hello!"}]}'
```

### Streaming
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"model": "gemma-3-1b-it", "messages": [{"role": "user", "content": "Count to 5"}], "stream": true}'
```

## Documentation

See the `docs/` directory for detailed specifications:
- `openai-phone-endpoint-spec.md` - API specification
- `openai-phone-endpoint-guide.md` - Usage guide
- `openai-phone-endpoint-plan.md` - Implementation plan
- `openai-phone-endpoint-orchestrator-prompt.md` - Orchestrator configuration

## Building

This is a fork of the main Google AI Edge Gallery project. See [DEVELOPMENT.md](DEVELOPMENT.md) for build instructions.

## License

Apache License 2.0 - same as the main project.