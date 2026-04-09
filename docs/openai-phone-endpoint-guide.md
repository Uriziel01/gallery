# OpenAI-Compatible Phone Endpoint Guide

This guide shows how to run the phone endpoint from the app and connect to it from a PC.

## 1) Build and install from `gallery-openai`

Run Android build commands inside the Distrobox container:

```bash
distrobox enter gallery-openai -- bash -lc '
  cd /var/home/uriziel/projects/gallery/Android/src
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
  ANDROID_HOME=/home/uriziel/android-sdk \
  ANDROID_SDK_ROOT=/home/uriziel/android-sdk \
  ./gradlew :app:assembleDebug
'
```

Install the app to your phone with your normal Android workflow (`adb install`, Android Studio, etc.).

## 2) Enable the endpoint in the app

In the app:

1. Select a downloaded LLM chat-capable model in the model UI.
2. Open `Settings`.
3. In `OpenAI-compatible endpoint`:
4. Set `Port` (for example `8080`).
5. Set a `Bearer token` (required for `/v1/models` and `/v1/chat/completions`).
6. Tap `Save config`.
7. Tap `Start`.

`/health` is open. OpenAI routes require `Authorization: Bearer <token>`.

## 3) Find phone IP and verify from PC

Ensure phone and PC are on the same LAN/Wi-Fi. Find the phone IP from Android Wi-Fi details.

```bash
export PHONE_IP="192.168.1.50"
export PHONE_PORT="8080"
export PHONE_TOKEN="replace-with-your-token"
```

Health check:

```bash
curl "http://${PHONE_IP}:${PHONE_PORT}/health"
```

Model list:

```bash
curl -H "Authorization: Bearer ${PHONE_TOKEN}" \
  "http://${PHONE_IP}:${PHONE_PORT}/v1/models"
```

## 4) Chat completions

Non-streaming:

```bash
curl -sS \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${PHONE_TOKEN}" \
  -d '{
    "model": "YOUR_SELECTED_MODEL_NAME",
    "messages": [{"role":"user","content":"Write one short sentence about on-device AI."}],
    "stream": false
  }' \
  "http://${PHONE_IP}:${PHONE_PORT}/v1/chat/completions"
```

Streaming SSE:

```bash
curl -N \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${PHONE_TOKEN}" \
  -d '{
    "model": "YOUR_SELECTED_MODEL_NAME",
    "messages": [{"role":"user","content":"Count from 1 to 5."}],
    "stream": true
  }' \
  "http://${PHONE_IP}:${PHONE_PORT}/v1/chat/completions"
```

## 5) OpenAI SDK examples

### Python

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://192.168.1.50:8080/v1",
    api_key="replace-with-your-token",
)

resp = client.chat.completions.create(
    model="YOUR_SELECTED_MODEL_NAME",
    messages=[{"role": "user", "content": "Hello from my PC"}],
)
print(resp.choices[0].message.content)
```

### JavaScript

```javascript
import OpenAI from "openai";

const client = new OpenAI({
  baseURL: "http://192.168.1.50:8080/v1",
  apiKey: "replace-with-your-token",
});

const resp = await client.chat.completions.create({
  model: "YOUR_SELECTED_MODEL_NAME",
  messages: [{ role: "user", content: "Hello from my PC" }],
});

console.log(resp.choices[0].message?.content);
```

## 6) Stop the endpoint

In app `Settings` -> `OpenAI-compatible endpoint` -> tap `Stop`.
