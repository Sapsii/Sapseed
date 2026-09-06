cmake_minimum_required(VERSION 3.22.1)
project(sapseed_preprocess LANGUAGES CXX)

add_library(sapseed_preprocess SHARED rgba_tensor_writer.cpp)
target_compile_features(sapseed_preprocess PRIVATE cxx_std_20)
target_compile_options(sapseed_preprocess PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    -march=armv8.2-a+fp16+dotprod
)
