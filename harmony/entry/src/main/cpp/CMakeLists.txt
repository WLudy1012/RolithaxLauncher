cmake_minimum_required(VERSION 3.5)
project(rolithax_business C)

set(DOMAIN_OUTPUT "${CMAKE_CURRENT_LIST_DIR}/../../../../kmp-domain/build/bin/ohosArm64/debugShared")
set(DOMAIN_LIBRARY "${CMAKE_CURRENT_LIST_DIR}/../../../libs/arm64-v8a/librolithax_domain.so")
if(NOT EXISTS "${DOMAIN_OUTPUT}/librolithax_domain_api.h" OR NOT EXISTS "${DOMAIN_LIBRARY}")
    message(FATAL_ERROR "请先运行 :prepareHarmonyNative 构建鸿蒙共享领域库")
endif()

add_library(rolithax_domain SHARED IMPORTED)
set_target_properties(rolithax_domain PROPERTIES IMPORTED_LOCATION "${DOMAIN_LIBRARY}")
add_library(rolithax_business SHARED rolithax_business.c)
target_include_directories(rolithax_business PRIVATE "${DOMAIN_OUTPUT}")
target_link_libraries(rolithax_business PRIVATE rolithax_domain libace_napi.z.so)
