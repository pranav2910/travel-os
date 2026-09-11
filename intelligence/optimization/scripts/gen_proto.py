"""Generate Python protobuf + gRPC stubs from contracts/protobuf into src/travelos/.

The generated tree is not committed (see .gitignore); build and CI regenerate it, so the Python
service can never drift from the shared contracts.
"""

from __future__ import annotations

import pathlib
import sys

from grpc_tools import protoc

HERE = pathlib.Path(__file__).resolve().parent
PROJECT = HERE.parent
REPO = PROJECT.parent.parent
PROTO_ROOT = REPO / "contracts" / "protobuf" / "src" / "main" / "proto"
OUT = PROJECT / "src"


def main() -> int:
    protos = sorted(str(p) for p in PROTO_ROOT.rglob("*.proto"))
    if not protos:
        print(f"no .proto files under {PROTO_ROOT}", file=sys.stderr)
        return 1
    import grpc_tools  # noqa: PLC0415  (well-known types ship with grpc_tools)

    include = pathlib.Path(grpc_tools.__file__).parent / "_proto"
    args = [
        "protoc",
        f"-I{PROTO_ROOT}",
        f"-I{include}",
        f"--python_out={OUT}",
        f"--pyi_out={OUT}",
        f"--grpc_python_out={OUT}",
        *protos,
    ]
    rc = protoc.main(args)
    if rc != 0:
        return rc
    # Make every generated directory a package.
    for directory in {p.parent for p in (OUT / "travelos").rglob("*_pb2.py")}:
        for d in [directory, *directory.parents]:
            if d == OUT:
                break
            init = d / "__init__.py"
            if not init.exists():
                init.write_text("")
    print(f"generated {len(protos)} protos into {OUT / 'travelos'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
