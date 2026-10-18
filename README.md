# Matter Manipulator

An unofficial NeoForge 1.21.1 port of [Matter Manipulator](https://github.com/RecursivePineapple/MatterManipulator) by RecursivePineapple, originally made for GTNH on 1.7.10. This isn't the original mod, so please report issues here, and not to the original author.

The Matter Manipulator is basically WorldEdit for survival. It's a handheld tool that can build large or repetitive structures for you without any hassle.

## Modes

- **Geometry**: cuboids, spheres, cylinders and lines.
- **Exchange**: replace a block/pipe with another across a selected region.
- **Cable**: lay cables and pipes between two or more points, either manually or using a pathing algorithm.
- **Copy**: rebuild a region somewhere else, machine settings included. You can rotate, mirror and stack it.
- **Move**: pick up a region and set it down elsewhere, contents and all.
- **Blueprint**: save a region to a file or a text string, share it with other players ingame or export it to share on other platforms (blueprint sharing website coming soon)

There are four tiers that each give more and more features, ranging from the Prototype up to the MKIII, which has unlimited range.

## Other features
- In-world preview of what will be placed before you commit to it
- Large jobs are spread across ticks, so it creates a minimal impact on performance
- Blocks can come from either your inventory or a linked AE2 network.

## Mod support

Machines, cables, their settings, and filters from the mods below are handled properly when copying, moving and building, with more mods planned. Want to request support for another mod? [Open an issue](https://github.com/ko-lja/Matter-Manipulator-Reborn/issues)

| Mod | 1.20.1 | 1.21.1 | 26.1 |
|---|:---:|:---:|:---:|
| [Applied Energistics 2](https://www.curseforge.com/minecraft/mc-mods/applied-energistics-2) | ✅ | ✅ | ✅ |
| [Domum Ornamentum](https://www.curseforge.com/minecraft/mc-mods/domum-ornamentum) | ✅ | ✅ | ❌ |
| [Flux Networks](https://www.curseforge.com/minecraft/mc-mods/flux-networks) | ✅ | ✅ | ❌ |
| [FramedBlocks](https://www.curseforge.com/minecraft/mc-mods/framedblocks) | ✅ | ✅ | ✅ |
| [GTCEu Modern](https://www.curseforge.com/minecraft/mc-mods/gregtechceu-modern) | ✅ | ✅ | ❌ |
| [LaserIO](https://www.curseforge.com/minecraft/mc-mods/laserio) | ✅ | ✅ | ✅ |
| [LittleTiles](https://www.curseforge.com/minecraft/mc-mods/littletiles) | ✅ | ✅ | ❌ |
| [Project Red](https://www.curseforge.com/minecraft/mc-mods/project-red-transmission) | ✅ | ✅ | ❌ |
| [Rechiseled](https://www.curseforge.com/minecraft/mc-mods/rechiseled) | ✅ | ✅ | ✅ |

## Adding an integration

See [docs/integrations.md](docs/integrations.md).

## License and Credits

* LGPL-3.0, see [LICENSE](LICENSE).
* Original mod: RecursivePineapple, [GitHub](https://github.com/RecursivePineapple/MatterManipulator) (LGPL-3.0)
