#include <napi/native_api.h>
#include "librolithax_domain_api.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static napi_value normalize_payload(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value arg;
    void *kind = NULL;
    size_t length = 0;
    if (napi_get_cb_info(env, info, &argc, &arg, NULL, &kind) != napi_ok || argc != 1 ||
        napi_get_value_string_utf8(env, arg, NULL, 0, &length) != napi_ok || length > 4 * 1024 * 1024) {
        napi_throw_type_error(env, NULL, "Invalid Daemon response");
        return NULL;
    }
    char *payload = malloc(length + 1);
    if (payload == NULL) {
        napi_throw_error(env, NULL, "Cannot allocate Daemon response");
        return NULL;
    }
    size_t written = 0;
    if (napi_get_value_string_utf8(env, arg, payload, length + 1, &written) != napi_ok ||
        written != length || strlen(payload) != length) {
        free(payload);
        napi_throw_type_error(env, NULL, "Invalid Daemon response");
        return NULL;
    }
    librolithax_domain_ExportedSymbols *symbols = librolithax_domain_symbols();
    const char *normalized = NULL;
    switch ((intptr_t)kind) {
        case 1: normalized = symbols->kotlin.root.com.wludy.rolithax.launcher.shared.normalizeInstanceList(payload); break;
        case 2: normalized = symbols->kotlin.root.com.wludy.rolithax.launcher.shared.normalizeInstanceInfo(payload); break;
        case 3: normalized = symbols->kotlin.root.com.wludy.rolithax.launcher.shared.normalizeFileList(payload); break;
        case 4: normalized = symbols->kotlin.root.com.wludy.rolithax.launcher.shared.normalizeFileContent(payload); break;
        default: break;
    }
    free(payload);
    if (normalized == NULL) {
        napi_throw_error(env, NULL, "Cannot parse Daemon response");
        return NULL;
    }
    napi_value result;
    napi_status status = napi_create_string_utf8(env, normalized, NAPI_AUTO_LENGTH, &result);
    symbols->DisposeString(normalized);
    if (status != napi_ok) {
        napi_throw_error(env, NULL, "Cannot return Daemon response");
        return NULL;
    }
    return result;
}

static napi_value init(napi_env env, napi_value exports) {
    napi_property_descriptor properties[] = {
        { "normalizeInstanceList", NULL, normalize_payload, NULL, NULL, NULL, napi_default, (void *)(intptr_t)1 },
        { "normalizeInstanceInfo", NULL, normalize_payload, NULL, NULL, NULL, napi_default, (void *)(intptr_t)2 },
        { "normalizeFileList", NULL, normalize_payload, NULL, NULL, NULL, napi_default, (void *)(intptr_t)3 },
        { "normalizeFileContent", NULL, normalize_payload, NULL, NULL, NULL, napi_default, (void *)(intptr_t)4 }
    };
    if (napi_define_properties(env, exports, sizeof(properties) / sizeof(properties[0]), properties) != napi_ok) {
        napi_throw_error(env, NULL, "Cannot register shared domain");
        return NULL;
    }
    return exports;
}

NAPI_MODULE(rolithax_business, init)
