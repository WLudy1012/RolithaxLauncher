import argparse
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
from pathlib import Path
import platform
from shutil import copyfile
import subprocess
from tempfile import TemporaryDirectory
from urllib.request import urlopen


REVISION = "129290d2ffe3f77ad574bfad8b1ab44f5f3b8fbe"
SOURCE_HASH = "23e210121ff5cf4678d9f4b62454f0f900eb1041cb4772aa0388703766a62f3a"
PACK = "src/share/native/com/sun/java/util/jar/pack"
SOURCES = [f"{PACK}/{name}" for name in (
    "bands.cpp", "bands.h", "bytes.cpp", "bytes.h", "coding.cpp", "coding.h",
    "constants.h", "defines.h", "main.cpp", "unpack.cpp", "unpack.h", "utils.cpp",
    "utils.h", "zip.cpp", "zip.h",
)] + [
    "src/share/native/common/jni_util.h",
    "src/share/javavm/export/jni.h",
    "src/solaris/javavm/export/jni_md.h",
    "src/share/native/common/jlong.h",
    "src/solaris/native/common/jlong_md.h",
]


def fetch_source(path: str) -> tuple[str, bytes]:
    url = f"https://raw.githubusercontent.com/openjdk/jdk8u/{REVISION}/jdk/{path}"
    with urlopen(url, timeout=30) as response:
        return Path(path).name, response.read()


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the Android x86_64 OpenJDK 8 unpack200 executable")
    parser.add_argument("--ndk", type=Path, required=True, help="Android NDK 28.2.13676358 directory")
    parser.add_argument("--output", type=Path, required=True, help="Output library pathname")
    args = parser.parse_args()
    system = platform.system()
    host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}[system]
    compiler = args.ndk.resolve() / "toolchains" / "llvm" / "prebuilt" / host / "bin" / (
        "clang++.exe" if system == "Windows" else "clang++"
    )
    if not compiler.is_file():
        parser.error(f"Android NDK compiler was not found at {compiler}")

    with ThreadPoolExecutor(max_workers=8) as pool:
        sources = sorted(pool.map(fetch_source, SOURCES))
    digest = sha256()
    for name, content in sources:
        digest.update(name.encode("ascii") + b"\0")
        digest.update(sha256(content).digest())
    if digest.hexdigest() != SOURCE_HASH:
        raise SystemExit("OpenJDK source hash mismatch")

    with TemporaryDirectory(prefix="rolithax-unpack200-") as temporary:
        directory = Path(temporary)
        for name, content in sources:
            (directory / name).write_bytes(content)
        cpp = [name for name, _ in sources if name.endswith(".cpp")]
        subprocess.run([
            str(compiler), "--target=x86_64-linux-android24", "-std=c++98",
            "-DPRODUCT", "-DFULL", "-DSYSTEM_ZLIB", "-D_LP64=1", "-O2", "-fPIE", "-pie",
            "-fno-ident", "-static-libstdc++",
            "-Wl,-z,max-page-size=16384,-z,common-page-size=16384,--build-id=sha1,--strip-all",
            *cpp, "-lz", "-o", "libunpack200.so",
        ], cwd=directory, check=True)
        output = args.output.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        copyfile(directory / "libunpack200.so", output)
        print(f"{sha256(output.read_bytes()).hexdigest()}  {output}")


if __name__ == "__main__":
    main()
