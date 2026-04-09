# OpenAI-Compatible Phone Endpoint v1 Spec

This document freezes the v1 contract for the phone-hosted OpenAI-compatible endpoint.

## Purpose

Expose a minimal local inference API that OpenAI client libraries can talk to without translation layers.

## Supported Surface

Only these OpenAI-style routes are in scope for v1:

- `GET /v1/models`
- `POST /v1/chat/completions`

The endpoint serves exactly one active local model at a time. `GET /v1/models` exists only to report that selected model.

Any other OpenAI route is out of scope for v1 and must return `404 Not Found`.

## Core v1 Rules

- Text-only chat completions.
- One active request at a time, serialized internally.
- `stream=true` uses SSE and emits OpenAI-style delta chunks.
- Basic bearer-token auth is required on every request.
- The implementation may use a small embedded HTTP server such as NanoHTTPD, but the transport choice is not part of the API contract.

## Authentication

Requests must include:

- `Authorization: Bearer <token>`

Missing or invalid credentials return `401 Unauthorized` with an OpenAI-shaped error body.

Authentication applies to every route, including `GET /v1/models`.

## `POST /v1/chat/completions`

### Required request shape

- `model`: must match the single active local model name
- `messages`: array of chat messages

### Accepted request fields

- `model`
- `messages`
- `stream`

Any other top-level field is unsupported in v1 and must be rejected with `400 Bad Request`.

### Supported message content

- `role`: `system`, `user`, `assistant`
- `content`: text only

No image, audio, tool, or multi-part message content is supported.

Each message `content` value must be a JSON string. Null content, structured content parts, or non-string content must be rejected with `400 Bad Request`.

At least one message must be present.

### Streaming

- `stream: false` or omitted returns one normal JSON completion response.
- `stream: true` returns `text/event-stream`.
- Each chunk must follow OpenAI chat-completions delta semantics.
- The stream ends with `data: [DONE]`.

### Non-streaming response

Return an OpenAI-shaped JSON object with:

- `id`
- `object: "chat.completion"`
- `created`
- `model`
- `choices[0].index`
- `choices[0].message`
- `choices[0].finish_reason`

`usage` may be omitted in v1 unless it is easy to compute accurately.

The response contains exactly one choice in v1.

### Streaming response shape

Each SSE event payload must be an OpenAI-style chat completion chunk with:

- `id`
- `object: "chat.completion.chunk"`
- `created`
- `model`
- `choices[0].index`
- `choices[0].delta`
- `choices[0].finish_reason`

The first chunk may establish the assistant role in `choices[0].delta.role`. Content deltas must be emitted in `choices[0].delta.content`. The terminal chunk must set `choices[0].finish_reason` and then the stream must end with `data: [DONE]`.

## `GET /v1/models`

Return one OpenAI-shaped model list containing the single active model only.

The response shape is:

- `object: "list"`
- `data`: array with one model entry

The model entry must include:

- `id`
- `object: "model"`
- `created`
- `owned_by`

## Errors

Use standard OpenAI-style error envelopes for:

- `400 Bad Request` for invalid JSON, unsupported fields, or invalid message content
- `401 Unauthorized` for missing or invalid bearer token
- `404 Not Found` for unsupported routes
- `409 Conflict` when another inference request is already active
- `500 Internal Server Error` for unexpected failures

Error bodies must use the OpenAI error envelope shape:

- `error.message`
- `error.type`
- `error.code`

Validation failures should use a stable client-facing message and a non-empty `error.type`.

## Unsupported in v1

These are explicitly out of scope and must be rejected or omitted:

- Embeddings
- Images
- Audio
- Tool/function calling
- Multi-model routing
- Concurrent request execution
- Remote model download via the endpoint
- Audio or image input inside chat messages
- Structured content parts in `messages[*].content`
- Request tuning fields such as `temperature`, `top_p`, `n`, `stop`, `max_tokens`, `response_format`, `tools`, and `tool_choice`
- Fine-tuning APIs
- Batch APIs
- Assistants/threads/runs APIs
- Responses API

## Stability Note

This spec is frozen for the first implementation pass. Any expansion should be treated as a new versioned contract, not an implicit extension of v1.
