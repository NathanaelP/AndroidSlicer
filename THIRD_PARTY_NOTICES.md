# Third-party components

MobileSlicer is licensed under AGPL-3.0-or-later (see [LICENSE](LICENSE)). It bundles or builds on the components below, each under its own license. All of them are compatible with AGPL-3.0.

Versions are pinned in [`engine/versions.env`](engine/versions.env). Sources are fetched by [`engine/fetch-sources.sh`](engine/fetch-sources.sh); each one's full license text is in its source tree.

## Shipped in the app

| Component | Version | License (SPDX) | Source |
| --- | --- | --- | --- |
| CuraEngine | 5.13.0 | AGPL-3.0-or-later | https://github.com/Ultimaker/CuraEngine |
| Scripta | `b96045c` | AGPL-3.0 | https://github.com/Ultimaker/Scripta_public |
| Cura setting definitions (`fdmprinter.def.json`, `fdmextruder.def.json`) | 5.13.0 | LGPL-3.0-or-later | https://github.com/Ultimaker/Cura |
| oneTBB | 2022.2.0 | Apache-2.0 | https://github.com/uxlfoundation/oneTBB |
| LLVM libc++ (`libc++_shared.so`, from the Android NDK r29) | r29 | Apache-2.0 WITH LLVM-exception | https://developer.android.com/ndk |
| {fmt} | 11.1.3 | MIT | https://github.com/fmtlib/fmt |
| spdlog | 1.15.1 | MIT | https://github.com/gabime/spdlog |
| range-v3 | 0.12.0 | BSL-1.0 | https://github.com/ericniebler/range-v3 |
| Boost (headers only) | 1.88.0 | BSL-1.0 | https://www.boost.org |
| RapidJSON | `f9d5341` | MIT | https://github.com/Tencent/rapidjson |
| stb | `5736b15` | MIT OR Unlicense | https://github.com/nothings/stb |
| Clipper (polyclipping) | 6.4.2 | BSL-1.0 | https://sourceforge.net/projects/polyclipping/ |
| Wagyu | 0.5.0 | BSL-1.0 (one file BSD-3-Clause, Google) | https://github.com/mapbox/wagyu |
| mapbox geometry.hpp | 2.0.3 | ISC | https://github.com/mapbox/geometry.hpp |
| mapbox variant | 1.2.0 | BSD-3-Clause | https://github.com/mapbox/variant |
| zlib | 1.3.1 | Zlib | https://github.com/madler/zlib |
| libpng | 1.6.48 | libpng-2.0 | https://github.com/pnggroup/libpng |

## Used only at build time

| Component | License (SPDX) | Source |
| --- | --- | --- |
| `StandardProjectSettings.cmake` from Ultimaker's Conan recipe index (fetched, not copied into this repo) | None stated: the repository has no license file | https://github.com/Ultimaker/conan-ultimaker-index |
| Android NDK, SDK and Gradle plugin | Android SDK License / Apache-2.0 | https://developer.android.com |

Before a public release (milestone M14), the app must also show these licenses and their full texts on a license screen.
