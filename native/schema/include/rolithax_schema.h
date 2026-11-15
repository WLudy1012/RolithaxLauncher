#pragma once
#include <stdint.h>

#if defined(_WIN32)
#  if defined(ROLITHAX_SCHEMA_BUILD)
#    define ROLITHAX_SCHEMA_API __declspec(dllexport)
#  else
#    define ROLITHAX_SCHEMA_API __declspec(dllimport)
#  endif
#else
#  define ROLITHAX_SCHEMA_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif
ROLITHAX_SCHEMA_API uint64_t rolithax_schema_create(void);
ROLITHAX_SCHEMA_API void rolithax_schema_destroy(uint64_t session);
ROLITHAX_SCHEMA_API char* rolithax_schema_request(uint64_t session, const char* request_json);
ROLITHAX_SCHEMA_API void rolithax_schema_free(char* value);
#ifdef __cplusplus
}
#endif
