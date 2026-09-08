#!/usr/bin/env python3
"""Verify the Linux player bridge implements exactly the JNI surface Kotlin declares.

Kotlin's `external fun` declarations in NativePlayerBridge.kt are the contract.
If the C++ bridge is missing one, the app compiles fine and dies with
UnsatisfiedLinkError the first time that call is reached -- typically the
moment the user presses play. Nothing in the Gradle build catches that, so
this check exists to catch it at merge time instead.

Run after every rebase onto a new fork release.

  check-jni-contract.py [--kotlin PATH] [--cpp PATH] [--json]

Exit status: 0 = contract satisfied, 1 = mismatch, 2 = could not parse.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# Kotlin type -> JNI type. Nullability is irrelevant across JNI: a nullable
# String is still a jstring, it just may arrive as NULL.
KOTLIN_TO_JNI = {
    "Long": "jlong", "Int": "jint", "Short": "jshort", "Byte": "jbyte",
    "Boolean": "jboolean", "Double": "jdouble", "Float": "jfloat",
    "Char": "jchar", "String": "jstring", "Unit": "void",
    "Array<String>": "jobjectArray", "LongArray": "jlongArray",
    "IntArray": "jintArray", "ByteArray": "jbyteArray",
    "FloatArray": "jfloatArray", "DoubleArray": "jdoubleArray",
}

C_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")


def balanced(text: str, open_idx: int) -> tuple[str, int]:
    """Return the contents of the parens starting at open_idx, and the index past ')'."""
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i], i + 1
    raise ValueError(f"unbalanced parentheses at offset {open_idx}")


def split_top_level(params: str) -> list[str]:
    """Split on commas that are not nested inside <> or ()."""
    out, depth, cur = [], 0, ""
    for ch in params:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return [p.strip() for p in out if p.strip()]


def kotlin_jni_type(ktype: str) -> str:
    ktype = ktype.strip().rstrip("?").strip()
    if ktype in KOTLIN_TO_JNI:
        return KOTLIN_TO_JNI[ktype]
    if ktype.startswith("Array<"):
        return "jobjectArray"
    return "jobject"  # interfaces, classes, event sinks


def parse_kotlin(path: Path) -> dict[str, list[str]]:
    src = LINE_COMMENT.sub("", path.read_text(encoding="utf8", errors="replace"))
    methods: dict[str, list[str]] = {}
    for m in re.finditer(r"\bexternal\s+fun\s+(\w+)\s*\(", src):
        name = m.group(1)
        params, _ = balanced(src, m.end() - 1)
        types = []
        for p in split_top_level(params):
            if ":" not in p:
                continue
            types.append(kotlin_jni_type(p.split(":", 1)[1]))
        methods[name] = types
    return methods


def parse_cpp(path: Path) -> dict[str, list[str]]:
    src = path.read_text(encoding="utf8", errors="replace")
    src = C_COMMENT.sub(" ", src)
    src = LINE_COMMENT.sub("", src)
    methods: dict[str, list[str]] = {}
    # Both the NP(name) macro form and the fully spelled-out JNI export.
    pattern = re.compile(
        r"(?:\bNP\(\s*(\w+)\s*\)|Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_(\w+))\s*\("
    )
    for m in pattern.finditer(src):
        name = m.group(1) or m.group(2)
        # Skip the #define of the macro itself.
        if name in ("name",):
            continue
        try:
            params, _ = balanced(src, m.end() - 1)
        except ValueError:
            continue
        types = []
        for p in split_top_level(params):
            toks = p.replace("*", " * ").split()
            if not toks:
                continue
            # Type is everything before an optional trailing identifier.
            t = toks[0]
            if t == "const" and len(toks) > 1:
                t = toks[1]
            types.append(t)
        # Drop the JNIEnv* and jobject/jclass receiver.
        types = types[2:] if len(types) >= 2 else types
        methods[name] = types
    return methods


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    root = Path(__file__).resolve()
    for parent in root.parents:
        if (parent / "composeApp").is_dir():
            root = parent
            break
    else:
        root = Path.cwd()
    ap.add_argument("--kotlin", type=Path, default=root /
                    "composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerBridge.kt")
    ap.add_argument("--cpp", type=Path, default=root /
                    "composeApp/src/desktopMain/native/linux/player_bridge.cpp")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args()

    for p in (args.kotlin, args.cpp):
        if not p.is_file():
            print(f"check-jni-contract: not found: {p}", file=sys.stderr)
            return 2

    kt = parse_kotlin(args.kotlin)
    cpp = parse_cpp(args.cpp)
    if not kt:
        print("check-jni-contract: no `external fun` found -- did the file move?", file=sys.stderr)
        return 2

    missing = sorted(set(kt) - set(cpp))
    extra = sorted(set(cpp) - set(kt))
    mismatched = sorted(
        n for n in set(kt) & set(cpp) if kt[n] != cpp[n]
    )

    if args.json:
        print(json.dumps({
            "declared": len(kt), "implemented": len(cpp),
            "missing": missing, "extra": extra,
            "signature_mismatch": {n: {"kotlin": kt[n], "cpp": cpp[n]} for n in mismatched},
        }, indent=2))
    else:
        print(f"Kotlin declares  : {len(kt)} native methods  ({args.kotlin.name})")
        print(f"C++ implements   : {len(cpp)} native methods  ({args.cpp.name})")
        if missing:
            print(f"\nMISSING -- declared in Kotlin, absent from C++ ({len(missing)}):")
            print("  These throw UnsatisfiedLinkError at runtime. Implement or stub each one.")
            for n in missing:
                print(f"    {n}({', '.join(kt[n])})")
        if mismatched:
            print(f"\nSIGNATURE MISMATCH ({len(mismatched)}):")
            for n in mismatched:
                print(f"    {n}")
                print(f"        kotlin: ({', '.join(kt[n])})")
                print(f"        cpp   : ({', '.join(cpp[n])})")
        if extra:
            print(f"\nEXTRA -- implemented in C++, not declared in Kotlin ({len(extra)}):")
            print("  Harmless but dead; usually a method the fork renamed or dropped.")
            for n in extra:
                print(f"    {n}")
        if not (missing or mismatched):
            print("\nOK: every declared native method is implemented with a matching signature.")

    return 1 if (missing or mismatched) else 0


if __name__ == "__main__":
    sys.exit(main())
