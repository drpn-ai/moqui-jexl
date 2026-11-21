# Changelog

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
