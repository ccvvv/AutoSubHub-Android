cmake_minimum_required(VERSION 3.22.1)
project(autosubhub_runtime_probe C)

add_executable(runtime_probe runtime_probe.c)

# Package this tiny PIE executable beside native libraries. The .so suffix is
# required for Android's native-library packaging pipeline; the ELF is still
# an executable and is invoked as a child process, never dlopen()'d.
set(RUNTIME_PROBE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
file(MAKE_DIRECTORY "${RUNTIME_PROBE_DIR}")

set_target_properties(runtime_probe PROPERTIES
    OUTPUT_NAME "libruntime_probe"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${RUNTIME_PROBE_DIR}"
)

target_compile_options(runtime_probe PRIVATE -fPIE -Wall -Wextra -Werror)
target_link_options(runtime_probe PRIVATE -fPIE -pie)
