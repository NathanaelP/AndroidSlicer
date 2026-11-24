# SPDX-License-Identifier: AGPL-3.0-or-later
# Stand-in for the Conan-generated package config CuraEngine expects.
# MS_SRC (fetched sources) and MS_PREFIX (installed deps for this target) are set by build.sh.
# Load oneTBB's real config from the install prefix, then add the onetbb::onetbb
# name that CuraEngine links against.
include("${MS_PREFIX}/lib/cmake/TBB/TBBConfig.cmake")
if(NOT TARGET onetbb::onetbb)
  add_library(onetbb::onetbb INTERFACE IMPORTED)
  target_link_libraries(onetbb::onetbb INTERFACE TBB::tbb)
endif()
