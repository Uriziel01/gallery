# OpenAI-Compatible Phone Endpoint Plan

Goal: add a lightweight OpenAI-compatible HTTP endpoint to the Android app so a PC can connect to a model running on the phone.

Scope for v1:
- `GET /v1/models`
- `POST /v1/chat/completions`
- Text-only chat completions
- SSE streaming for `stream=true`
- One active model at a time
- One active request at a time, serialized
- Basic bearer-token auth
- Foreground service lifecycle

Environment assumptions:
- Development happens from Bazzite Linux, which is an immutable/atomic Fedora-based system.
- All interactive build and test work should happen inside a Distrobox container named `gallery-openai`.
- Any step that mentions shell commands, package installs, or Android tooling should assume it runs inside that container unless the step explicitly says otherwise.

Out of scope for v1:
- Ollama compatibility
- Embeddings
- Images, audio, and tools/function calling
- Multi-model routing
- Concurrent requests
- Remote model downloading from the endpoint

Implementation order:

1. Scope lock and API contract
- Create a short spec file that freezes the supported OpenAI surface, request fields, response shapes, error codes, auth model, and unsupported features.
- Prefer a small server library such as NanoHTTPD unless a repo-specific blocker appears.
- Exit criteria: the supported API is explicit and stable enough for follow-on work.

2. Add transport dependency and build plumbing
- Update the Android app module build to include the chosen HTTP server library and any small parsing helper needed.
- Do not add server behavior yet.
- Exit criteria: the app still builds with the new dependency.

3. Extract server-facing model access
- Create a non-UI singleton or repository under a new `server/` package.
- It must resolve the selected model, access the allowlist/task graph, and initialize or clean up a model without depending on `ModelManagerViewModel`.
- This is the architectural extraction that makes the HTTP layer possible.
- Exit criteria: a service-side caller can obtain a model and ensure it is initialized.

4. Add OpenAI DTOs and mapping helpers
- Create request/response models for chat completions, models list responses, error payloads, message-role mapping, and validation helpers.
- Keep this step pure data and parsing.
- Exit criteria: DTOs serialize and deserialize correctly in unit tests.

5. Add a stateless inference gateway
- Create a gateway that translates one OpenAI chat request into one LiteRT-LM inference run.
- Serialize access with a `Mutex` or single-worker queue.
- Keep the UI chat session separate from this server path.
- Exit criteria: one request in, one completion out, with cancellation and cleanup working.

6. Scaffold the foreground service and lifecycle
- Add a new Android `Service` that owns the HTTP server and runs in the foreground.
- Add a notification channel and a minimal `/health` endpoint.
- Register the service in the manifest with the right permissions and foreground type.
- Exit criteria: the service starts and stops cleanly on device.

7. Implement `GET /v1/models`
- Return only the model or models the server can actually serve.
- For v1, keep the endpoint to one selected local model unless there is a strong reason to support more.
- Exit criteria: a PC client can fetch a valid OpenAI-shaped models response.

8. Implement non-streaming `POST /v1/chat/completions`
- Parse the request, map OpenAI messages to the internal prompt/system instruction, call the inference gateway, and return a normal JSON completion response.
- Exit criteria: a client configured against the phone gets a full completion over HTTP.

9. Implement SSE streaming for `POST /v1/chat/completions`
- Add the `stream=true` path and emit OpenAI-style delta chunks over SSE.
- End with `[DONE]`.
- Exit criteria: a streaming client receives incremental output.

10. Add config, authentication, and UI control
- Persist server enabled/disabled state, port, bearer token, and selected model in a dedicated config store.
- Add start/stop controls in the existing app flow.
- Exit criteria: the server is intentionally enabled and visibly controlled by the user.

11. Add tests and docs
- Add tests for DTO mapping, request validation, single-flight behavior, and server lifecycle basics.
- Add a short connection guide with OpenAI client examples for PC access.
- Exit criteria: another developer can reproduce the setup and use it.

Suggested ownership boundaries for sub-agents:
- Step 1: documentation only.
- Step 2: build files only.
- Step 3: new `server/` access layer plus any minimal support changes in model manager abstractions.
- Step 4: new DTO files only.
- Step 5: new gateway files only.
- Step 6: service and manifest only.
- Step 7: model list route only.
- Step 8: non-streaming chat route only.
- Step 9: streaming route only.
- Step 10: config and UI only.
- Step 11: tests and docs only.

Guardrails:
- Do not let any step depend on code from a later step.
- Do not overlap file ownership between steps unless the orchestrator explicitly serializes the work.
- Keep each step small enough for one agent to finish and review quickly.
