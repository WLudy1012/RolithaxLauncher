LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE     := rolithaxvm
LOCAL_SRC_FILES  := rolithaxvm.c
LOCAL_LDLIBS     := -llog -ldl
LOCAL_CFLAGS     := -O2 -Wall
include $(BUILD_SHARED_LIBRARY)
