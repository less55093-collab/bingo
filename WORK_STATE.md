# Work State

Mode: state-main
Topology: linear
Outcome: Let natural-language image requests produce an explicit, cost-aware plan before multi-variant generation, while keeping clear single-image requests fast.

## Baseline

- Git worktree is `bingo/`.
- Pre-existing user changes are present across AI, app, search, and tests. They must be preserved.
- Confirmed non-goals: no template center, no full image-generation workbench, no general chat redesign.

## Tasks

| ID | Status | Scope |
| --- | --- | --- |
| T-001 | done | Add image-generation variants and batch execution contract. |
| T-002 | done | Add image-specific approval card and selection/edit actions. |
| T-003 | done | Verify fast single-image and confirmed multi-image policies. |

## Completion

- Focused unit tests pass for legacy single-image requests, multi-variant approval, malformed variant gating, existing tool-call validation, and chat service behavior.
- `:app:assembleDebug` passes.
- Batch execution uses supervisor concurrency so successful variants survive an individual variant failure.
- No commits, pushes, migrations, or external provider calls were made.

## Implementation pressure check

- Failure assumption: a model may still emit multiple legacy calls instead of one `variants` call; keep legacy single-call compatibility and make the new tool description explicit.
- Main journey risk: approval must show the complete plan before any billable image request; `needsApproval` must be based on parsed variants.
- Evidence needed: unit tests for parsing and approval policy, plus an app build and a mocked tool execution path.
- Smallest coherent change: use the existing tool approval lifecycle and extend only image input/output handling.

## Delivery truth

Local verification can prove plan parsing, approval gating, selection serialization, and batch orchestration with fakes. Provider billing, concurrency limits, and exact cost metadata remain real-environment checks.

---

# Work State: Image-First Onboarding And Signup Credit

Mode: state-main
Topology: linear
Outcome: Introduce the image-generation Agent before monetization, keep chat as the main creation path, expose the existing professional workbench beside search, remove Bingo's Claude offering, and teach recharge only after a real insufficient-balance response.

## Baseline

- Git worktrees are `bingo/` and sibling `bingoapi/`; both contain pre-existing user changes that must be preserved.
- Confirmed product direction: clear single-image requests generate immediately; multi-variant plans require approval.
- Confirmed monetary behavior: new accounts receive a real `$0.20` balance in the app's existing USD-denominated balance system.
- Non-goals: no chat redesign, no new image workbench, no fake second-generation counter, no remote key deletion, and no production deployment.

## Tasks

| ID | Status | Scope |
| --- | --- | --- |
| T-101 | done | Verify and finish first-run Agent onboarding with no recharge-first content. |
| T-102 | done | Verify search and professional image-generation drawer actions are parallel and route correctly. |
| T-103 | done | Remove Bingo Claude models and Claude provisioning while preserving generic Anthropic SDK compatibility. |
| T-104 | done | Guarantee `$0.20` real signup balance across supported registration paths and migration defaults. |
| T-105 | done | Route only real insufficient-balance image errors into recharge guidance. |
| T-106 | done | Run focused tests, builds, backend checks, and whole-flow polish review. |

## T-101 Implementation Pressure Check

- Failure assumption: prose-only onboarding may still leave users unclear about the Agent -> proposal -> approval -> generation sequence.
- Main journey risk: introducing payment or too many controls before the first image request would recreate the aversion this change is meant to remove.
- Evidence needed: inspect all first-run pages and navigation, then compile the app and exercise the representative route where possible.
- Smallest coherent change: reuse the existing tutorial pager and chat destination, changing only its image-Agent content and final navigation.

## T-101 Verification

- The first authenticated launch opens the three-step image-Agent tutorial over a chat route; completing it pops back to chat.
- The tutorial contains no recharge action or payment-first copy and explicitly distinguishes immediate single-image generation from approval-gated multi-variant generation.
- Focused app compilation and unit tests passed.

## T-102 Implementation Pressure Check

- Failure assumption: two visible actions could still route to duplicate or newly built surfaces instead of the existing search and professional workbench.
- Main journey risk: cramped labels in a 300 dp drawer could hide the distinction between conversational creation and the professional workbench.
- Evidence needed: inspect both route targets and compile the Compose UI at the existing drawer width.
- Smallest coherent change: share the existing quick-action component and navigate to the existing `MessageSearch` and `ImageGen` screens.

## T-102 Verification

- `ChatDrawer.kt` renders exactly two equal-weight quick actions in one row.
- Search routes to `Screen.MessageSearch`; professional image generation routes to the existing `Screen.ImageGen` workbench.
- The previous duplicate image-generation menu item is removed; app compilation passed.

## T-103 Implementation Pressure Check

- Failure assumption: removing Bingo's Claude model list could accidentally remove generic Anthropic provider support used by imports or existing user configurations.
- Main journey risk: stale Claude credentials or models could remain visible after login and undermine the image-first product focus.
- Evidence needed: default-provider tests, injector/provisioning tests, and a source scan limited to Bingo-specific model/key constants.
- Smallest coherent change: remove only Bingo Claude defaults, gateway group/key provisioning, and overwrite injection; leave the generic Claude SDK/provider and import compatibility intact.

## T-103 Verification

- Bingo's shipped model list has no `claude-*` models and its background/fast model now resolves to GPT.
- Key provisioning creates only `app-gpt` and `app-image`; the legacy local Claude secret is cleared on key persistence.
- Focused default-provider and provider-injector tests passed. Generic `ClaudeProvider`, import support, and theme compatibility remain intact by design.

## T-104 Implementation Pressure Check

- Failure assumption: changing only config defaults would miss existing deployments whose `settings.default_balance` row is still zero or whose auth-source override grants zero.
- Main journey risk: silently overwriting a larger operator-configured grant would reduce existing signup benefits and damage trust.
- Evidence needed: migration discovery tests plus grant-plan tests for zero and higher configured balances across the final registration resolver.
- Smallest coherent change: migrate only untouched zero global defaults and enforce `$0.20` as the final minimum signup grant while preserving higher configured values.

## T-104 Verification

- `default.user_balance`, setup-generated config, and the example deployment config are `0.2`.
- Migration `196_default_signup_balance.sql` updates only a zero-valued existing `default_balance` row, preserving non-zero operator settings.
- The final signup resolver enforces a `$0.20` floor after global or source-specific settings for email and third-party registration paths; higher configured values remain unchanged.
- Backend migration, config, service, and focused grant-plan tests passed.

## T-105 Verification

- Standalone image generation maps the shared localized insufficient-balance result to a dialog whose action opens the existing redeem page.
- Chat Agent image-tool failures inspect only explicit balance/quota markers and render the same recharge action inline; unrelated errors remain ordinary errors.
- The old redeem-page link to the Agent tutorial was removed, and account copy now describes the Agent tutorial accurately.
- Focused error mapping tests and the debug app build passed.

## T-106 Implementation Pressure Check

- Failure assumption: local tests can pass while real provider pricing makes the number of free generations differ by model, size, or gateway pricing.
- Main journey risk: claiming an exact second-generation failure would be false if actual billed cost is below `$0.10` per image.
- Evidence needed: complete local test/build sweep, migration syntax checks, and explicit reporting of the remaining deployed billing verification gap.
- Smallest coherent change: keep real balance enforcement and avoid a fake generation counter or client-side denial.

## T-106 Verification And Polish

- App focused unit tests, `:app:assembleDebug`, and final `git diff --check` passed.
- Backend migration/config checks and signup-plan tests passed; migration embedding was verified by the migrations package tests.
- No production deployment, remote key deletion, commit, staging, or push was performed.
- Final review found no remaining in-scope implementation defect. Exact free-generation count remains pricing-dependent and is intentionally not simulated.

## Delivery Truth Target

Local checks can prove UI routing, configured model/key removal, signup grant resolution, migration behavior, and error classification. Actual provider billing and a deployed second-generation insufficient-balance experience remain real-environment checks requiring a deployed backend and fresh test account.

Status: complete
