# Third-party source

Rolithax Schema builds its parser dependencies from the vendored source. It does not download dependencies during a normal build.

| Component | Pinned version | License | Upstream |
| --- | --- | --- | --- |
| nlohmann JSON | 3.12.0 | MIT, `vendor/JSON-LICENSE.MIT` | https://github.com/nlohmann/json/tree/v3.12.0 |
| tinyxml2 | 11.0.0 | zlib, `vendor/TINYXML2-LICENSE.txt` | https://github.com/leethomason/tinyxml2/tree/11.0.0 |
| Lua | 5.5.1 | MIT, copyright in `vendor/lua/lua.h` and `vendor/LUA-LICENSE.html` | https://www.lua.org/ftp/lua-5.5.1.tar.gz |

The Lua release archive SHA-256 is `1c4b4068d67061f2a2231ad2b5422e77acea1487ea9890f6320af614f4373dce`. Individual vendored source checksums are in `vendor/SHA256SUMS`. `vendor_download.py` reproduces the downloads from these pinned upstream releases; run it only when deliberately refreshing vendor files.

The runtime opens only base, math, table, string and utf8 libraries. Shipping Lua source files for other libraries does not grant scripts access to those libraries.
