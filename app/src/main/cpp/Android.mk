LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE     := rolithaxvm
LOCAL_SRC_FILES  := rolithaxvm.c
LOCAL_LDLIBS     := -llog -ldl
LOCAL_CFLAGS     := -O2 -Wall
include $(BUILD_SHARED_LIBRARY)

SCHEMA_ROOT := $(LOCAL_PATH)/../../../../native/schema
SCHEMA_LUA := $(wildcard $(SCHEMA_ROOT)/vendor/lua/*.c)
SCHEMA_LUA := $(filter-out $(addprefix $(SCHEMA_ROOT)/vendor/lua/,lua.c luac.c linit.c lcorolib.c ldblib.c liolib.c loadlib.c loslib.c),$(SCHEMA_LUA))

include $(CLEAR_VARS)
LOCAL_MODULE := rolithax_schema
LOCAL_SRC_FILES := schema_jni.cpp ../../../../native/schema/src/schema.cpp ../../../../native/schema/vendor/tinyxml2.cpp $(patsubst $(LOCAL_PATH)/%,%,$(SCHEMA_LUA))
LOCAL_C_INCLUDES := $(SCHEMA_ROOT)/include $(SCHEMA_ROOT)/vendor $(SCHEMA_ROOT)/vendor/lua
LOCAL_CFLAGS := -O2 -fvisibility=hidden -DROLITHAX_SCHEMA_BUILD
LOCAL_CPPFLAGS := -std=c++17 -fvisibility-inlines-hidden
LOCAL_CPP_FEATURES := exceptions rtti
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
