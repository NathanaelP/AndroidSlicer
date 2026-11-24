# SPDX-License-Identifier: AGPL-3.0-or-later
# Stand-in for the Conan-generated package config CuraEngine expects.
# MS_SRC (fetched sources) and MS_PREFIX (installed deps for this target) are set by build.sh.
if(NOT TARGET boost::boost)
  add_library(boost::boost INTERFACE IMPORTED)
  target_include_directories(boost::boost INTERFACE "${MS_SRC}/boost")
endif()
