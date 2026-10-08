#!/bin/bash
# Builds the i2pd router for Android from source: no binaries in the repository.
#
#   i2pd/build.sh <ndk dir> <cmake dir> <work dir> <out dir> <abi>...
#
# i2pd and OpenSSL are git submodules pinned to their release tags; Boost (headers and
# Program_options only) is a release archive checked by SHA-256. Builds OpenSSL (static) and i2pd
# per ABI, then puts out/jniLibs/<abi>/libi2pd.so (an executable, named so Android
# extracts it into the native library dir) and out/assets/i2pd/certificates.
set -euo pipefail

NDK=$1; CMAKE_DIR=$2; WORK=$(realpath -m "$3"); OUT=$(realpath -m "$4"); shift 4
ABIS=("$@")
HERE=$(cd "$(dirname "$0")" && pwd)
API=24 # getifaddrs is in bionic from Android 7

BOOST_VERSION=1.84.0
BOOST_URL=https://archives.boost.io/release/$BOOST_VERSION/source/boost_${BOOST_VERSION//./_}.tar.bz2
BOOST_SHA256=cc4b893acf645c9d4b698e9a0f08ca8846aa5d6c68275c14c3e7949c24109454

I2PD_SRC=$HERE/i2pd
OPENSSL_SRC=$HERE/openssl
for src in "$I2PD_SRC/libi2pd" "$OPENSSL_SRC/Configure"; do
    if [ ! -e "$src" ]; then
        echo "i2pd: sources missing, run: git submodule update --init --depth 1 i2pd/i2pd i2pd/openssl" >&2
        exit 1
    fi
done
I2PD_COMMIT=$(git -C "$I2PD_SRC" rev-parse HEAD)
OPENSSL_COMMIT=$(git -C "$OPENSSL_SRC" rev-parse HEAD)

STAMP="i2pd=$I2PD_COMMIT openssl=$OPENSSL_COMMIT boost=$BOOST_VERSION api=$API $(sha256sum "$HERE/CMakeLists.txt" "$0" | sha256sum | cut -c1-16)"

JOBS=$(nproc)
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
mkdir -p "$WORK/dl" "$OUT"

fetch() { # name url sha256
    local file=$WORK/dl/$1
    if [ ! -f "$file" ] || ! echo "$3  $file" | sha256sum -c --status; then
        echo "i2pd: downloading $2"
        curl -fsSL -o "$file.part" "$2"
        echo "$3  $file.part" | sha256sum -c --status || { echo "i2pd: checksum mismatch for $2" >&2; exit 1; }
        mv "$file.part" "$file"
    fi
}

unpack() { # dir archive [members...]
    local dir=$1 archive=$2; shift 2
    if [ ! -f "$WORK/$dir.unpacked" ]; then
        rm -rf "${WORK:?}/$dir"
        tar -xf "$archive" -C "$WORK" "$@"
        touch "$WORK/$dir.unpacked"
    fi
}

fetch boost-$BOOST_VERSION.tar.bz2 "$BOOST_URL" $BOOST_SHA256

BOOST_DIR=boost_${BOOST_VERSION//./_}
BOOST_SRC=$WORK/$BOOST_DIR
# Only the headers and Program_options: the whole of Boost is a gigabyte
unpack "$BOOST_DIR" "$WORK/dl/boost-$BOOST_VERSION.tar.bz2" "$BOOST_DIR/boost" "$BOOST_DIR/libs/program_options/src"

for ABI in "${ABIS[@]}"; do
    target="$OUT/jniLibs/$ABI/libi2pd.so"
    if [ -f "$target" ] && [ "$(cat "$WORK/i2pd-$ABI.stamp" 2>/dev/null)" = "$STAMP" ]; then
        echo "i2pd: $ABI up to date"
        continue
    fi

    case $ABI in
        arm64-v8a) SSL_TARGET=android-arm64 ;;
        armeabi-v7a) SSL_TARGET=android-arm ;;
        x86) SSL_TARGET=android-x86 ;;
        x86_64) SSL_TARGET=android-x86_64 ;;
        *) echo "i2pd: unknown ABI $ABI" >&2; exit 1 ;;
    esac

    SSL_OUT=$WORK/openssl-$ABI
    if [ ! -f "$SSL_OUT/lib/libcrypto.a" ] || [ "$(cat "$SSL_OUT.commit" 2>/dev/null)" != "$OPENSSL_COMMIT" ]; then
        echo "i2pd: building OpenSSL ${OPENSSL_COMMIT:0:8} for $ABI"
        rm -rf "$SSL_OUT"
        rm -rf "$WORK/openssl-build-$ABI"
        mkdir -p "$WORK/openssl-build-$ABI"
        (cd "$WORK/openssl-build-$ABI" &&
            PATH=$TOOLCHAIN/bin:$PATH ANDROID_NDK_ROOT=$NDK \
                "$OPENSSL_SRC/Configure" $SSL_TARGET -D__ANDROID_API__=$API \
                no-shared no-tests no-apps no-docs no-engine no-module \
                --prefix="$SSL_OUT" --libdir=lib > configure.log &&
            PATH=$TOOLCHAIN/bin:$PATH make -j"$JOBS" build_libs > make.log &&
            PATH=$TOOLCHAIN/bin:$PATH make install_dev > install.log)
        rm -rf "$WORK/openssl-build-$ABI"
        echo "$OPENSSL_COMMIT" > "$SSL_OUT.commit"
    fi

    echo "i2pd: building i2pd ${I2PD_COMMIT:0:8} for $ABI"
    BUILD=$WORK/i2pd-build-$ABI
    "$CMAKE_DIR/bin/cmake" -S "$HERE" -B "$BUILD" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$CMAKE_DIR/bin/ninja" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-$API -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DI2PD_SRC="$I2PD_SRC" -DBOOST_SRC="$BOOST_SRC" -DOPENSSL_DIR="$SSL_OUT" > /dev/null
    "$CMAKE_DIR/bin/cmake" --build "$BUILD" -j "$JOBS"

    mkdir -p "$OUT/jniLibs/$ABI"
    cp "$BUILD/i2pd" "$target"
    echo "$STAMP" > "$WORK/i2pd-$ABI.stamp"
done

rm -rf "$OUT/assets/i2pd/certificates"
mkdir -p "$OUT/assets/i2pd"
cp -r "$I2PD_SRC/contrib/certificates" "$OUT/assets/i2pd/certificates"
echo "i2pd: done"
