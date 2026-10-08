# The i2pd router as an Android executable, from source.
# Called by build.sh with the NDK toolchain; the sources are unpacked there.
# Like PurpleI2P's own Android binary: std::filesystem, Boost.Program_options, static OpenSSL, the system zlib.

cmake_minimum_required(VERSION 3.22)
project(i2pd CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

foreach (dir I2PD_SRC BOOST_SRC OPENSSL_DIR)
    if (NOT DEFINED ${dir})
        message(FATAL_ERROR "${dir} not set")
    endif ()
endforeach ()

file(GLOB I2PD_SOURCES
        ${I2PD_SRC}/libi2pd/*.cpp
        ${I2PD_SRC}/libi2pd_client/*.cpp
        ${I2PD_SRC}/i18n/*.cpp)
list(APPEND I2PD_SOURCES
        ${I2PD_SRC}/daemon/UnixDaemon.cpp
        ${I2PD_SRC}/daemon/Daemon.cpp
        ${I2PD_SRC}/daemon/UPnP.cpp
        ${I2PD_SRC}/daemon/HTTPServer.cpp
        ${I2PD_SRC}/daemon/I2PControl.cpp
        ${I2PD_SRC}/daemon/I2PControlHandlers.cpp
        ${I2PD_SRC}/daemon/i2pd.cpp)

file(GLOB BOOST_PO_SOURCES ${BOOST_SRC}/libs/program_options/src/*.cpp)

add_executable(i2pd ${I2PD_SOURCES} ${BOOST_PO_SOURCES})

target_include_directories(i2pd PRIVATE
        ${I2PD_SRC}/libi2pd
        ${I2PD_SRC}/libi2pd_client
        ${I2PD_SRC}/i18n
        ${I2PD_SRC}/daemon)
target_include_directories(i2pd SYSTEM PRIVATE
        ${BOOST_SRC}
        ${OPENSSL_DIR}/include)

target_compile_definitions(i2pd PRIVATE
        ANDROID ANDROID_BINARY __ANDROID__
        STD_FILESYSTEM=1
        BOOST_ALL_NO_LIB)
if (ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_definitions(i2pd PRIVATE ANDROID_ARM7A)
endif ()
target_compile_options(i2pd PRIVATE -fexceptions -frtti -Wno-deprecated-declarations)

target_link_libraries(i2pd PRIVATE
        ${OPENSSL_DIR}/lib/libssl.a
        ${OPENSSL_DIR}/lib/libcrypto.a
        z dl atomic)

# Android 15+ devices may use 16 KB pages
target_link_options(i2pd PRIVATE -pie -Wl,-z,max-page-size=16384 -s)
