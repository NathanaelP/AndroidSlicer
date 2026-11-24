# SPDX-License-Identifier: AGPL-3.0-or-later
# Stand-in for the Conan-generated package config CuraEngine expects.
# MS_SRC (fetched sources) and MS_PREFIX (installed deps for this target) are set by build.sh.
# Clipper 6.4.2 is one .cpp file; build it here so the headers sit under polyclipping/.
if(NOT TARGET clipper::clipper)
  set(_inc "${CMAKE_BINARY_DIR}/_clipper_include")
  file(MAKE_DIRECTORY "${_inc}/polyclipping")
  configure_file("${MS_SRC}/clipper/cpp/clipper.hpp" "${_inc}/polyclipping/clipper.hpp" COPYONLY)
  add_library(clipper_polyclipping STATIC "${MS_SRC}/clipper/cpp/clipper.cpp")
  target_include_directories(clipper_polyclipping PUBLIC "${_inc}" "${_inc}/polyclipping")
  add_library(clipper::clipper ALIAS clipper_polyclipping)
endif()
