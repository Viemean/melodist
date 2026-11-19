cmake_minimum_required(VERSION 3.22.1)
project("melodist_usb_audio" C)

set(CMAKE_C_STANDARD 11)

add_library(melodist_usb_audio SHARED
    usb-audio-output.c
)

if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(melodist_usb_audio PRIVATE
        -O3
        -flto=thin
        -fomit-frame-pointer
        -fvisibility=hidden
        -fno-math-errno
        -fno-trapping-math
        -ffp-contract=fast
    )
    target_link_options(melodist_usb_audio PRIVATE
        -flto=thin
        -Wl,-O3
        -Wl,--gc-sections
        -Wl,--as-needed
    )
endif()

find_library(log-lib log)
target_link_libraries(melodist_usb_audio
    ${log-lib}
)
