# OpenRayneo × DSH Go

OpenRayneo owns the glasses connection, audio storage, ASR, local speaker processing and record summaries.
The phone's external Agent decides whether to wait, answer, call tools or run a workflow.
This revision uses plugin **0.1.10**. Update the companion APK and plugin together for historical-input rejection and review-queue isolation.
Architecture and current limitations live in the workspace `.arch/`; operations live in `docs/runbook.md`.

## Install and connect

Install this directory once:

```sh
dsh plugin --profile web add /absolute/path/openrayneo-dsh
```

For builds with the installation-bundle workflow, download the `openrayneo-android-<commit>` artifact from a successful main-branch Android APK run. It contains `app-release.apk`, `openrayneo-validation.apk`, the matching `openrayneo-dsh-*.tgz` and `install-manifest.json` with package, version, SHA-256 hashes and verified certificate fingerprints. `openrayneo-validation.apk` has its own Android package name (`com.openrayneo.android.validation`) so it can be installed beside an older differently signed trial APK **without uninstalling it or deleting its data**. It starts with separate app settings and records; stop the older OpenRayneo bridge before connecting glasses, and grant the validation app its own Bluetooth/notification permissions. It is a test install, not an automatic data migration. Compare the certificate with the installed APK before updating. A fixed CI signing key does not guarantee a match with an older install; do not uninstall to bypass a mismatch. PR builds validate the bundle but do not publish an installation artifact.

For a packaged update, replace the installed plugin directory with `openrayneo-dsh-0.1.10.tgz` contents.
The npm archive has a top-level `package/`; use `--strip-components=1` when extracting into that directory.
Update the companion APK and restart the DSH engine after replacing plugin code.
Existing connection credentials and saved phone records remain.

In OpenRayneo enable **DSH Go 与外部 Agent → 本机 Agent 入口**, then copy its address and token.
In DSH Go tap **Connect / 连接眼镜** and allow clipboard access.
The plugin stores the token as the `OPENRAYNEO_TOKEN` credential; clipboard content stays out of prompts.
The default local URL is `http://127.0.0.1:8766`; set the actual Service URL for a custom port.
Keep both phone apps allowed to run in the background.
Record encounter sync uses a separate token and port **8767**; it does not expose device tools.

## Tools and records

The plugin registers `rayneo_status`, `rayneo_tools` and `rayneo_call`.
Read the actual tool definitions before use; they define the available glasses and phone operations.
`record_query` searches by record ID, text or Unix-millisecond interval `[from,to)`.
`record_annotate` attaches `analysis`, `note` or `tags` using the queried `sourceRevision`.
Use a stable annotation `id` for retries; source corrections mark derived results stale.
Todo/calendar mutations require stable `request_id` values and readback after uncertain outcomes.
External `/v1/tools/call` writes require approval of their exact arguments in the OpenRayneo AI screen within 40 seconds. Read-only tool calls retain the existing authorization model. The existing `state=running` polling contract is preserved; `phase=awaiting_approval` distinguishes review from execution. Reject/expiry never grants approval. The plugin's overall tool deadline still applies; after interruption, inspect the actual device state rather than retrying a write. A completed tool response, queued notification, or cancellation is not a guarantee of physical device state. The separate record synchronization API contract is unchanged.
Raw audio, voice embeddings, model API keys and vendor-cloud credentials are not needed by this plugin.

## Voice delivery and conversation reuse

Completed ASR and speaker results are delivered together, including unknown or mixed segments.
The encrypted phone outbox preserves pending inputs while DSH is unavailable and answers until delivery.
Live **小雷小雷** inputs reuse one DSH conversation across wake, record and task IDs.
The persisted binding is scoped by phone installation, native conversation key and selected Agent preset.
Stable native keys require Android36 or newer; other live sources retain separate sessions. Historical backfill stays in records instead of starting a new Agent task.
Phone **助手 → 新建对话** rotates the native key and clears local short context without deleting records.
Changing the Agent preset chooses its own binding; stale replies cannot enter a new conversation.

The reply uses the current native wake/realtime SID when valid, otherwise a glasses notification.
Stopping capture preserves an already submitted task; cancellation targets only the owned request.
Manual DSH input and other requests keep their own lifecycle; already executed operations remain.
Same-key requests serialize until the prior turn and cancellation cleanup are quiescent.
Bindings survive plugin reload and DSH cold restart through the real session controller and storage domain.
Only a genuinely missing session may be recreated before prompting; transient failures do not replay prompts/tools.
Terminal delivery retries use readback. Approvals and phone-control permissions remain in DSH Go.

Historical backfill remains in the existing record/summary pipeline. The new phone relay rejects new backfill Agent requests and cancels outstanding legacy backfill requests on restore; the updated plugin also rejects such a request before model dispatch. Already executed effects are not undone. Explicitly request historical analysis from the record screen if needed.

## Optional independent ambient model

By default, automatic hints continue to use DSH `agentDefaultModel.currentSelection()`. In the OpenRayneo plugin configuration, set both `ambientProvider` (registered DSH provider route) and `ambientModel` (its exact model ID) to select a separate model for AlwaysOn and opt-in fast realtime hints. Optionally specify `ambientReasoningEffort`, an exact adapter-supported identifier; leave it blank to use that selected model’s provider default. Clear all three fields to restore the original behavior. These are configuration fields, not a new provider or credential mechanism. The model must already be available through the configured DSH service. The runtime validates the selected route and effort; unsupported selections fail without fallback to the default model. Existing Agent session/preset selection, manual follow-up extraction, and summary service are unchanged. No model price or free entitlement is implied. Verify actual billing and latency with your chosen provider.
