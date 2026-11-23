# MobileSlicer

A native Android slicer built on CuraEngine. It takes a Nomad Sculpt export (STL/OBJ) to G-code on the device and sends it to a Klipper printer through Moonraker, with no PC and no flash drive. First target: Elegoo Neptune 4 Pro.

**Status:** planning. No code yet.

## Documentation

- [Architecture and milestones](docs/architecture-and-milestones.md): goals, scope, key technical decisions, milestones M0–M14, UI direction, and risks.

## License

MobileSlicer is free and open-source software, made for makers. It is licensed under the **GNU Affero General Public License, version 3 or (at your option) any later version** (SPDX: `AGPL-3.0-or-later`). The full text is in [LICENSE](LICENSE).

Copyright (C) 2026 Nathanael Potoski and MobileSlicer contributors.

In plain terms:

- **You can** use, study, modify, and share MobileSlicer for any purpose, including commercially.
- **If you distribute it**, modified or not (for example, an APK), you must make the complete source code available under the same license.
- **If you run a modified version as a network service**, you must offer its source to the people who use that service.
- **No one can make it closed.** Every copy and every derivative stays under AGPL-3.0-or-later.

This summary is for convenience only; the [LICENSE](LICENSE) text is what applies.

### Third-party components

MobileSlicer bundles CuraEngine, which is itself licensed under AGPL-3.0. Other bundled libraries and data keep their own licenses, which must be compatible with AGPL-3.0. They will be listed, with their licenses, in a `THIRD_PARTY_NOTICES` file as they are added.

### Contributing

By submitting a contribution, you agree that it is licensed under AGPL-3.0-or-later, the same license as the rest of the project. New source files should start with:

```
// SPDX-License-Identifier: AGPL-3.0-or-later
```
