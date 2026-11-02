cmake_minimum_required(VERSION 3.22.1)
project(aistudio_android LANGUAGES C CXX)

include(FetchContent)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG 5c2ea1ab11a17a750dabffe31e8c182ddf4966cc
    GIT_SUBMODULES_RECURSE TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(aistudio SHARED llama_jni.cpp)
target_compile_features(aistudio PRIVATE cxx_std_17)
target_link_libraries(aistudio PRIVATE llama android log)
