#include "rolithax_schema.h"
#include "json.hpp"
#include "tinyxml2.h"
extern "C" {
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
}
#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <regex>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
using Json = nlohmann::json;
using Strings = std::map<std::string, std::string>;
struct Failure : std::runtime_error {
    std::string file;
    int line;
    Failure(std::string f, int l, const std::string& m) : std::runtime_error(m), file(std::move(f)), line(l) {}
};
[[noreturn]] void fail(const std::string& file, int line, const std::string& message) { throw Failure(file, line, message); }
bool starts(const std::string& s, const std::string& prefix) { return s.compare(0, prefix.size(), prefix) == 0; }
bool ends(const std::string& s, const std::string& suffix) { return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0; }
bool asciiAlnum(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'); }
bool symbol(const std::string& s) {
    return !s.empty() && s.size() <= 64 && s[0] >= 'a' && s[0] <= 'z' && std::all_of(s.begin(), s.end(), [](char c) { return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'; });
}
bool player(const std::string& s) { return !s.empty() && s.size() <= 16 && std::all_of(s.begin(), s.end(), [](char c) { return asciiAlnum(c) || c == '_'; }); }
std::string trim(const std::string& s) {
    const auto first = s.find_first_not_of(" \r\n\t");
    return first == std::string::npos ? "" : s.substr(first, s.find_last_not_of(" \r\n\t") - first + 1);
}
std::vector<std::string> split(const std::string& s, const std::string& delimiter) {
    std::vector<std::string> result;
    size_t start = 0;
    for (;;) {
        const auto at = s.find(delimiter, start);
        result.push_back(s.substr(start, at == std::string::npos ? at : at - start));
        if (at == std::string::npos) return result;
        start = at + delimiter.size();
    }
}
bool validText(const std::string& s, bool newline = false) {
    for (size_t i = 0; i < s.size();) {
        unsigned char c = static_cast<unsigned char>(s[i++]);
        if (c < 0x80) { if ((c < 32 && !(newline && c == 10)) || c == 127) return false; continue; }
        unsigned cp; int count;
        if (c >= 0xc2 && c <= 0xdf) { cp = c & 31; count = 1; }
        else if (c >= 0xe0 && c <= 0xef) { cp = c & 15; count = 2; }
        else if (c >= 0xf0 && c <= 0xf4) { cp = c & 7; count = 3; }
        else return false;
        if (i + count > s.size()) return false;
        for (int j = 0; j < count; ++j) { c = static_cast<unsigned char>(s[i++]); if ((c & 0xc0) != 0x80) return false; cp = (cp << 6) | (c & 63); }
        if ((count == 1 && cp < 128) || (count == 2 && cp < 2048) || (count == 3 && cp < 65536) || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) || (cp >= 128 && cp <= 159)) return false;
    }
    return true;
}
std::string excerpt(const std::string& s, size_t max = 512) {
    if (s.size() <= max) return s;
    size_t end = max;
    while (end && (static_cast<unsigned char>(s[end]) & 0xc0) == 0x80) --end;
    return s.substr(0, end);
}
Json parseJson(const std::string& text, const std::string& file) {
    std::vector<std::set<std::string>> keys;
    try {
        return Json::parse(text, [&](int depth, Json::parse_event_t event, Json& parsed) {
            if (depth > 24) fail(file, 1, "JSON is too deeply nested");
            if (event == Json::parse_event_t::object_start) keys.emplace_back();
            if (event == Json::parse_event_t::key && !keys.back().insert(parsed.get<std::string>()).second) fail(file, 1, "Duplicate JSON key");
            if (event == Json::parse_event_t::object_end) keys.pop_back();
            return true;
        });
    } catch (const Json::exception&) { fail(file, 1, "Invalid JSON"); }
}
std::string stringAt(const Json& value, const char* key, const std::string& file, bool optional = false) {
    if (!value.contains(key)) { if (optional) return ""; fail(file, 1, std::string("Missing ") + key); }
    if (!value[key].is_string()) fail(file, 1, std::string(key) + " must be a string");
    return value[key].get<std::string>();
}
void allowedKeys(const Json& j, const std::set<std::string>& keys, const std::string& file, int line = 1) {
    if (!j.is_object()) fail(file, line, "Expected an object");
    for (auto it = j.begin(); it != j.end(); ++it) if (!keys.count(it.key())) fail(file, line, "Unknown property: " + it.key());
}
struct Command {
    std::string text;
    Strings inputs;
    std::optional<std::string> resolve(const Strings& arguments) const {
        if (arguments.size() != inputs.size()) return {};
        std::string result = text;
        static const std::regex integer("-?[0-9]{1,10}"), number("-?[0-9]+(?:\\.[0-9]+)?");
        for (const auto& item : inputs) {
            const auto found = arguments.find(item.first);
            if (found == arguments.end()) return {};
            const auto& v = found->second;
            bool valid = false;
            if (item.second == "player") valid = player(v);
            else if (item.second == "token") valid = !v.empty() && v.size() <= 128 && std::all_of(v.begin(), v.end(), [](char c) { return asciiAlnum(c) || std::strchr("_.:/+-", c) != nullptr; });
            else if (item.second == "integer") valid = std::regex_match(v, integer);
            else if (item.second == "number") valid = v.size() <= 32 && std::regex_match(v, number);
            if (!valid || v.find('\0') != std::string::npos) return {};
            const auto placeholder = "{" + item.first + "}";
            size_t at = 0;
            while ((at = result.find(placeholder, at)) != std::string::npos) { result.replace(at, placeholder.size(), v); at += v.size(); }
        }
        return result.size() <= 4096 ? std::optional<std::string>(result) : std::nullopt;
    }
};
Command compileCommand(const Strings& props, int sdk, const std::string& file, int line) {
    for (const auto& p : props) if (p.first != "text" && p.first != (sdk == 1 ? "player" : "inputs")) fail(file, line, "Unknown command property");
    if (!props.count("text")) fail(file, line, "Expected command text");
    Command result{props.at("text"), {}};
    static const std::regex commandName("[A-Za-z0-9][A-Za-z0-9_:-]{0,63}");
    const auto words = split(result.text, " ");
    if (result.text.empty() || result.text.size() > (sdk == 1 ? 160u : 256u) || !std::regex_match(words[0], commandName)) fail(file, line, "Invalid command name");
    if (sdk == 1) {
        if (props.count("player")) {
            const auto& input = props.at("player");
            if (!symbol(input) || words.size() != 2 || words[1] != "{" + input + "}") fail(file, line, "Use one player placeholder");
            result.inputs[input] = "player";
        } else if (words.size() != 1) fail(file, line, "Use a command name and at most one player placeholder");
        return result;
    }
    if (props.count("inputs")) for (const auto& declaration : split(props.at("inputs"), ",")) {
        const auto pair = split(declaration, ":");
        if (pair.size() != 2 || !symbol(pair[0]) || !std::set<std::string>{"player", "token", "integer", "number"}.count(pair[1]) || !result.inputs.emplace(pair[0], pair[1]).second) fail(file, line, "Invalid input declaration");
    }
    std::set<std::string> used;
    for (size_t i = 1; i < words.size(); ++i) {
        const auto& word = words[i];
        if (starts(word, "{") && ends(word, "}")) {
            const auto input = word.substr(1, word.size() - 2);
            if (!result.inputs.count(input)) fail(file, line, "Undeclared command input");
            used.insert(input);
        } else if (word.empty() || !std::all_of(word.begin(), word.end(), [](char c) { return asciiAlnum(c) || std::strchr("_.:/+-", c) != nullptr; })) fail(file, line, "Invalid static command word");
    }
    if (used.size() != result.inputs.size()) fail(file, line, "Unused command input");
    return result;
}
struct Rule {
    std::string prefix, suffix, contains, field, operation, capture;
    std::optional<std::string> value;
    std::optional<std::string> match(const std::string& line) const {
        if (line.size() < prefix.size() + suffix.size() || !starts(line, prefix) || !ends(line, suffix) || line.find(contains) == std::string::npos) return {};
        if (value) return value;
        auto middle = line.substr(prefix.size(), line.size() - prefix.size() - suffix.size());
        if (capture == "text") return excerpt(middle);
        if (capture == "player") return player(middle) ? std::optional<std::string>(middle) : std::nullopt;
        if (capture == "player-event") return player(middle) ? std::optional<std::string>(line) : std::nullopt;
        if (capture == "number" || capture == "percent") {
            auto digit = [](char c) { return c >= '0' && c <= '9'; };
            for (size_t at = 0; at < middle.size();) {
                const size_t start = at;
                if (middle[at] == '-' && at + 1 < middle.size() && digit(middle[at + 1])) ++at;
                else if (!digit(middle[at])) { ++at; continue; }
                while (at < middle.size() && digit(middle[at])) ++at;
                if (at + 1 < middle.size() && middle[at] == '.' && digit(middle[at + 1])) {
                    ++at;
                    while (at < middle.size() && digit(middle[at])) ++at;
                }
                if (capture == "number" || (at < middle.size() && middle[at] == '%')) return at - start <= 32 ? std::optional<std::string>(middle.substr(start, at - start)) : std::nullopt;
            }
            return {};
        }
        int onlineCount = -1;
        if (capture == "online" || capture == "online-summary") {
            static const std::regex online("([0-9]{1,2}) of a max of [0-9]{1,9} players online: ?(.*)");
            std::smatch found;
            if (!std::regex_match(middle, found, online)) return {};
            onlineCount = std::stoi(found[1].str()); middle = found[2].str();
        }
        const auto names = middle.empty() ? std::vector<std::string>{} : split(middle, ", ");
        if (names.size() > 64 || !std::all_of(names.begin(), names.end(), player) || (onlineCount >= 0 && static_cast<int>(names.size()) != onlineCount)) return {};
        if (capture == "online-summary") return std::to_string(names.size()) + " 人在线";
        std::string joined;
        for (const auto& name : names) { if (!joined.empty()) joined += '\n'; joined += name; }
        return joined;
    }
};
struct LuaRuntime;
struct Plugin {
    std::string id, version, server, title, engine = "yaml";
    int sdk = 1;
    Json nodes;
    Strings initial;
    std::vector<Rule> rules;
    std::map<std::string, Command> commands;
    std::unique_ptr<LuaRuntime> lua;
    Json source;
};
struct YamlEntry { int line = 1; Strings values; };
void parseYaml(Plugin& plugin, const std::string& text) {
    if (text.size() > 65536) fail("rules.yaml", 1, "Rules are too large");
    std::map<std::string, std::map<std::string, YamlEntry>> sections;
    std::vector<std::pair<std::string, std::string>> order;
    std::string section;
    YamlEntry* entry = nullptr;
    std::istringstream stream(text);
    std::string raw; int line = 0;
    while (std::getline(stream, raw)) {
        ++line;
        while (!raw.empty() && (raw.back() == '\r' || raw.back() == ' ' || raw.back() == '\t')) raw.pop_back();
        if (trim(raw).empty() || starts(trim(raw), "#")) continue;
        const auto indent = raw.find_first_not_of(' '), colon = raw.find(':', indent);
        if (colon == std::string::npos) fail("rules.yaml", line, "Expected a mapping entry");
        const auto key = raw.substr(indent, colon - indent), value = trim(raw.substr(colon + 1));
        if (!symbol(key)) fail("rules.yaml", line, "Invalid mapping key");
        auto scalar = [&]() {
            if (value.size() < 2 || value.front() != '"' || value.back() != '"') fail("rules.yaml", line, "Expected a double-quoted string");
            Json decoded;
            try { decoded = parseJson(value, "rules.yaml"); } catch (const Failure&) { fail("rules.yaml", line, "Invalid quoted string"); }
            if (!decoded.is_string()) fail("rules.yaml", line, "Expected a string");
            const auto result = decoded.get<std::string>();
            if (result.size() > 512 || !validText(result)) fail("rules.yaml", line, "Scalar is too long or contains control characters");
            return result;
        };
        if (indent == 0) {
            if (!std::set<std::string>{"initial", "rules", "commands"}.count(key) || !value.empty() || sections.count(key)) fail("rules.yaml", line, "Unknown or duplicate section");
            sections[key] = {}; section = key; entry = nullptr;
        } else if (indent == 2) {
            if (section.empty()) fail("rules.yaml", line, "Missing section");
            auto& entries = sections[section];
            if (entries.size() >= 128 || entries.count(key)) fail("rules.yaml", line, "Too many or duplicate entries");
            entry = &entries[key]; entry->line = line; order.emplace_back(section, key);
            if (section == "initial") entry->values["value"] = scalar();
            else if (!value.empty()) fail("rules.yaml", line, "Expected a nested mapping");
        } else if (indent == 4) {
            if (!entry || section == "initial" || entry->values.size() >= 8 || entry->values.count(key)) fail("rules.yaml", line, "Unexpected or duplicate property");
            entry->values[key] = scalar();
        } else fail("rules.yaml", line, "Use two-space indentation");
    }
    for (const auto& pair : order) {
        const auto& e = sections[pair.first][pair.second]; const auto& p = e.values;
        if (pair.first == "initial") plugin.initial[pair.second] = p.at("value");
        else if (pair.first == "commands") plugin.commands[pair.second] = compileCommand(p, plugin.sdk, "rules.yaml", e.line);
        else {
            for (const auto& property : p) if (!std::set<std::string>{"prefix", "suffix", "contains", "field", "operation", "value", "capture"}.count(property.first)) fail("rules.yaml", e.line, "Unknown rule property");
            auto get = [&](const char* key) { const auto found = p.find(key); return found == p.end() ? std::string() : found->second; };
            Rule rule{get("prefix"), get("suffix"), get("contains"), get("field"), p.count("operation") ? get("operation") : "set", get("capture"), {}};
            if (rule.prefix.empty() && rule.suffix.empty() && rule.contains.empty()) fail("rules.yaml", e.line, "A matcher is required");
            if (!symbol(rule.field)) fail("rules.yaml", e.line, "Invalid state field");
            auto captures = std::set<std::string>{"text", "player", "players", "player-event", "online", "online-summary"};
            if (plugin.sdk == 2) { captures.insert("number"); captures.insert("percent"); }
            if ((rule.operation != "set" && rule.operation != "append") || p.count("value") == p.count("capture") || (p.count("capture") && !captures.count(rule.capture))) fail("rules.yaml", e.line, "Invalid operation, value or capture");
            if (p.count("value")) rule.value = get("value");
            plugin.rules.push_back(std::move(rule));
        }
    }
}

void parseXml(Plugin& plugin, const std::string& text) {
    if (text.size() > 65536) fail("ui.xml", 1, "Layout is too large");
    if (text.find("<!") != std::string::npos || text.find("<?") != std::string::npos) fail("ui.xml", 1, "Declarations, comments and DTDs are not supported");
    for (size_t at = 0; (at = text.find('&', at)) != std::string::npos; ++at) {
        const auto end = text.find(';', at);
        if (end == std::string::npos || !std::set<std::string>{"&amp;", "&lt;", "&gt;", "&quot;", "&apos;"}.count(text.substr(at, end - at + 1))) fail("ui.xml", 1, "Unsupported XML entity");
    }
    tinyxml2::XMLDocument document;
    if (document.Parse(text.data(), text.size()) != tinyxml2::XML_SUCCESS) fail("ui.xml", document.ErrorLineNum(), "Invalid XML layout");
    auto* root = document.RootElement();
    if (!root || std::string(root->Name()) != "panel" || !root->Attribute("title") || root->NextSiblingElement() || !root->FirstChildElement()) fail("ui.xml", 1, "Expected one non-empty panel");
    for (auto* attribute = root->FirstAttribute(); attribute; attribute = attribute->Next()) if (std::string(attribute->Name()) != "title") fail("ui.xml", root->GetLineNum(), "Unexpected panel attribute");
    plugin.title = root->Attribute("title");
    if (trim(plugin.title).empty() || plugin.title.size() > 256 || !validText(plugin.title)) fail("ui.xml", 1, "Invalid panel title");
    std::set<std::string> ids;
    std::function<Json(const tinyxml2::XMLElement*, int)> node;
    auto textChildren = [](const tinyxml2::XMLElement* element) {
        for (auto* child = element->FirstChild(); child; child = child->NextSibling()) if (child->ToText() && !trim(child->Value()).empty()) fail("ui.xml", child->GetLineNum(), "Controls cannot contain text");
    };
    node = [&](const tinyxml2::XMLElement* element, int depth) -> Json {
        if (depth > 8 || ids.size() >= 128) fail("ui.xml", element->GetLineNum(), "Layout is too deeply nested or has too many controls");
        const std::string kind = element->Name();
        Strings attributes;
        for (auto* attribute = element->FirstAttribute(); attribute; attribute = attribute->Next()) {
            const std::string value = attribute->Value();
            if (value.size() > 256 || trim(value).empty() || !validText(value)) fail("ui.xml", element->GetLineNum(), "Invalid attribute value");
            attributes[attribute->Name()] = value;
        }
        if (!attributes.count("id") || !symbol(attributes["id"]) || !ids.insert(attributes["id"]).second || !attributes.count("label")) fail("ui.xml", element->GetLineNum(), "Control needs a unique ID and a label");
        std::set<std::string> expected{"id", "label"};
        const bool container = kind == "section" || (plugin.sdk == 2 && kind == "row");
        if (kind == "text") { if (attributes.count("field")) expected.insert("field"); }
        else if (kind == "status" || kind == "list" || kind == "field" || (plugin.sdk == 2 && (kind == "metric" || kind == "progress" || kind == "badge"))) expected.insert("field");
        else if (kind == "button") expected.insert("action");
        else if (!container && !(plugin.sdk == 2 && kind == "divider")) fail("ui.xml", element->GetLineNum(), "Unknown control");
        for (const auto& attr : expected) if (!attributes.count(attr)) fail("ui.xml", element->GetLineNum(), "Missing control attribute: " + attr);
        if (plugin.sdk == 2) for (const auto* extension : {"description", "tone", "unit", "placeholder"}) expected.insert(extension);
        for (const auto& attr : attributes) if (!expected.count(attr.first)) fail("ui.xml", element->GetLineNum(), "Unexpected control attribute");
        for (const auto* key : {"field", "action"}) if (attributes.count(key) && !symbol(attributes[key])) fail("ui.xml", element->GetLineNum(), "Invalid field or action");
        textChildren(element);
        Json children = Json::array();
        for (auto* child = element->FirstChildElement(); child; child = child->NextSiblingElement()) {
            if (!container) fail("ui.xml", child->GetLineNum(), "Only sections and rows may contain controls");
            children.push_back(node(child, depth + 1));
        }
        Json result{{"id", attributes["id"]}, {"kind", kind}, {"label", attributes["label"]}, {"children", children}};
        for (const auto* key : {"field", "action", "description", "tone", "unit", "placeholder"}) result[key] = attributes.count(key) ? Json(attributes[key]) : Json(nullptr);
        return result;
    };
    textChildren(root);
    for (auto* sibling = document.FirstChild(); sibling; sibling = sibling->NextSibling()) if (sibling != root) fail("ui.xml", sibling->GetLineNum(), "Expected exactly one panel");
    plugin.nodes = Json::array();
    for (auto* child = root->FirstChildElement(); child; child = child->NextSiblingElement()) plugin.nodes.push_back(node(child, 1));
}

struct LuaRuntime {
    static constexpr size_t memoryLimit = 4 * 1024 * 1024;
    lua_State* state = nullptr;
    size_t used = 0;
    int ticks = 100000;
    std::chrono::steady_clock::time_point deadline;
    int callback = LUA_NOREF;
    bool running = false;
    std::string script;
    const Strings* callValues = nullptr;
    const std::string* callLine = nullptr;

    static void* allocate(void* context, void* pointer, size_t oldSize, size_t newSize) {
        auto* self = static_cast<LuaRuntime*>(context);
        if (!pointer) oldSize = 0;
        if (!newSize) { self->used -= oldSize; std::free(pointer); return nullptr; }
        if (self->running && std::chrono::steady_clock::now() > self->deadline) return nullptr;
        const size_t limit = memoryLimit + (self->running ? 0 : 256 * 1024);
        if (newSize > limit || self->used - oldSize > limit - newSize) return nullptr;
        void* next = std::realloc(pointer, newSize);
        if (next) self->used = self->used - oldSize + newSize;
        return next;
    }
    static LuaRuntime* owner(lua_State* state) {
        void* context = nullptr;
        lua_getallocf(state, &context);
        return static_cast<LuaRuntime*>(context);
    }
    static void hook(lua_State* state, lua_Debug*) {
        auto* self = owner(state);
        self->ticks -= 100;
        if (self->ticks <= 0 || std::chrono::steady_clock::now() > self->deadline) luaL_error(state, "Execution budget exhausted");
    }
    static void charge(lua_State* state, size_t work) {
        auto* self = owner(state);
        if (work >= static_cast<size_t>(std::max(self->ticks, 0)) || std::chrono::steady_clock::now() > self->deadline) luaL_error(state, "Execution budget exhausted");
        self->ticks -= static_cast<int>(std::max<size_t>(work, 1));
    }
    static int boundedString(lua_State* state) {
        size_t work = 0;
        const int arguments = lua_gettop(state), kind = static_cast<int>(lua_tointeger(state, lua_upvalueindex(2)));
        for (int i = 1; i <= arguments; ++i) if (lua_type(state, i) == LUA_TSTRING) work += lua_rawlen(state, i);
        if (kind == 1) {
            size_t length = 0, separator = 0;
            luaL_checklstring(state, 1, &length);
            const auto repeats = luaL_checkinteger(state, 2);
            luaL_optlstring(state, 3, "", &separator);
            if (repeats > 0 && length + separator > 0) {
                if (static_cast<lua_Unsigned>(repeats) > 100000 / (length + separator)) return luaL_error(state, "Execution budget exhausted");
                work += static_cast<size_t>(repeats) * (length + separator);
            }
        }
        charge(state, work + static_cast<size_t>(arguments) * (kind == 2 ? 256 : 4));
        lua_pushvalue(state, lua_upvalueindex(1)); lua_insert(state, 1);
        lua_call(state, arguments, LUA_MULTRET);
        return lua_gettop(state);
    }
    static int boundedPattern(lua_State* state) {
        size_t sourceLength = 0, patternLength = 0;
        luaL_checklstring(state, 1, &sourceLength);
        const char* pattern = luaL_checklstring(state, 2, &patternLength);
        if (sourceLength > 8192 || patternLength > 256) return luaL_error(state, "Pattern input exceeds budget");
        const bool plain = lua_toboolean(state, lua_upvalueindex(2)) && lua_toboolean(state, 4);
        int quantifiers = 0;
        if (!plain) {
            bool inClass = false;
            for (size_t i = 0; i < patternLength; ++i) {
                const char c = pattern[i];
                if (c == '%' && i + 1 < patternLength) {
                    const char escape = pattern[++i];
                    if (escape == 'b' || escape == 'f' || (escape >= '0' && escape <= '9')) return luaL_error(state, "Pattern construct exceeds budget");
                } else if (c == '[') inClass = true;
                else if (c == ']') inClass = false;
                else if (!inClass && (c == '*' || c == '+' || c == '-' || c == '?') && ++quantifiers > 1) return luaL_error(state, "Use at most one pattern quantifier");
            }
            if (quantifiers && (patternLength == 0 || pattern[0] != '^')) return luaL_error(state, "Quantified patterns must start with ^");
            if (quantifiers && lua_toboolean(state, lua_upvalueindex(3))) return luaL_error(state, "gmatch does not support quantified patterns");
        }
        charge(state, sourceLength * (patternLength + 1) + patternLength);
        const int arguments = lua_gettop(state);
        lua_pushvalue(state, lua_upvalueindex(1));
        lua_insert(state, 1);
        lua_call(state, arguments, LUA_MULTRET);
        return lua_gettop(state);
    }
    static int boundedTable(lua_State* state) {
        const int kind = static_cast<int>(lua_tointeger(state, lua_upvalueindex(2)));
        size_t work = 1;
        if (kind == 1) {
            const auto from = luaL_checkinteger(state, 2), to = luaL_checkinteger(state, 3);
            if (to >= from && (from > LUA_MAXINTEGER - 8192 || to > from + 8191)) return luaL_error(state, "Table move exceeds budget");
            if (to >= from) work = static_cast<size_t>(to - from) + 1;
        } else if (kind == 2) {
            if (luaL_optinteger(state, 1, 0) > 8192 || luaL_optinteger(state, 2, 0) > 8192) return luaL_error(state, "Table allocation exceeds budget");
            work = static_cast<size_t>(std::max<lua_Integer>(luaL_optinteger(state, 1, 0), 0) + std::max<lua_Integer>(luaL_optinteger(state, 2, 0), 0));
        } else if (kind == 3) {
            if (lua_gettop(state) > 8192) return luaL_error(state, "Table pack exceeds budget");
            work = static_cast<size_t>(lua_gettop(state));
        } else {
            luaL_checktype(state, 1, LUA_TTABLE);
            if (lua_rawlen(state, 1) > 8192) return luaL_error(state, "Table operation exceeds budget");
            work = lua_rawlen(state, 1);
            if (kind == 5 || kind == 6) {
                size_t bytes = 0;
                for (size_t i = 1; i <= work; ++i) { lua_rawgeti(state, 1, static_cast<lua_Integer>(i)); if (lua_type(state, -1) == LUA_TSTRING) bytes += lua_rawlen(state, -1); lua_pop(state, 1); }
                work += bytes;
                if (kind == 6) work *= 16;
            }
            if (kind == 4) {
                const auto from = luaL_optinteger(state, 2, 1), to = luaL_optinteger(state, 3, static_cast<lua_Integer>(lua_rawlen(state, 1)));
                if (to >= from && (from > LUA_MAXINTEGER - 8192 || to > from + 8191)) return luaL_error(state, "Table unpack exceeds budget");
                if (to >= from) work = static_cast<size_t>(to - from) + 1;
            }
        }
        charge(state, work);
        const int arguments = lua_gettop(state);
        lua_pushvalue(state, lua_upvalueindex(1)); lua_insert(state, 1);
        lua_call(state, arguments, LUA_MULTRET);
        return lua_gettop(state);
    }
    static int initialize(lua_State* state) {
        auto* self = owner(state);
        luaL_requiref(state, "_G", luaopen_base, 1); lua_pop(state, 1);
        luaL_requiref(state, LUA_MATHLIBNAME, luaopen_math, 1); lua_pop(state, 1);
        luaL_requiref(state, LUA_TABLIBNAME, luaopen_table, 1); lua_pop(state, 1);
        luaL_requiref(state, LUA_STRLIBNAME, luaopen_string, 1); lua_pop(state, 1);
        luaL_requiref(state, LUA_UTF8LIBNAME, luaopen_utf8, 1); lua_pop(state, 1);
        for (const auto* name : {"load", "loadfile", "dofile", "require", "collectgarbage", "print", "warn", "pcall", "xpcall", "getmetatable", "setmetatable"}) { lua_pushnil(state); lua_setglobal(state, name); }
        lua_getglobal(state, "string");
        for (const auto* name : {"dump", "pack", "packsize", "unpack"}) { lua_pushnil(state); lua_setfield(state, -2, name); }
        for (const auto* name : {"find", "match", "gsub", "gmatch"}) {
            lua_getfield(state, -1, name);
            lua_pushboolean(state, std::strcmp(name, "find") == 0);
            lua_pushboolean(state, std::strcmp(name, "gmatch") == 0);
            lua_pushcclosure(state, boundedPattern, 3);
            lua_setfield(state, -2, name);
        }
        for (const auto* name : {"byte", "char", "format", "len", "lower", "rep", "reverse", "sub", "upper"}) {
            lua_getfield(state, -1, name);
            lua_pushinteger(state, std::strcmp(name, "rep") == 0 ? 1 : std::strcmp(name, "format") == 0 ? 2 : 0);
            lua_pushcclosure(state, boundedString, 2); lua_setfield(state, -2, name);
        }
        lua_pop(state, 1);
        lua_getglobal(state, "utf8");
        for (const auto* name : {"char", "codes", "codepoint", "len", "offset"}) {
            lua_getfield(state, -1, name); lua_pushinteger(state, 0);
            lua_pushcclosure(state, boundedString, 2); lua_setfield(state, -2, name);
        }
        lua_pop(state, 1);
        for (const auto* name : {"tonumber", "tostring", "rawequal"}) {
            lua_getglobal(state, name); lua_pushinteger(state, 0);
            lua_pushcclosure(state, boundedString, 2); lua_setglobal(state, name);
        }
        lua_getglobal(state, "table");
        const char* tableFunctions[] = {"concat", "insert", "remove", "sort", "move", "create", "pack", "unpack"};
        const int tableKinds[] = {5, 0, 0, 6, 1, 2, 3, 4};
        for (int i = 0; i < 8; ++i) {
            lua_getfield(state, -1, tableFunctions[i]); lua_pushinteger(state, tableKinds[i]);
            lua_pushcclosure(state, boundedTable, 2); lua_setfield(state, -2, tableFunctions[i]);
        }
        lua_pop(state, 1);
        if (luaL_loadbufferx(state, self->script.data(), self->script.size(), "@business.lua", "t") != LUA_OK) return lua_error(state);
        lua_call(state, 0, 1);
        if (!lua_istable(state, -1)) return luaL_error(state, "Lua must return a plugin table");
        return 1;
    }
    static int invoke(lua_State* state) {
        auto* self = owner(state);
        lua_rawgeti(state, LUA_REGISTRYINDEX, self->callback);
        lua_pushlstring(state, self->callLine->data(), self->callLine->size());
        lua_createtable(state, 0, static_cast<int>(self->callValues->size()));
        for (const auto& entry : *self->callValues) {
            lua_pushlstring(state, entry.second.data(), entry.second.size());
            lua_setfield(state, -2, entry.first.c_str());
        }
        lua_call(state, 2, 1);
        return 1;
    }
    void protectedCall(lua_CFunction function) {
        ticks = 100000;
        deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(25);
        lua_sethook(state, hook, LUA_MASKCOUNT, 100);
        lua_pushcfunction(state, function);
        running = true;
        const int status = lua_pcall(state, 0, 1, 0);
        running = false;
        lua_sethook(state, nullptr, 0, 0);
        if (status != LUA_OK) {
            const char* message = lua_tostring(state, -1);
            const std::string error = message ? excerpt(message) : "Lua execution failed";
            lua_settop(state, 0);
            int line = 1;
            if (starts(error, "business.lua:")) {
                const auto end = error.find(':', 13);
                const auto number = error.substr(13, end == std::string::npos ? end : end - 13);
                if (!number.empty() && number.size() < 8 && std::all_of(number.begin(), number.end(), [](char c) { return c >= '0' && c <= '9'; })) line = std::max(1, std::stoi(number));
            }
            fail("business.lua", line, error);
        }
    }
    static Strings stringMap(lua_State* state, int index, size_t maxSize, bool multiline) {
        if (!lua_istable(state, index)) fail("business.lua", 1, "Expected a string table");
        index = lua_absindex(state, index);
        Strings result;
        lua_pushnil(state);
        while (lua_next(state, index)) {
            if (lua_type(state, -2) != LUA_TSTRING || lua_type(state, -1) != LUA_TSTRING) fail("business.lua", 1, "Table keys and values must be strings");
            size_t keySize = 0, valueSize = 0;
            const char* key = lua_tolstring(state, -2, &keySize);
            const char* value = lua_tolstring(state, -1, &valueSize);
            std::string k(key, keySize), v(value, valueSize);
            if (!symbol(k) || v.size() > maxSize || !validText(v, multiline) || result.size() >= 128) fail("business.lua", 1, "Invalid state or command field");
            result[k] = v;
            lua_pop(state, 1);
        }
        return result;
    }
    LuaRuntime(Plugin& plugin, const std::string& source) : script(source) {
        if (script.empty() || script.size() > 131072) fail("business.lua", 1, "Lua source is empty or too large");
        state = lua_newstate(allocate, this, 0x524f4c49);
        if (!state) fail("business.lua", 1, "Cannot allocate Lua state");
        try {
            protectedCall(initialize);
            lua_getfield(state, -1, "initial");
            plugin.initial = stringMap(state, -1, 512, false); lua_pop(state, 1);
            lua_getfield(state, -1, "commands");
            if (!lua_isnil(state, -1)) {
                if (!lua_istable(state, -1)) fail("business.lua", 1, "commands must be a table");
                lua_pushnil(state);
                while (lua_next(state, -2)) {
                    if (lua_type(state, -2) != LUA_TSTRING || !lua_istable(state, -1) || plugin.commands.size() >= 128) fail("business.lua", 1, "Invalid command table");
                    size_t size = 0; const char* name = lua_tolstring(state, -2, &size);
                    std::string id(name, size);
                    if (!symbol(id)) fail("business.lua", 1, "Invalid action ID");
                    plugin.commands[id] = compileCommand(stringMap(state, -1, 512, false), 2, "business.lua", 1);
                    lua_pop(state, 1);
                }
            }
            lua_pop(state, 1);
            lua_getfield(state, -1, "on_log");
            if (!lua_isfunction(state, -1)) fail("business.lua", 1, "on_log must be a function");
            callback = luaL_ref(state, LUA_REGISTRYINDEX);
            lua_settop(state, 0);
        } catch (...) { lua_close(state); state = nullptr; throw; }
    }
    ~LuaRuntime() { if (state) lua_close(state); }
    Strings accept(const std::string& line, const Strings& values) {
        callValues = &values; callLine = &line;
        try {
            protectedCall(invoke);
            Strings updates;
            if (!lua_isnil(state, -1)) updates = stringMap(state, -1, 8192, true);
            lua_settop(state, 0);
            for (const auto& entry : updates) if (!values.count(entry.first) || (entry.second.find('\n') == std::string::npos && entry.second.size() > 512)) fail("business.lua", 1, "Update targets unknown state or exceeds value limit");
            return updates;
        } catch (...) { lua_settop(state, 0); throw; }
    }
};

std::unique_ptr<Plugin> compile(const Json& source) {
    allowedKeys(source, {"manifest", "ui", "rules", "lua"}, "request.json");
    const auto manifestText = stringAt(source, "manifest", "plugin.json");
    if (manifestText.size() > 16384) fail("plugin.json", 1, "Manifest is too large");
    auto manifest = parseJson(manifestText, "plugin.json");
    if (!manifest.is_object() || !manifest.contains("sdkVersion") || !manifest["sdkVersion"].is_number_integer()) fail("plugin.json", 1, "Expected an SDK version");
    auto plugin = std::make_unique<Plugin>();
    if (manifest["sdkVersion"] != 1 && manifest["sdkVersion"] != 2) fail("plugin.json", 1, "Unsupported SDK version");
    plugin->sdk = manifest["sdkVersion"].get<int>();
    allowedKeys(manifest, plugin->sdk == 1 ? std::set<std::string>{"id", "version", "sdkVersion", "server"} : std::set<std::string>{"id", "version", "sdkVersion", "server", "engine", "name", "description", "compatibility"}, "plugin.json");
    if (plugin->sdk == 1 && manifestText.size() > 8192) fail("plugin.json", 1, "Manifest is too large");
    plugin->id = stringAt(manifest, "id", "plugin.json");
    plugin->version = stringAt(manifest, "version", "plugin.json");
    plugin->server = stringAt(manifest, "server", "plugin.json");
    const auto idParts = split(plugin->id, ".");
    static const std::regex version("(?:0|[1-9][0-9]{0,8})(?:\\.(?:0|[1-9][0-9]{0,8})){2}");
    if (plugin->id.size() > 96 || idParts.size() < 2 || !std::all_of(idParts.begin(), idParts.end(), symbol)) fail("plugin.json", 1, "Use a namespaced plugin ID");
    if (!std::regex_match(plugin->version, version) || !symbol(plugin->server)) fail("plugin.json", 1, "Invalid version or server family");
    if (plugin->sdk == 2) {
        plugin->engine = stringAt(manifest, "engine", "plugin.json");
        if (plugin->engine != "yaml" && plugin->engine != "lua") fail("plugin.json", 1, "Unsupported engine");
        for (const auto* optional : {"name", "description"}) if (manifest.contains(optional)) {
            const auto value = stringAt(manifest, optional, "plugin.json");
            if (value.size() > 512 || !validText(value)) fail("plugin.json", 1, "Invalid metadata string");
        }
        if (manifest.contains("compatibility") && !manifest["compatibility"].is_object()) fail("plugin.json", 1, "compatibility must be an object");
    }
    parseXml(*plugin, stringAt(source, "ui", "ui.xml"));
    if (plugin->engine == "lua") plugin->lua = std::make_unique<LuaRuntime>(*plugin, stringAt(source, "lua", "business.lua"));
    else parseYaml(*plugin, stringAt(source, "rules", "rules.yaml", true));
    std::set<std::string> fields, inputs;
    for (const auto& entry : plugin->initial) fields.insert(entry.first);
    for (const auto& rule : plugin->rules) fields.insert(rule.field);
    std::function<void(const Json&)> validate = [&](const Json& nodes) {
        for (const auto& node : nodes) {
            if (!node["field"].is_null()) {
                const auto field = node["field"].get<std::string>();
                if (node["kind"] == "field") inputs.insert(field);
                else if (!fields.count(field)) fail("ui.xml", 1, "Unknown state field: " + field);
            }
            if (!node["action"].is_null() && !plugin->commands.count(node["action"].get<std::string>())) fail("ui.xml", 1, "Unknown action");
            validate(node["children"]);
        }
    };
    validate(plugin->nodes);
    for (const auto& command : plugin->commands) for (const auto& input : command.second.inputs) if (!inputs.count(input.first)) fail(plugin->engine == "lua" ? "business.lua" : "rules.yaml", 1, "Command needs a matching UI input: " + input.first);
    plugin->source = source;
    return plugin;
}

Json builtinSource() {
    return {{"manifest", R"({"id":"rolithax.paper","version":"1.0.0","sdkVersion":1,"server":"paper"})"},
        {"ui", R"(<panel title="Paper 服务器"><section id="server" label="服务端"><status id="lifecycle-status" label="日志状态" field="lifecycle"/></section><section id="players" label="玩家"><text id="player-summary" label="最近一次查询" field="player-summary"/><list id="online-list" label="查询返回的玩家" field="online-players"/><button id="refresh" label="查询在线玩家" action="refresh-players"/><list id="activity-list" label="玩家进出" field="activity"/></section></panel>)"},
        {"rules", R"(initial:
  lifecycle: "等待服务端日志"
  player-summary: "等待查询"
  online-players: ""
  activity: ""
rules:
  started:
    prefix: "Starting minecraft server version "
    field: "lifecycle"
    value: "正在启动"
  ready:
    prefix: "Done ("
    contains: "For help, type"
    field: "lifecycle"
    value: "启动完成"
  stopping:
    prefix: "Stopping server"
    field: "lifecycle"
    value: "正在停止"
  players:
    prefix: "There are "
    field: "online-players"
    capture: "online"
  player-summary:
    prefix: "There are "
    contains: " players online:"
    field: "player-summary"
    capture: "online-summary"
  join:
    suffix: " joined the game"
    field: "activity"
    operation: "append"
    capture: "player-event"
  leave:
    suffix: " left the game"
    field: "activity"
    operation: "append"
    capture: "player-event"
commands:
  refresh-players:
    text: "list"
)"}};
}

std::string stripLog(const std::string& raw) {
    std::string clean;
    clean.reserve(raw.size());
    for (size_t i = 0; i < raw.size();) {
        if (raw[i] == '\x1b' && i + 1 < raw.size() && raw[i + 1] == '[') {
            size_t end = i + 2;
            while (end < raw.size() && end - i - 2 < 32 && ((raw[end] >= '0' && raw[end] <= '9') || raw[end] == ';')) ++end;
            if (end < raw.size() && raw[end] == 'm') { i = end + 1; continue; }
        }
        clean += raw[i++];
    }
    return clean;
}
std::string messageBody(const std::string& plain) {
    static const std::regex envelope("^\\[[0-9]{2}:[0-9]{2}:[0-9]{2}\\](?:[ ]+\\[[^\\]\\r\\n]{1,96}\\]){1,2}:[ ]*");
    std::smatch match;
    return std::regex_search(plain, match, envelope) ? plain.substr(match.length()) : plain;
}
struct Model {
    std::unique_ptr<Plugin> plugin;
    std::set<std::string> allowed;
    Strings values, evidence;
    std::map<unsigned char, std::vector<size_t>> index;
    std::vector<size_t> fallback;
    Model(std::unique_ptr<Plugin> next, std::set<std::string> grants) : plugin(std::move(next)), allowed(std::move(grants)), values(plugin->initial) {
        for (const auto& grant : allowed) if (!plugin->commands.count(grant)) fail("request.json", 1, "Unknown command grant");
        std::set<unsigned char> first;
        for (const auto& rule : plugin->rules) if (!rule.prefix.empty()) first.insert(static_cast<unsigned char>(rule.prefix.front()));
        for (size_t i = 0; i < plugin->rules.size(); ++i) if (plugin->rules[i].prefix.empty()) fallback.push_back(i);
        for (unsigned char c : first) for (size_t i = 0; i < plugin->rules.size(); ++i) if (plugin->rules[i].prefix.empty() || static_cast<unsigned char>(plugin->rules[i].prefix.front()) == c) index[c].push_back(i);
    }
    bool accept(const std::string& raw) {
        const auto plain = stripLog(raw);
        if (!validText(plain)) return false;
        const auto line = messageBody(plain), proof = excerpt(plain);
        Strings updates;
        if (plugin->lua) updates = plugin->lua->accept(line, values);
        else {
            const auto found = line.empty() ? index.end() : index.find(static_cast<unsigned char>(line.front()));
            const auto& candidates = found == index.end() ? fallback : found->second;
            for (size_t at : candidates) {
                const auto& rule = plugin->rules[at];
                const auto value = rule.match(line);
                if (!value) continue;
                if (rule.operation == "append") {
                    const auto previous = updates.count(rule.field) ? updates[rule.field] : values.count(rule.field) ? values[rule.field] : "";
                    auto lines = split(previous, "\n");
                    lines.erase(std::remove(lines.begin(), lines.end(), ""), lines.end());
                    lines.push_back(*value);
                    std::string joined;
                    for (size_t i = lines.size() > 16 ? lines.size() - 16 : 0; i < lines.size(); ++i) { if (!joined.empty()) joined += '\n'; joined += lines[i]; }
                    updates[rule.field] = joined;
                } else updates[rule.field] = *value;
            }
        }
        bool changed = false;
        for (const auto& entry : updates) {
            auto oldValue = values.find(entry.first), oldProof = evidence.find(entry.first);
            if (oldValue == values.end() || oldValue->second != entry.second || oldProof == evidence.end() || oldProof->second != proof) changed = true;
        }
        for (const auto& entry : updates) { values[entry.first] = entry.second; evidence[entry.first] = proof; }
        return changed;
    }
};

std::set<std::string> allCommands(const Plugin& plugin) {
    std::set<std::string> result;
    for (const auto& entry : plugin.commands) result.insert(entry.first);
    return result;
}
struct Session {
    std::mutex mutex;
    std::unique_ptr<Model> model;
    std::deque<std::string> history;
    size_t historySize = 0;
    uint64_t revision = 0, packageRevision = 0;
    bool stale = false;
    Session() {
        auto plugin = compile(builtinSource());
        auto allowed = allCommands(*plugin);
        model = std::make_unique<Model>(std::move(plugin), std::move(allowed));
    }
    Json snapshot() const {
        return {{"revision", revision}, {"packageRevision", packageRevision}, {"pluginId", model->plugin->id}, {"title", model->plugin->title}, {"stale", stale}, {"nodes", model->plugin->nodes}, {"values", model->values}, {"evidence", model->evidence}};
    }
    Json metadata(const Plugin& plugin) const {
        Json commands = Json::object();
        for (const auto& entry : plugin.commands) commands[entry.first] = entry.second.text;
        return {{"id", plugin.id}, {"version", plugin.version}, {"server", plugin.server}, {"title", plugin.title}, {"nodes", plugin.nodes}, {"initial", plugin.initial}, {"commands", commands}, {"engine", plugin.engine}, {"sdkVersion", plugin.sdk}};
    }
    bool gap() { if (stale) return false; stale = true; ++revision; return true; }
    bool log(std::string text) {
        if (text.size() > 8192) return gap();
        while (!text.empty() && (text.back() == '\r' || text.back() == '\n')) text.pop_back();
        if (trim(text).empty() || text.find_first_of("\r\n") != std::string::npos) return false;
        const bool changed = model->accept(text);
        history.push_back(text); historySize += text.size();
        while (history.size() > 256 || historySize > 262144) { historySize -= history.front().size(); history.pop_front(); }
        if (changed) ++revision;
        return changed;
    }
    Json install(std::unique_ptr<Plugin> plugin, std::set<std::string> allowed) {
        auto next = std::make_unique<Model>(std::move(plugin), std::move(allowed));
        for (const auto& line : history) next->accept(line);
        model = std::move(next); ++packageRevision; ++revision;
        return snapshot();
    }
    Json request(const Json& request) {
        if (!request.is_object()) fail("request.json", 1, "Expected a request object");
        const auto op = stringAt(request, "op", "request.json");
        if (op == "snapshot") return snapshot();
        if (op == "inspect" || op == "reload") {
            if (!request.contains("source")) fail("request.json", 1, "Missing source");
            auto plugin = compile(request["source"]);
            if (op == "inspect") return metadata(*plugin);
            if (plugin->id == "rolithax.paper") fail("plugin.json", 1, "Reserved built-in plugin ID");
            std::set<std::string> allowed;
            if (request.contains("allowedCommands")) {
                if (!request["allowedCommands"].is_array() || request["allowedCommands"].size() > 128) fail("request.json", 1, "Invalid command grants");
                for (const auto& grant : request["allowedCommands"]) { if (!grant.is_string()) fail("request.json", 1, "Invalid command grant"); allowed.insert(grant.get<std::string>()); }
            }
            return install(std::move(plugin), std::move(allowed));
        }
        if (op == "log") return log(stringAt(request, "text", "request.json"));
        if (op == "gap") return gap();
        if (op == "reset") {
            auto replacement = std::make_unique<Model>(compile(model->plugin->source), model->allowed);
            model = std::move(replacement); history.clear(); historySize = 0; stale = false; ++packageRevision; ++revision;
            return true;
        }
        if (op == "restore") {
            auto plugin = compile(builtinSource()); auto grants = allCommands(*plugin);
            return install(std::move(plugin), std::move(grants));
        }
        if (op == "command") {
            const auto action = stringAt(request, "actionId", "request.json");
            if (!model->allowed.count(action)) return nullptr;
            if (request.contains("expectedRevision") && !request["expectedRevision"].is_null()) {
                const auto& expected = request["expectedRevision"];
                if (!expected.is_number_unsigned() && (!expected.is_number_integer() || expected.get<int64_t>() < 0)) return nullptr;
                if (expected.get<uint64_t>() != packageRevision) return nullptr;
            }
            Strings arguments;
            if (request.contains("arguments")) {
                if (!request["arguments"].is_object() || request["arguments"].size() > 128) return nullptr;
                for (auto it = request["arguments"].begin(); it != request["arguments"].end(); ++it) { if (!it.value().is_string()) return nullptr; arguments[it.key()] = it.value().get<std::string>(); }
            }
            const auto command = model->plugin->commands.find(action);
            if (command == model->plugin->commands.end()) return nullptr;
            const auto result = command->second.resolve(arguments);
            return result ? Json(*result) : Json(nullptr);
        }
        fail("request.json", 1, "Unknown operation");
    }
};

std::mutex sessionsMutex;
std::unordered_map<uint64_t, std::shared_ptr<Session>> sessions;
uint64_t nextSession = 1;
char* copyResponse(const Json& result) {
    const auto text = result.dump(-1, ' ', false, Json::error_handler_t::replace);
    auto* copy = static_cast<char*>(std::malloc(text.size() + 1));
    if (copy) std::memcpy(copy, text.c_str(), text.size() + 1);
    return copy;
}
Json error(const std::string& file, int line, const std::string& message) {
    return {{"ok", false}, {"error", {{"file", file}, {"line", line}, {"message", message}}}};
}
}

extern "C" uint64_t rolithax_schema_create(void) {
    try {
        auto session = std::make_shared<Session>();
        std::lock_guard<std::mutex> guard(sessionsMutex);
        if (sessions.size() >= 1024 || !nextSession) return 0;
        const auto id = nextSession++;
        sessions.emplace(id, std::move(session));
        return id;
    } catch (...) { return 0; }
}
extern "C" void rolithax_schema_destroy(uint64_t session) {
    try { std::lock_guard<std::mutex> guard(sessionsMutex); sessions.erase(session); } catch (...) {}
}
extern "C" char* rolithax_schema_request(uint64_t id, const char* requestJson) {
    try {
        if (!requestJson) return copyResponse(error("request.json", 1, "Null request"));
        size_t size = 0;
        while (size <= 524288 && requestJson[size]) ++size;
        if (size > 524288) return copyResponse(error("request.json", 1, "Request is too large"));
        std::shared_ptr<Session> session;
        { std::lock_guard<std::mutex> guard(sessionsMutex); const auto it = sessions.find(id); if (it != sessions.end()) session = it->second; }
        if (!session) return copyResponse(error("request.json", 1, "Unknown or closed session"));
        const auto request = parseJson(std::string(requestJson, size), "request.json");
        std::lock_guard<std::mutex> guard(session->mutex);
        return copyResponse({{"ok", true}, {"data", session->request(request)}});
    } catch (const Failure& e) {
        try { return copyResponse(error(e.file, e.line, e.what())); } catch (...) { return nullptr; }
    } catch (const std::exception&) {
        try { return copyResponse(error("request.json", 1, "Invalid request or native resource limit reached")); } catch (...) { return nullptr; }
    } catch (...) { return nullptr; }
}
extern "C" void rolithax_schema_free(char* value) { std::free(value); }
