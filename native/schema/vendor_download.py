from pathlib import Path
import hashlib, io, tarfile, urllib.request
from concurrent.futures import ThreadPoolExecutor

root = Path(__file__).parent / "vendor"
files = {
    "json.hpp": "https://raw.githubusercontent.com/nlohmann/json/v3.12.0/single_include/nlohmann/json.hpp",
    "JSON-LICENSE.MIT": "https://raw.githubusercontent.com/nlohmann/json/v3.12.0/LICENSE.MIT",
    "tinyxml2.h": "https://raw.githubusercontent.com/leethomason/tinyxml2/11.0.0/tinyxml2.h",
    "tinyxml2.cpp": "https://raw.githubusercontent.com/leethomason/tinyxml2/11.0.0/tinyxml2.cpp",
    "TINYXML2-LICENSE.txt": "https://raw.githubusercontent.com/leethomason/tinyxml2/11.0.0/LICENSE.txt",
}
expected = {
    "json.hpp": "aaf127c04cb31c406e5b04a63f1ae89369fccde6d8fa7cdda1ed4f32dfc5de63",
    "JSON-LICENSE.MIT": "46a65cffd1ea955132d95a8dd921640714a8d6b537d2e4e482d31145ae95b603",
    "tinyxml2.h": "0f3c6e5a91b6d65caca3cc7239926455f21e488cff80ac3d27c74f5262fd2602",
    "tinyxml2.cpp": "95f64e4cb06c9e147d5b12e8860c88898944858dfbc38fed92ebf918f1436554",
    "TINYXML2-LICENSE.txt": "9332252e9b9e46db8285d4a3f0bf25f139bf1dca6781b956d57f2302efca6432",
}

def fetch(pair):
    name, url = pair
    body = urllib.request.urlopen(url, timeout=60).read()
    digest = hashlib.sha256(body).hexdigest()
    if digest != expected[name]:
        raise ValueError(f"Checksum mismatch: {name}")
    root.mkdir(parents=True, exist_ok=True)
    (root / name).write_bytes(body)
    return name, digest

if __name__ == "__main__":
    hashes = dict(ThreadPoolExecutor(5).map(fetch, files.items()))
    lua = urllib.request.urlopen("https://www.lua.org/ftp/lua-5.5.1.tar.gz", timeout=60).read()
    if hashlib.sha256(lua).hexdigest() != "1c4b4068d67061f2a2231ad2b5422e77acea1487ea9890f6320af614f4373dce":
        raise ValueError("Lua release checksum mismatch")
    with tarfile.open(fileobj=io.BytesIO(lua), mode="r:gz") as archive:
        for member in archive.getmembers():
            if member.isfile() and member.name.startswith("lua-5.5.1/src/") and Path(member.name).suffix in {".c", ".h"}:
                target = root / "lua" / Path(member.name).name
                target.parent.mkdir(parents=True, exist_ok=True)
                body = archive.extractfile(member).read()
                target.write_bytes(body)
                hashes["lua/" + target.name] = hashlib.sha256(body).hexdigest()
        (root / "LUA-LICENSE.html").write_bytes(archive.extractfile("lua-5.5.1/doc/readme.html").read())
    (root / "SHA256SUMS").write_text("".join(f"{digest}  {name}\n" for name, digest in sorted(hashes.items())))
    print(f"Vendored {len(hashes)} files; Lua archive checksum verified")
