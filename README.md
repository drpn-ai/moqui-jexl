# moqui-jexl

User scripts for any Moqui application: the people who use your app write small scripts that read its data safely,
try them, and put them on a schedule that emails or notifies them the answer, without a code commit. Scripts are
[Apache Commons JEXL](https://commons.apache.org/proper/commons-jexl/), run inside your own server behind limits and
guardrails, and never write anything.

Version 0.2.2. Until 1.0 the contract may still change (see CHANGELOG.md); from 1.0 it follows semantic versioning.

## What it adds

- **A sandbox** (`org.moqui.jexl.ScriptSupervisor`). A script reads only `input` (plain values) and the `fn:` library:
  no host objects, no class or method calls, no I/O, no clock. Every run has a work budget, a time limit, caps on text,
  list and number size, and a check on the memory it allocates.
- **Named limit profiles** (`ScriptProfiles`). `EXPRESSION` (one expression, no statements) and `SCRIPT` are built in;
  an application adds its own.
- **A lane for running inside your server** (`ScriptLane`): a few dedicated threads, one run at a time per owner, a
  capped queue, holds while the server is busy, a kill switch, and stuck-run handling.
- **A read step.** A script declares the inputs it reads (a read spec, JSON). The built-in `entity` source reads Moqui
  entities your app lists as readable, as the script's owner, so your authorization and entity filters apply. Your app
  can add its own read sources.
- **A result contract**: a summary, files as rows (rendered to CSV) and optional data.
- **Scheduled runs** (deployments): a cron in a time zone, an owner re-checked at every run, several deliveries each,
  and a run record.
- **Built-in deliveries**: email with the CSV files attached, and Moqui notifications. Your app can add its own.
- **Screens** in the Tools app: User Scripts (write, run, schedule, see runs).
- **A `.jexl` script-runner**: a `.jexl` file runs anywhere Moqui runs a script (a `type="script"` service, an XML
  actions `<script location>`, `ec.resource.script()`), in the same sandbox, with the plain context values as `input`.

## Install

Add the component to `runtime/component/moqui-jexl` (or your `addons.xml`), build, and load its seed data
(`./gradlew load`). Then:

1. Set `jexl_email_from` (the From address of script emails).
2. List the entities scripts may read (see the security model below).
3. Grant `JEXL_SCRIPT_EDIT` (write and run) and `JEXL_SCRIPT_SCHEDULE` (schedule) to the user groups that should have
   them. `ADMIN` has both.

## Security model

What a script can do: read its `input`, compute, and return a result. What it cannot do: call Java or Moqui, read a
file, open a connection, write anything, see the clock, or run past its limits.

What a read can see:

- **Only entities your app lists.** The artifact group `JEXL_READABLE` starts empty. Add your entities as `AT_ENTITY`
  members (by name, or a `nameIsPattern="Y"` regex). Nothing else is readable, not even for `ADMIN`.
- **Only as the owner, with authorization on.** A read runs on a script thread in a fresh execution context, logged in
  as the script's owner, with an empty artifact stack: nothing the caller was allowed carries over. Grant user groups
  view on `JEXL_READABLE`, and attach your filter sets (`ArtifactAuthzFilter`) to those grants to limit rows (by
  organization, by owner, ...).
- **Never `moqui.*`.** Moqui never applies entity filters to `moqui.*` entities, and they hold security data, so the
  entity source refuses them, including by a short name and inside a view.
- **Never an entity no grant would govern.** An entity marked `authorize-skip="view"` is refused, and so is every read
  while entity authorization is switched off (`AT_ENTITY authz-enabled="false"`): in both cases no grant or filter
  would apply.
- **Read sources are granted too.** The owner needs authorization on each source service (`JEXL_SOURCES`; the built-in
  entity source is in it).

Who runs what:

- A scheduled run reads, runs and delivers as its **owner**: whoever last stored it or changed its script. The owner
  is acted as directly, never logged in through the realm, so runs never touch the owner's login state. At every run
  the owner must still exist, not be terminated or disabled, and be allowed to schedule (a grant on
  `JEXL_SCRIPT_SCHEDULE`, where any DENY wins); otherwise the deployment switches itself off (`OWNER_LOST`). A
  temporary lockout after failed logins only holds that run (`HELD`).
- Storing a deployment always needs `JEXL_SCRIPT_SCHEDULE`, whichever screen or service it comes through.
- Editing a script that someone else's active deployment runs makes you its owner, so it needs `JEXL_SCRIPT_SCHEDULE`.
- Only the owner can run a deployment by hand.
- Every service a stored row can name (read source, input service, delivery) must implement the matching interface,
  checked when stored and again at every run.
- Email uses only templates listed in `jexl_email_templates`, to **any address** the delivery names: limit recipients
  in your own delivery if you need to. A notification goes only to the owner or a group the owner is an explicit member
  of.
- `.jexl` files your application ships are application code: they run in the sandbox with their time limit, but never
  wait on user scripts (no queue, no thread cap, no stuck pause, no kill switch).

## Read spec

```json
{ "inputs": [
  { "name": "orders", "source": "entity", "entityName": "mantle.order.OrderHeader",
    "fields": ["orderId", "statusId", "grandTotal", "placedDate"],
    "where": { "and": [ { "field": "statusId", "op": "in", "value": ["OrderApproved"] } ] },
    "window": { "field": "placedDate", "lastDays": 30 },
    "groupBy": ["statusId"],
    "measures": [ { "fn": "sum", "field": "grandTotal", "as": "total" }, { "fn": "count", "as": "n" } ],
    "orderBy": ["-total"] } ] }
```

- Up to 10 inputs; each is `input.<name>` in the script. The script also gets `input.run` (`date`, `timeZone`).
- `where`: `{field, op, value}` joined with `{and: [...]}` / `{or: [...]}`, up to 8 levels. `op` is one of Moqui's
  entity operators: `equals`, `not-equals`, `less`, `greater`, `less-equals`, `greater-equals`, `in`, `not-in`
  (lists up to 1,000), `between` (two values), `like`, `not-like` (`%` and `_`), `is-null`, `is-not-null`. Values are
  literals.
- `window`: the last N days (1 to 3,660) on a date field, from the start of that day in the run's time zone.
- `groupBy` + `measures` (`count`, `sum`, `min`, `max`, `avg`).
- The rows an input hands the script (groups, for a grouped read) are capped by the profile's `maxReadRows`. Past it
  the run stops with `READ_TOO_LARGE` and the count, never a silent cut. The read as a whole has a time limit.
- An application source may take its own keys in an input (they reach its service as `input`).

## Result contract

A script returns nothing (nothing to send), or:

```
{ summary: '3 orders are late', files: [ { name: 'late.csv', columns: ['orderId', 'days'], rows: [['SO-1', 3]] } ], data: {...} }
```

A bare text or number is a summary; a map without these keys, or a list, is `data`. At most 5 files; rows per file
up to the profile's `maxCollectionSize`; summary up to 4,000 characters. Files render as RFC 4180 CSV (UTF-8 with a
byte order mark); a text cell whose first visible character could start a spreadsheet formula gets a leading `'`.

## Writing a read source

A service that implements `moqui.jexl.ScriptServices.read#ScriptSource`: in `input` (the input as written), `ownerUserId`,
`userScriptId` (the saved script, empty for an unsaved run), `ownerKey` (the run's scope: what the host passed to
`run#UserScript`, the deployment's owner key, or the user), `runDate`, `timeZone`, `maxRows`; out `rows` (plain maps), `rowCount`, or `error` `{kind: READ_NOT_ALLOWED | READ_ERROR |
READ_TOO_LARGE, message}`. It runs logged in as the owner. `org.moqui.jexl.script.ReadConditions` matches, windows,
groups and orders rows in memory if your source cannot push them down. Register it with
`moqui.jexl.ScriptServices.store#ScriptSource` (name, service) and grant it through `JEXL_SOURCES`.

## Writing a delivery

A service that implements `moqui.jexl.ScriptServices.deliver#UserScriptDeploymentResult`: in `scriptDeployment`,
`delivery` (its row, with `parameters`), `scriptResult` (the contract), `files` (`[{name, contentType, contentBytes}]`),
`scriptRunId`; out `outcome` (`OK`, `TOO_LARGE`, `FAILED`) and `message`. It runs logged in as the owner; an error it
adds marks only this delivery `FAILED`. Grant it to the groups that schedule (the built-ins are in
`JEXL_SCRIPT_SCHEDULE`).

## Run statuses

`OK`, `NOTHING_TO_SEND`, `PREVIEW`, `HELD`, `DISABLED`, `OWNER_LOST`, `READ_ERROR`, `READ_NOT_ALLOWED`,
`READ_TOO_LARGE`, `SCRIPT_ERROR` (with the kind: `UNITS`, `TIME`, `MEMORY`, `NOT_ALLOWED`, ...), `RESULT_INVALID`,
`DELIVERY_PARTIAL`, `DELIVERY_FAILED`, `STUCK`, `SCRIPT_MISSING`, `NO_INPUT`, and for the scheduler's load holds
`WAITING` and `EXPIRED`. Real runs are kept as `jexl.script.UserScriptRun` rows.

## Settings

All are read with `SystemBinding.getPropOrEnv` (a system property or an environment variable).

| Setting | Default | Meaning |
|---|---|---|
| `jexl_profiles` | none | extra profile names (comma list); each starts from `SCRIPT` |
| `jexl_profile_<NAME>_<limit>` | per profile | tune a limit: `maxSourceChars`, `units`, `timeoutMs`, `maxStringChars`, `maxCollectionSize`, `maxInputValues`, `maxInputJsonChars`, `maxOutputJsonChars`, `maxOutputNodes`, `maxDepth`, `maxReadRows`, `maxAllocatedBytes` |
| `jexl_scripts_enabled` | true | false switches user scripts off (`.jexl` application files still run) |
| `jexl_lane_threads` | 2 | script threads |
| `jexl_lane_queue` | 4 | runs that may wait for a thread |
| `jexl_lane_wait_ms` | 30000 | how long a run waits before it is `HELD` |
| `jexl_lane_grace_ms` | 1000 | past its time limit plus this, a run that ignores its stop is `STUCK` |
| `jexl_lane_stuck_cap` | 2 | stuck runs before every new run is held |
| `jexl_read_timeout_ms` | 60000 | time limit of a read |
| `jexl_delivery_timeout_ms` | 120000 | time limit of a run's deliveries |
| `jexl_result_summary_chars` | 4000 | summary cap |
| `jexl_result_files` | 5 | files per result |
| `jexl_email_from` | none | From address of script emails |
| `jexl_email_templates` | `JEXL_SCRIPT_RESULT` | email templates deliveries may use |
| `jexl_email_attachment_bytes` | 20971520 | past this in all, the email goes without its files (`TOO_LARGE`) |
| `jexl_run_retention_days` | 30 | how long run records are kept |
| `jexl_owner_key_service` | none | a service returning `ownerKey` for a deployment (the lane's one-at-a-time key), default the owner user id |
| `jexl_owner_fields_service` | none | a service (in `entityName`, `ownerFields` = the effective values, `existing`; out `problem`) called on every store, create or update, that approves the record's owner values |
| `jexl_busy_service` | none | a service whose `busyReason` out-parameter adds your app's own reason to wait |
| `jexl_hold_heap_percent` | 70 | user scripts wait while heap after GC is at or over this (over 100 turns it off) |
| `jexl_hold_cpu_percent` | 75 | the same for process CPU |
| `jexl_max_wait_minutes` | 60 | how long a due scheduled run waits on a busy server before it expires |
| `jexl_deployment_check_seconds` | 60 | how often due deployments run; 0 turns the scheduler off |

A profile's memory check defaults to 512 bytes per unit of its work budget (JEXL allocates about 300 to 400 bytes per
unit in real scripts, garbage included).

## Owner scoping for apps that serve many organizations

The entities are in `jexl.script`, not `moqui.*`, so entity filters apply to them. Add your owner field to
`UserScript`, `UserScriptDeployment`, `UserScriptRun` and `ScriptSource` with `extend-entity`, pass it to
`store#UserScript` / `store#UserScriptDeployment` as `ownerFields` (a core field is refused there, an existing record's
owner fields never change, and `jexl_owner_fields_service` lets your app approve the values, e.g. that the caller acts
for that organization; a run record copies the extended fields it shares with its deployment), add an `EntityFilter`,
and expose scripts through your own (remote) services; none of this component's services are remote. Set
`jexl_owner_key_service` so one organization's scripts run one at a time, and pass the same key as `ownerKey` to
`run#UserScript`. Reads run as the owner in a fresh context, so your sources take the scope from `userScriptId` /
`ownerKey`, check the owner may use it, and set it up themselves.

## Example: orders by status

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="JEXL_READABLE" artifactName="mantle.order.OrderHeader"
        nameIsPattern="N" artifactTypeEnumId="AT_ENTITY" inheritAuthz="N"/>
<moqui.security.ArtifactAuthz artifactAuthzId="OPS_READABLE" userGroupId="OPS" artifactGroupId="JEXL_READABLE"
        authzTypeEnumId="AUTHZT_ALLOW" authzActionEnumId="AUTHZA_VIEW"/>
<moqui.security.ArtifactAuthzFilter artifactAuthzId="OPS_READABLE" entityFilterSetId="MY_ORG_ORDERS"/>
```

With the read spec above, the script

```
var lines = [...];
for (var row : input.orders) { fn:push(lines, [row.statusId, row.n, row.total]); }
{ summary: size(input.orders) + ' statuses', files: [ { name: 'orders.csv', columns: ['status', 'orders', 'total'], rows: lines } ] }
```

returns a summary and one CSV file, which a deployment can email every morning. Build rows with a `[...]` list and
`fn:push` (one unit per row): `l = l + [row]` copies the list every time, and a `[]` literal is a fixed array.

## Tests

From the Moqui root, after `./gradlew load`, one class at a time:

    ./gradlew :runtime:component:moqui-jexl:test --tests 'org.moqui.jexl.<Class>'

The tests use their own conf (`src/test/resources/conf/JexlTestConf.xml`) for test-only entities.

## License

CC0 1.0 Universal plus a Grant of Patent License, like Moqui (see LICENSE.md). JEXL is Apache 2.0.
