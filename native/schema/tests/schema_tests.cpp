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
        builtin(id); yaml(id); lua(id); concurrency();
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
