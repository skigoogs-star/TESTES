cmake_minimum_required(VERSION 3.22.1)

project(deckrecusb C)

# Only what Java cannot do: isochronous USB transfers. Everything else about the capture path —
# descriptor parsing, format conversion, buffering — is Kotlin, because that part can be unit
# tested on a JVM and this part cannot be tested anywhere except on a phone with a mixer attached.
add_library(deckrecusb SHARED usb_iso.c)

target_compile_options(deckrecusb PRIVATE -Wall -Wextra -Werror -O2)

# Android 15 devices may use 16 KB memory pages; a library aligned for 4 KB will not load on them.
# The NDK only defaults to this from r28, and the runner image also carries older NDKs.
target_link_options(deckrecusb PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(deckrecusb log)
