#include <napi/native_api.h>
#include "librolithax_poc_api.h"
#include <stdlib.h>
#include <string.h>

static char *read_string(napi_env env, napi_value value, size_t limit) {
    size_t length = 0;
    if (napi_get_value_string_utf8(env, value, NULL, 0, &length) != napi_ok || length > limit) {
        return NULL;
    }
    char *buffer = malloc(length + 1);
    if (buffer == NULL) return NULL;
    size_t written = 0;
    if (napi_get_value_string_utf8(env, value, buffer, length + 1, &written) != napi_ok ||
        written != length || strlen(buffer) != length) {
        free(buffer);
        return NULL;
    }
    return buffer;
}

static napi_value resource_key(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value argv[2];
    if (napi_get_cb_info(env, info, &argc, argv, NULL, NULL) != napi_ok || argc != 2) {
        napi_throw_type_error(env, NULL, "Expected resource type and query strings");
        return NULL;
    }
    char *type = read_string(env, argv[0], 256);
    char *query = read_string(env, argv[1], 4096);
    if (type == NULL || query == NULL) {
        free(type);
        free(query);
        napi_throw_type_error(env, NULL, "Invalid resource type or query");
        return NULL;
    }
    librolithax_poc_ExportedSymbols *symbols = librolithax_poc_symbols();
    const char *value = symbols->kotlin.root.com.wludy.rolithax.poc.resourceFilterKey(type, query);
    free(type);
    free(query);
    if (value == NULL) {
        napi_throw_error(env, NULL, "Kotlin probe returned no value");
        return NULL;
    }
    napi_value result;
    napi_status status = napi_create_string_utf8(env, value, NAPI_AUTO_LENGTH, &result);
    symbols->DisposeString(value);
    if (status != napi_ok) {
        napi_throw_error(env, NULL, "Could not create probe result");
        return NULL;
    }
    return result;
}

static napi_value init(napi_env env, napi_value exports) {
    napi_property_descriptor properties[] = {
        { "resourceKey", NULL, resource_key, NULL, NULL, NULL, napi_default, NULL }
    };
    if (napi_define_properties(env, exports, 1, properties) != napi_ok) {
        napi_throw_error(env, NULL, "Could not register probe");
        return NULL;
    }
    return exports;
}

NAPI_MODULE(rolithax_probe, init)
