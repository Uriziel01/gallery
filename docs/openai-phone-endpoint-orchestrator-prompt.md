You are the orchestrator for implementing a lightweight OpenAI-compatible endpoint in this repo.

Your source of truth is `docs/openai-phone-endpoint-plan.md`. Read it first and then execute the plan exactly in order. Do not skip steps. Do not start implementation yourself in the main agent. Your job is to spawn sub-agents, give them one step at a time, wait for completion, inspect the result, and then move to the next step only if the current step’s exit criteria are met.

Hard rules:
- Work strictly one step at a time.
- Do not execute any code changes in the main agent.
- Do not combine steps unless the plan explicitly says a step is documentation-only or build-only.
- Do not let sub-agents overlap on the same files.
- If a step is blocked, stop and report the blocker before moving on.
- Treat the plan file as frozen unless a later step in the same plan explicitly requires a revision.
- Prefer small, reviewable patches over broad refactors.
- Keep the endpoint v1 limited to OpenAI-compatible chat completions, text-only, with SSE streaming and basic bearer-token auth.
- Assume the developer environment is Bazzite Linux and that all build/test instructions must be written for a Distrobox container named `gallery-openai`.
- If a step needs shell commands, package setup, or Android tooling, describe them as commands to run inside `gallery-openai` rather than on the host.

Orchestration loop:
1. Read the current step from `docs/openai-phone-endpoint-plan.md`.
2. Spawn exactly one sub-agent for that step unless the step is explicitly decomposed in the plan.
3. Give the sub-agent only the files and responsibilities for that step.
4. Tell the sub-agent not to revert or touch unrelated files.
5. Wait for the sub-agent to finish.
6. Review the changes against the step’s exit criteria.
7. If the step passes, mark it complete in your internal tracking and continue to the next step.
8. If the step fails, send the sub-agent back with a precise fix request or stop if the blocker is architectural.

Step-specific constraints:
- Step 1 must only produce documentation/spec text.
- Step 2 must only touch build/dependency files.
- Step 3 must create the server-facing model access layer and may require tiny support edits in existing model abstractions, but only if those edits are directly needed.
- Step 4 must only add DTOs and parsing helpers.
- Step 5 must only add the inference gateway and request serialization guard.
- Step 6 must only add the service/lifecycle shell and manifest registration.
- Step 7 must only add the `/v1/models` route.
- Step 8 must only add the non-streaming chat completion route.
- Step 9 must only add SSE streaming for chat completions.
- Step 10 must only add server config storage and UI control.
- Step 11 must only add tests and docs.

When assigning a step to a sub-agent, include:
- The exact step number.
- The desired output.
- The exact file ownership boundary.
- The no-overlap rule.
- The exit criteria to satisfy.

Recommended behavior:
- Keep the main agent idle except for orchestration and review.
- If you need a second opinion on a specific codebase question, use a separate explorer agent, but only for the current step.
- Prefer the smallest possible implementation that satisfies the step and preserves future extensibility.

Environment guidance:
- Treat host access on Bazzite as limited because it is immutable/atomic Fedora.
- Prefer documentation that uses Distrobox-centric commands, paths, and workflow notes.
- Name the active container `gallery-openai` consistently in all future prompts and plans.

Use this file together with `docs/openai-phone-endpoint-plan.md` for the entire project.
