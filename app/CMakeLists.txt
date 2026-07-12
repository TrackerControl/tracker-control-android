
cmake_minimum_required(VERSION 3.10.2)

project(netguard)

# Use relative paths for __FILE__ macro to ensure reproducible builds
# This remaps absolute paths to relative paths in the compiled binaries
add_compile_options("-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")

add_library( netguard
             SHARED
             src/main/jni/netguard/netguard.c
             src/main/jni/netguard/session.c
             src/main/jni/netguard/ip.c
             src/main/jni/netguard/tls.c
             src/main/jni/netguard/tcp.c
             src/main/jni/netguard/udp.c
             src/main/jni/netguard/icmp.c
             src/main/jni/netguard/dns.c
             src/main/jni/netguard/dhcp.c
             src/main/jni/netguard/pcap.c
             src/main/jni/netguard/util.c )

include_directories( src/main/jni/netguard/ )

find_library( log-lib
              log )

target_link_libraries( netguard
                       ${log-lib} )

target_link_options(netguard PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
    "-Wl,--build-id=none")

# The clang version string embedded in .comment (which varies with the NDK's
# use of BOLT-optimized toolchains, "+bolt"/"-bolt") and any residual
# .note.gnu.build-id both harm reproducibility. Strip them post-link.
# See https://izzyondroid.org/docs/reproducibleBuilds/DebugFailedRBs/#clang
add_custom_command(TARGET netguard
    POST_BUILD
    COMMAND ${CMAKE_OBJCOPY} --remove-section .comment
            --remove-section .note.gnu.build-id
            $<TARGET_FILE:netguard>
    VERBATIM)
