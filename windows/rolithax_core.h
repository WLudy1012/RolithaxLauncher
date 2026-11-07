#ifndef ROLITHAX_CORE_H
#define ROLITHAX_CORE_H

#ifdef __cplusplus
extern "C" {
#endif

int rolithax_abi_version(void);
/* The caller releases returned UTF-8 strings and closes each session after all of its calls finish. */
void *rolithax_session_open(const char *config_json, char **error_json);
char *rolithax_session_call(void *session, const char *operation, const char *args_json);
void rolithax_session_close(void *session);
void rolithax_string_free(char *value);

#ifdef __cplusplus
}
#endif

#endif
