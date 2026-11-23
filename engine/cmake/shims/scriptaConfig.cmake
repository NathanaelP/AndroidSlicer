# SPDX-License-Identifier: AGPL-3.0-or-later
# Stand-in for the Conan-generated package config CuraEngine expects.
# MS_SRC (fetched sources) and MS_PREFIX (installed deps for this target) are set by build.sh.
if(NOT TARGET scripta::scripta)
  add_library(scripta::scripta INTERFACE IMPORTED)
  target_include_directories(scripta::scripta INTERFACE "${MS_SRC}/scripta/include")
endif()
