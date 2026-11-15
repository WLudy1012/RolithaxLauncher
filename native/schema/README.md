# Rolithax Schema native runtime

C++17 owns manifest validation, XML compilation, YAML matching, Lua execution, action grants, live state, evidence and hot reload. Android JNI and Windows C ABI clients consume the same JSON protocol described in [contract.md](contract.md).

Build and test on Windows with Visual Studio 2022 C++ tools:

```powershell
cmake -S native/schema -B native/schema/build -A x64
cmake --build native/schema/build --config Release --parallel
ctest --test-dir native/schema/build -C Release --output-on-failure
```

On Linux:

```sh
cmake -S native/schema -B native/schema/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/schema/build --parallel
ctest --test-dir native/schema/build --output-on-failure
```

The outputs are `rolithax_schema.dll` / `librolithax_schema.so` and `rolithax-schema`. Distribute the CLI with its shared library. The Linux CLI searches its own directory and `../lib`. For Android NDK, pass its CMake toolchain file, `ANDROID_ABI`, `ANDROID_PLATFORM`, `-DBUILD_TESTING=OFF` and `-DROLITHAX_SCHEMA_CLI=OFF`. All dependencies are vendored; no JVM or network access is needed to compile or run this core.

Each CLI process owns one session. Send one JSON object per line; each response occupies one line. Library callers create independent sessions and must release every response with `rolithax_schema_free`. Requests on a session serialize; destroying a session safely unregisters its handle while already-running requests finish.

Limits: 512 KiB requests, 128 controls, 8 UI nesting levels, 256 retained log lines / 256 KiB history, 8 KiB per log line, 128 YAML entries per section. A log gap marks the displayed state stale. Commands are candidates returned to the host only after grants, argument validation and an optional package revision check; the runtime never sends them.

Lua scripts receive 4 MiB allocator budget, 100,000 shared VM/C-library work units and a 25 ms deadline per load or log call. No filesystem, network, process, dynamic loading, bytecode loading, coroutine or debug access is exposed. `pcall` and `xpcall` are unavailable so a script cannot swallow the instruction-limit error. Pattern functions accept at most 8 KiB input, 256-byte patterns, one quantifier, and no balanced/frontier/backreference constructs. Quantified patterns must be anchored with `^`; `gmatch` does not accept quantifiers. Plain `string.find` and string slicing remain available. Bulk string, utf8 and table operations charge estimated work to the shared budget before running. Lua state updates are validated before commit. Failed reload or history replay leaves the existing package, grants and snapshot in place.

The tests cover the built-in Paper log rules, spoofed player messages, YAML/Lua compilation, Schema 2 controls, typed command inputs, duplicate keys, entity rejection, script budgets, failed reload/replay, stale confirmations and concurrent destruction. SDK fixture replays use this same executable rather than a second parser.
