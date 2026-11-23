# SPDX-License-Identifier: AGPL-3.0-or-later
# Stand-in for the Conan-generated package config CuraEngine expects.
# MS_SRC (fetched sources) and MS_PREFIX (installed deps for this target) are set by build.sh.
if(NOT TARGET stb::stb)
  add_library(stb::stb INTERFACE IMPORTED)
  target_include_directories(stb::stb INTERFACE "${MS_SRC}/stb")
endif()
