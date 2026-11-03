# moqui-jexl

Sandboxed [Apache Commons JEXL](https://commons.apache.org/proper/commons-jexl/) for Moqui: scripts users write and
keep as data, scheduled script deployments, and a `.jexl` script-runner.

## What it adds

- **A sandbox** (`org.moqui.jexl.ScriptSupervisor`). A script reads only `input` (plain JSON) and the `fn:` library.
  It has no host access, no class or method calls, and no I/O. It runs on its own small thread pool with a work budget,
  caps on text and list size, a time limit, and checks on number size. There are two profiles: `EXPRESSION` (one
  expression, no loops) and `SCRIPT` (statements, locals, loops, lambdas).
- **User scripts** (`moqui.jexl.UserScript`). `moqui.jexl.ScriptServices` validates, stores (after a trial run on
  the sample input) and runs them.
- **Script deployments** (`moqui.jexl.UserScriptDeployment`). A user script runs on a Quartz cron, read in its own
  time zone. An input service reads the input, the script runs, and a delivery service receives the result; the script
  itself never does I/O. A deployment may name only services that implement
  `moqui.jexl.ScriptServices.read#UserScriptDeploymentInput` or `deliver#UserScriptDeploymentResult`. This is checked
  when the deployment is stored and again on every run.
- **A scheduler thread** (`ScriptDeploymentScheduler`). It is started by `ScriptDeploymentToolFactory` on the
  factory's scheduled executor, the same way the framework runs `ScheduledJobRunner` for service jobs. Each due
  deployment is claimed under a row lock before it runs, so servers that share a database never deliver the same run
  twice.
- **A `.jexl` script-runner** (`JexlScriptRunner`), registered next to the `.groovy` and `.xml` runners. A `.jexl`
  location works anywhere Moqui runs a script: a `type="script"` service, an XML actions `<script location>`, or
  `ec.resource.script()`. It runs in the same sandbox. `input` holds the plain values from the context, and `ec`
  and other host objects are never exposed. For a script service, a returned map becomes the out-parameters.

## Settings

All are read with `SystemBinding.getPropOrEnv`, so a system property or an environment variable sets them.

| Setting | Default | Meaning |
|---|---|---|
| `script_deployment_check_seconds` | 60 | how often due deployments run; 0 turns the scheduler off |
| `script_hold_heap_percent` | 70 | user scripts wait while heap after GC is at or over this; over 100 turns the check off |
| `script_hold_cpu_percent` | 75 | the same for process CPU |
| `script_max_wait_minutes` | 60 | how long a due run waits on a busy server before it expires |
| `script_busy_service` | none | a service whose `busyReason` out-parameter adds the application's own reason to wait |

## Extending

An application that scopes scripts by owner adds its owner field to `UserScript` and `UserScriptDeployment` with
`extend-entity`. It filters on that field in its own services: none of the services here are remote.

## Tests

From the Moqui root, after `./gradlew load`:

    ./gradlew :runtime:component:moqui-jexl:test

## License

CC0 1.0 Universal plus a Grant of Patent License, like Moqui (see LICENSE.md). JEXL is Apache 2.0.
