cmake_minimum_required(VERSION 3.6.0)
add_library(scrypt SHARED scrypt_jni.c crypto_scrypt-nosse.c sha256.c)
set_target_properties(scrypt PROPERTIES LINK_FLAGS -Wl,--build-id=none)
add_library(x11 SHARED aes_helper.c blake.c bmw.c cubehash.c echo.c groestl.c hashblock.cpp keccak.c jh.c luffa.c shavite.c simd.c skein.c)
set_target_properties(x11 PROPERTIES LINK_FLAGS -Wl,--build-id=none)
include_directories(.)

add_subdirectory(dashj-bls)
