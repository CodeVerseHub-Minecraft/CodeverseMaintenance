# Third party notices

| Library | Version | License |
|---|---|---|
| Gson | 2.11.0 | Apache License 2.0 |

Gson is relocated into `net.codeverse.maintenance.libs.gson` so it cannot
collide with a different version shaded by another plugin on the proxy.

## Compile only, not redistributed

The Velocity API and
[CodeverseAPI](https://github.com/CodeVerseHub-Minecraft/CodeverseAPI) are
compile time dependencies and are not included in the jar. The shared contract
is provided at runtime by CodeverseAuth, which owns the API registration on the
proxy. Shipping a second copy would mean a second `CodeverseApiProvider`, and
therefore a second service registry, so the maintenance service would be
contributed where nothing could find it.

## Apache License 2.0 attribution

Gson is distributed under the Apache License 2.0. A copy of that license travels
with the jar in `META-INF`, which is why the shadow configuration does not strip
license files.

## About

This project is maintained by the CodeVerseHub-Minecraft Subteam, which works
alongside the wider CodeVerseHub community but is a separate team. CodeVerseHub
is not responsible for these projects.
