# Changelog

## 0.2.2

- `store#UserScript` and `store#UserScriptDeployment` take `ownerFields`: fields the application added with
  `extend-entity` (an owner or organization field). A core field of the component is refused there. A run record
  (`UserScriptRun`) copies the extended fields it shares with its deployment. An existing record's owner fields never
  change, and the setting `jexl_owner_fields_service` names an application service that approves the values (for
  example, that the caller acts for that organization).
- The hourly run-record prune goes by the wall clock (the time records are stamped with), not the scan's own time.

## 0.2.1

- `read#ScriptSource` gains `userScriptId` and `ownerKey`, and `run#UserScript` gains `ownerKey` (the lane key, passed to
  sources): a source runs as the owner in a fresh context, so it needs to know which script and scope it reads for.
- Dates reach a script as ISO-8601 instants (`2026-11-20T10:00:00Z`), the same form as `input.run.date`, not
  server-local text.
- A run that cannot be recorded no longer stops the rest of a scheduler pass.
- `UserScriptRun.readCountsJson` is `text-long` (many long input names overflowed `text-medium`). Existing tables keep
  their column type until altered by hand.

## 0.2.0

A complete user-scripts feature for any Moqui application. Breaking changes from the first published commit
(0b2b867, which was labelled 1.0.0 but had no adopters; the component stays 0.x until its contract has run in
production):

- **Entities moved to `jexl.script`** (`jexl.script.UserScript`, `UserScriptDeployment`, ...). Moqui never applies
  entity filters to `moqui.*` entities, so the old `moqui.jexl` package could not be scoped by owner.
- **Deliveries are child rows** (`jexl.script.UserScriptDeploymentDelivery`); `UserScriptDeployment.deliveryServiceName`
  is gone. `store#UserScriptDeployment` takes `deliveries` (a list), or `deliveryServiceName` for one.
- **Deployments have an owner** (`ownerUserId`, required): reads and deliveries run as the owner, re-checked at every
  run (`OWNER_LOST` switches the deployment off). Only the owner runs a deployment by hand.
- **The delivery interface changed**: in `scriptDeployment`, `delivery`, `scriptResult` (now the result contract
  `{summary, files, data}`), `files` (rendered CSV), `scriptRunId`; out `outcome`, `message`.
- **Run statuses renamed**: `DELIVERED` → `OK`, `NOTHING_TO_DELIVER` → `NOTHING_TO_SEND`, `NOT_DELIVERED` →
  `DELIVERY_FAILED`; new: `HELD`, `DISABLED`, `OWNER_LOST`, `READ_ERROR`, `READ_NOT_ALLOWED`, `READ_TOO_LARGE`,
  `RESULT_INVALID`, `DELIVERY_PARTIAL`, `STUCK`.
- **Settings renamed to one prefix**: `script_busy_service` → `jexl_busy_service`, `script_hold_heap_percent` →
  `jexl_hold_heap_percent`, `script_hold_cpu_percent` → `jexl_hold_cpu_percent`, `script_max_wait_minutes` →
  `jexl_max_wait_minutes`, `script_deployment_check_seconds` → `jexl_deployment_check_seconds`.
- **Limits are named profiles** (`ScriptProfiles`); `ScriptSupervisor.LIMITS` and the `MAX_*` constants are gone.
  `ScriptSupervisor.parseInputJson` takes the profile.

Added: the script lane (owner slots, queue, holds, kill switch `set#UserScriptsEnabled`, stuck runs), the memory check,
the read step with the built-in entity source (`JEXL_READABLE`) and source plug-ins (`read#ScriptSource`,
`store#ScriptSource`), the result contract and CSV rendering, run records (`jexl.script.UserScriptRun`) with retention,
the email and notification deliveries, `fn:push`, the Tools screens, and the `JEXL_*` authorization groups.
