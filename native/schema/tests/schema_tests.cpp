#include "rolithax_schema.h"
#include "json.hpp"
#include <atomic>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

using Json = nlohmann::json;
void require(bool value, const std::string& message) { if (!value) throw std::runtime_error(message); }
Json call(uint64_t id, Json input, bool success = true) {
    const auto text = input.dump();
    char* response = rolithax_schema_request(id, text.c_str());
    require(response != nullptr, "Null native response");
    std::string copy(response); rolithax_schema_free(response);
    const auto value = Json::parse(copy);
    require(value["ok"] == success, "Unexpected response: " + copy);
    return success ? value["data"] : value["error"];
}
Json yamlSource() {
    return {{"manifest", R"({"id":"tests.chunky","version":"1.2.3","sdkVersion":2,"server":"paper","engine":"yaml"})"},
        {"ui", R"(<panel title="Chunky"><section id="main" label="Tasks" description="World generation"><row id="metrics" label="Progress"><progress id="progress" label="Completed" field="progress" unit="%"/><metric id="chunks" label="Chunks" field="chunks"/></row><field id="radius" label="Radius" field="radius" placeholder="1000"/><field id="world" label="World" field="world"/><button id="start" label="Set radius" action="radius"/><button id="select" label="Select world" action="select"/></section></panel>)"},
        {"rules", R"(initial:
  progress: "0"
  chunks: "0"
rules:
  progress:
    prefix: "[Chunky] "
    field: "progress"
    capture: "percent"
  count:
    prefix: "Processed "
    suffix: " chunks"
    field: "chunks"
    capture: "number"
commands:
  radius:
    text: "chunky radius {radius}"
    inputs: "radius:integer"
  select:
    text: "chunky world {world}"
    inputs: "world:token"
)"}};
}
Json luaSource(const std::string& script) {
    return {{"manifest", R"({"id":"tests.lua","version":"1.0.0","sdkVersion":2,"server":"paper","engine":"lua"})"},
        {"ui", R"(<panel title="Lua"><status id="state" label="State" field="state"/><button id="query" label="Query" action="query"/></panel>)"},
        {"lua", script}};
}
void builtin(uint64_t id) {
    auto snapshot = call(id, {{"op", "snapshot"}});
    require(snapshot["pluginId"] == "rolithax.paper" && snapshot["revision"] == 0, "Default Paper plugin");
    require(call(id, {{"op", "log"}, {"text", "[12:00:00] [Server thread/INFO]: <Bob> Alex joined the game"}}) == false, "Chat spoof rejected");
    require(call(id, {{"op", "log"}, {"text", "[12:00:01] [Server thread/INFO]: Alex joined the game"}}) == true, "Join captured");
    require(call(id, {{"op", "log"}, {"text", "There are 2 of a max of 20 players online: Alex"}}) == false, "Incorrect count rejected");
    call(id, {{"op", "log"}, {"text", "\u001b[32mThere are 2 of a max of 20 players online: Alex, Steve\u001b[0m"}});
    call(id, {{"op", "log"}, {"text", "There are 2 of a max of 20 players online: Alex, Steve"}});
    snapshot = call(id, {{"op", "snapshot"}});
    require(snapshot["values"]["online-players"] == "Alex\nSteve", "Player list");
    require(snapshot["values"]["player-summary"] == "2 人在线", "Player summary");
    require(snapshot["evidence"]["activity"].get<std::string>().find("12:00:01") != std::string::npos, "Evidence kept");
    require(call(id, {{"op", "command"}, {"actionId", "refresh-players"}, {"expectedRevision", 0}}) == "list", "Default grant");
    require(call(id, {{"op", "command"}, {"actionId", "refresh-players"}, {"arguments", {{"extra", "Alex"}}}}).is_null(), "Extra arguments rejected");
}
void yaml(uint64_t id) {
    auto source = yamlSource();
    auto metadata = call(id, {{"op", "inspect"}, {"source", source}});
    require(metadata["commands"]["radius"] == "chunky radius {radius}" && metadata["sdkVersion"] == 2, "Metadata");
    call(id, {{"op", "log"}, {"text", "[Chunky] Task completed 12.5%"}});
    call(id, {{"op", "gap"}});
    auto installed = call(id, {{"op", "reload"}, {"source", source}, {"allowedCommands", Json::array({"radius", "select"})}});
    require(installed["values"]["progress"] == "12.5" && installed["stale"] == true, "Replay and stale preserved");
    const auto rev = installed["packageRevision"];
    require(call(id, {{"op", "command"}, {"actionId", "radius"}, {"arguments", {{"radius", "-100"}}}, {"expectedRevision", rev}}) == "chunky radius -100", "Typed integer command");
    require(call(id, {{"op", "command"}, {"actionId", "radius"}, {"arguments", {{"radius", "100\nstop"}}}}).is_null(), "Command injection rejected");
    require(call(id, {{"op", "command"}, {"actionId", "select"}, {"arguments", {{"world", "minecraft:the_nether"}}}}) == "chunky world minecraft:the_nether", "Namespaced token");
    const auto before = call(id, {{"op", "snapshot"}});
    auto bad = source; bad["ui"] = "<panel title=\"Oops\"><unknown/></panel>";
    call(id, {{"op", "reload"}, {"source", bad}}, false);
    require(call(id, {{"op", "snapshot"}}) == before, "Failed reload is atomic");
    bad = source; bad["manifest"] = R"({"id":"a.b","version":"1.0.0","sdkVersion":2,"sdkVersion":1,"server":"paper","engine":"yaml"})";
    call(id, {{"op", "inspect"}, {"source", bad}}, false);
    bad = source; bad["ui"] = "<!DOCTYPE panel [<!ENTITY bad SYSTEM 'file:///secret'>]><panel title=\"x\"><text id=\"a\" label=\"&bad;\"/></panel>";
    call(id, {{"op", "inspect"}, {"source", bad}}, false);
    bad = source; bad["rules"] = "initial:\n  state: &anchor \"test\"";
    call(id, {{"op", "inspect"}, {"source", bad}}, false);
    call(id, {{"op", "reset"}});
    require(call(id, {{"op", "command"}, {"actionId", "radius"}, {"arguments", {{"radius", "1"}}}, {"expectedRevision", rev}}).is_null(), "Reset invalidates confirmation");
    require(call(id, {{"op", "snapshot"}})["stale"] == false, "Reset clears stale");
    auto numeric = source;
    std::string rules = "initial:\n  progress: \"0\"\n  chunks: \"0\"\nrules:\n";
    for (int i = 0; i < 128; ++i) rules += "  rule-" + std::to_string(i) + ":\n    prefix: \"X\"\n    field: \"progress\"\n    capture: \"percent\"\n";
    rules += "commands:\n  radius:\n    text: \"chunky radius {radius}\"\n    inputs: \"radius:integer\"\n  select:\n    text: \"chunky world {world}\"\n    inputs: \"world:token\"\n";
    numeric["rules"] = rules;
    call(id, {{"op", "reload"}, {"source", numeric}});
    require(call(id, {{"op", "log"}, {"text", "X" + std::string(8180, '1')}}) == false, "Long numeric non-match stays bounded");
}
void lua(uint64_t id) {
    const auto valid = luaSource(R"(assert(os == nil and io == nil and package == nil and debug == nil and load == nil and pcall == nil)
return {initial={state="waiting"},commands={query={text="lp info"}},on_log=function(line,state)
 if line == "spin" then while true do end end
 if line == "memory" then local x=string.rep("x",10000000) end
 if line == "table" then table.move({},1,1000000000000,1) end
 if line == "unknown" then return {missing="bad"} end
 if line == "badpattern" then return {state=string.match(line,".*.*.*")} end
 if line == "unanchored" then for i=1,1000 do string.match(string.rep("a",8192),".*Z") end end
 if line == "c-budget" then local a=string.rep("x",8000); for i=1,10000 do a=string.reverse(a) end end
 if line == "gmatch" then for value in string.gmatch("^^^^^^", "^.*Z") do end end
 if string.sub(line,1,5) == "done:" then return {state=string.sub(line,6)} end
 return nil
end})");
    call(id, {{"op", "reload"}, {"source", valid}, {"allowedCommands", Json::array({"query"})}});
    require(call(id, {{"op", "command"}, {"actionId", "query"}}) == "lp info", "Lua declared command");
    call(id, {{"op", "log"}, {"text", "done:ready"}});
    const auto before = call(id, {{"op", "snapshot"}});
    require(before["values"]["state"] == "ready", "Lua state update");
    for (const auto* line : {"spin", "memory", "table", "unknown", "badpattern", "unanchored", "c-budget", "gmatch"}) {
        call(id, {{"op", "log"}, {"text", line}}, false);
        require(call(id, {{"op", "snapshot"}}) == before, "Failed Lua call leaves snapshot intact");
    }
    call(id, {{"op", "reload"}, {"source", luaSource("while true do end")}}, false);
    require(call(id, {{"op", "snapshot"}}) == before, "Load budget rollback");
    call(id, {{"op", "reload"}, {"source", luaSource("return {initial={state='x'},commands={query={text='list'}},on_log=function() while true do end end}")}}, false);
    require(call(id, {{"op", "snapshot"}}) == before, "Replay budget rollback");
    call(id, {{"op", "log"}, {"text", "done:recovered"}});
    require(call(id, {{"op", "snapshot"}})["values"]["state"] == "recovered", "Runtime survives budget error");
}
Json sdk3Source() {
    return {{"manifest", R"({
        "id":"tests.schema-three", "version":"1.0.0", "sdkVersion":3, "server":"paper", "engine":"lua",
        "name":"Schema 3", "description":"SDK fixture", "authors":["Rolithax"], "license":"AGPL-3.0-only",
        "homepage":"https://example.org/plugin", "icon":"banner",
        "assets":[
            {"id":"banner","mediaType":"image/png","path":"assets/picture.png","size":12,"sha256":"0000000000000000000000000000000000000000000000000000000000000000"},
            {"id":"demo","mediaType":"video/mp4","url":"https://example.org/demo.mp4","size":12,"sha256":"0000000000000000000000000000000000000000000000000000000000000000"}
        ],
        "actions":{
            "read-config":{"kind":"file","operation":"read","root":"mcdr-config","path":"config.yml"},
            "host-status":{"kind":"host-task","taskId":"status"}
        },
        "preflight":[{"kind":"mcdrPlugin","id":"sample_plugin","version":"2.1.0-rc.1"}]
    })"}, {"ui", R"(<panel title="Extended">
      <tabs id="tabs" label="Navigation"><tab id="first" label="Overview">
        <image id="banner" label="Screenshot" asset="banner"/><video id="demo" label="Demonstration" asset="demo"/>
        <expander id="choices" label="Parameters"><toggle id="enabled" label="Enabled" field="enabled"/>
          <select id="world" label="World" field="world"><option id="overworld" label="Overworld" value="world"/></select></expander>
        <status id="state" label="State" field="state"/><button id="query" label="Query" action="query"/>
        <button id="config" label="Open config" action="read-config"/>
      </tab></tabs>
    </panel>)"}, {"lua", R"(return {
      initial={state="waiting"},commands={query={text="example {world} {enabled}",inputs="world:token,enabled:boolean"}},
      on_log=function() end,
      on_preflight=function(result,state) if result.checks[1].status == "found" then return {state=result.checks[1].version} end end,
      on_action=function(result,state) if result.ok then return {state="done"} end end
    })"}};
}
void sdk3(uint64_t id) {
    auto source = sdk3Source();
    const auto info = call(id, {{"op", "inspect"}, {"source", source}});
    require(info["sdkVersion"] == 3 && info["assets"].size() == 2 && info["preflight"][0]["id"] == "sample_plugin", "SDK 3 metadata");
    require(info["nodes"][0]["children"][0]["children"][0]["asset"] == "banner", "Media references are serialized");
    require(info["actions"]["read-config"]["root"] == "mcdr-config" && info["commands"].contains("query"), "Typed and console actions coexist");
    call(id, {{"op", "restore"}});
    const auto installed = call(id, {{"op", "reload"}, {"source", source}, {"allowedCommands", Json::array({"query", "read-config"})}});
    const auto version = installed["packageRevision"];
    const Json file{{"op", "prepareAction"}, {"actionId", "read-config"}, {"expectedRevision", version}};
    require(call(id, file) == info["actions"]["read-config"], "Authorized typed action");
    auto invalid = file; invalid["arguments"] = {{"path", "config.yml"}};
    require(call(id, invalid).is_null(), "A typed action cannot override its declared target");
    invalid = file; invalid.erase("expectedRevision");
    require(call(id, invalid).is_null(), "Typed action requires an explicit revision");
    invalid = file; invalid["actionId"] = "host-status";
    require(call(id, invalid).is_null(), "Unknown grant does not authorize host task");
    Json query{{"op", "command"}, {"actionId", "query"}, {"expectedRevision", version}, {"arguments", {{"world", "world"}, {"enabled", "true"}}}};
    require(call(id, query) == "example world true", "Select and toggle supply command input");
    query["arguments"]["world"] = "other";
    require(call(id, query).is_null(), "Undeclared select value rejected");
    const Json checks = Json::array({{{"status", "found"}, {"version", "2.1.0-rc.1"}}});
    require(call(id, {{"op", "event"}, {"eventType", "preflight"}, {"expectedRevision", version}, {"result", {{"checks", checks}}}}) == true, "Preflight result updates Lua state");
    require(call(id, {{"op", "snapshot"}})["values"]["state"] == "2.1.0-rc.1", "Lua result value shown");
    const Json action{{"op", "event"}, {"eventType", "action"}, {"expectedRevision", version}, {"result", {{"ok", true}}}};
    require(call(id, action) == true, "Action result updates Lua state");
    const auto before = call(id, {{"op", "snapshot"}});
    auto bad = source;
    auto manifest = Json::parse(source["manifest"].get<std::string>());
    manifest["actions"]["read-config"]["path"] = "../secret.yml";
    bad["manifest"] = manifest.dump();
    call(id, {{"op", "reload"}, {"source", bad}}, false);
    require(call(id, {{"op", "snapshot"}}) == before, "Invalid SDK 3 load rolls back");
    call(id, {{"op", "reset"}});
    require(call(id, action) == false && call(id, file).is_null(), "Reset invalidates old results and confirmations");
}
void concurrency() {
    const auto id = rolithax_schema_create();
    std::atomic<bool> failed{false};
    std::vector<std::thread> threads;
    for (int i = 0; i < 4; ++i) threads.emplace_back([&] {
        for (int j = 0; j < 100; ++j) {
            auto* response = rolithax_schema_request(id, "{\"op\":\"snapshot\"}");
            if (!response) failed = true;
            else { try { const auto parsed = Json::parse(response); if (!parsed.is_object()) failed = true; } catch (...) { failed = true; } rolithax_schema_free(response); }
        }
    });
    rolithax_schema_destroy(id);
    for (auto& thread : threads) thread.join();
    require(!failed, "Destroy race must return JSON safely");
}
int main() {
    const auto id = rolithax_schema_create();
    try {
        require(id != 0, "Create session");
        builtin(id); yaml(id); lua(id); sdk3(id); concurrency();
        call(id, {{"op", "restore"}});
        require(call(id, {{"op", "snapshot"}})["pluginId"] == "rolithax.paper", "Restore builtin");
        rolithax_schema_destroy(id);
        call(id, {{"op", "snapshot"}}, false);
        std::cout << "All native schema tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        rolithax_schema_destroy(id); std::cerr << error.what() << '\n'; return 1;
    }
}
