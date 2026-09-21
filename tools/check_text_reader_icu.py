#!/usr/bin/env python3
"""Check the production source-image script pattern with native ICU, not Python re.

Only Kotlin literal extraction uses Python's parser helpers. The regular expression
is compiled and searched by ICU's C API. Image routing/data decoding remain covered
by TextReaderImageResolverTest; this check does not reimplement that application
policy and does not claim ART or APK startup coverage.
"""
from __future__ import annotations

import argparse
import ctypes
import ctypes.util
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys


REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIRECTORY = Path("app/src/main/java/io/legado/app/model/localBook/epubcore/direct")
LEGACY_PATTERN = r"<js>[\s\S]*?</js>|@js:[\s\S]*|\{\{[\s\S]*?}}"
ICU_FLAGS = {"UNIX_LINES": 1, "IGNORE_CASE": 2, "COMMENTS": 4,
             "MULTILINE": 8, "LITERAL": 16, "DOT_MATCHES_ALL": 32}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_path(value):
    if value:
        path = Path(value)
        return path if path.is_absolute() else REPO_ROOT / path
    for name in ("TextReaderImageSource.kt", "TextReaderImageResource.kt"):
        path = REPO_ROOT / SOURCE_DIRECTORY / name
        if path.is_file() and re.search(r"\bobject\s+TextReaderImageSource\b", path.read_text(encoding="utf-8")):
            return path
    raise ValueError("Cannot find the production TextReaderImageSource object; specify --source")


def skip_space_and_comments(text, index):
    while index < len(text):
        if text[index].isspace():
            index += 1
        elif text.startswith("//", index):
            end = text.find("\n", index)
            index = len(text) if end < 0 else end + 1
        elif text.startswith("/*", index):
            end = text.find("*/", index + 2)
            if end < 0:
                raise ValueError("Unterminated Kotlin comment before scriptPattern")
            index = end + 2
        else:
            break
    return index


def read_kotlin_literal(text, index):
    """Decode a literal without evaluating Kotlin expressions or interpolations."""
    start = index
    if text.startswith('"""', index):
        end = text.find('"""', index + 3)
        if end < 0:
            raise ValueError("Unterminated raw Kotlin scriptPattern literal")
        value = text[index + 3:end]
        if re.search(r"\$(?:\{|[A-Za-z_])", value):
            raise ValueError("Interpolated scriptPattern requires an explicit extractor update")
        return value, end + 3, text[start:end + 3]
    if index >= len(text) or text[index] != '"':
        raise ValueError("scriptPattern must have a directly readable Kotlin string literal")
    index += 1
    decoded = []
    escapes = {"t": "\t", "b": "\b", "n": "\n", "r": "\r", "'": "'", '"': '"', "\\": "\\", "$": "$"}
    while index < len(text):
        char = text[index]
        index += 1
        if char == '"':
            return "".join(decoded), index, text[start:index]
        if char == "\\":
            if index >= len(text):
                break
            escape = text[index]
            index += 1
            if escape == "u":
                digits = text[index:index + 4]
                if not re.fullmatch(r"[0-9A-Fa-f]{4}", digits):
                    raise ValueError("Invalid Kotlin Unicode escape in scriptPattern")
                decoded.append(chr(int(digits, 16)))
                index += 4
            elif escape in escapes:
                decoded.append(escapes[escape])
            else:
                raise ValueError("Unsupported Kotlin escape in scriptPattern: " + escape)
        else:
            if char == "$" and index < len(text) and (text[index] == "{" or text[index].isalpha() or text[index] == "_"):
                raise ValueError("Interpolated scriptPattern requires an explicit extractor update")
            decoded.append(char)
    raise ValueError("Unterminated Kotlin scriptPattern literal")


def extract_pattern(path):
    source = path.read_text(encoding="utf-8")
    owner = re.search(r"\bobject\s+TextReaderImageSource\s*\{", source)
    if not owner:
        raise ValueError("Source does not contain object TextReaderImageSource")
    declaration = re.search(r"\bval\s+scriptPattern\s*=\s*Regex\s*\(", source[owner.end():])
    if not declaration:
        raise ValueError("Cannot locate TextReaderImageSource.scriptPattern = Regex(...)")
    index = skip_space_and_comments(source, owner.end() + declaration.end())
    line = source.count("\n", 0, index) + 1
    pattern, index, literal = read_kotlin_literal(source, index)
    index = skip_space_and_comments(source, index)
    if index >= len(source):
        raise ValueError("Unterminated Regex constructor")
    options = []
    if source[index] == ",":
        begin = index + 1
        index = begin
        depth = 0
        while index < len(source):
            if source[index] == ")" and depth == 0:
                break
            if source[index] == "(":
                depth += 1
            elif source[index] == ")":
                depth -= 1
            index += 1
        expression = source[begin:index]
        options = re.findall(r"\bRegexOption\.(\w+)", expression)
        remainder = re.sub(r"\bRegexOption\.\w+|\bsetOf\b|[\s(),]", "", expression)
        if not options or remainder:
            raise ValueError("Unsupported computed Regex options: " + expression.strip())
    if index >= len(source) or source[index] != ")":
        raise ValueError("scriptPattern is not a direct Regex literal constructor")
    unknown = set(options) - ICU_FLAGS.keys()
    if unknown:
        raise ValueError("Unsupported RegexOption to ICU mapping: " + ", ".join(sorted(unknown)))
    flags = 0
    for option in options:
        flags |= ICU_FLAGS[option]
    return {"value": pattern, "kotlinLiteral": literal, "line": line, "regexOptions": options, "icuFlags": flags}


class IcuUnavailable(RuntimeError):
    pass


class ParseError(ctypes.Structure):
    _fields_ = [("line", ctypes.c_int32), ("offset", ctypes.c_int32),
                ("preContext", ctypes.c_uint16 * 16), ("postContext", ctypes.c_uint16 * 16)]


def utf16_buffer(value):
    raw = value.encode("utf-16-le", errors="surrogatepass")
    units = (ctypes.c_uint16 * (len(raw) // 2 + 1))()
    for index in range(len(raw) // 2):
        units[index] = raw[index * 2] | (raw[index * 2 + 1] << 8)
    return units, len(raw) // 2, raw


class NativeIcu:
    def __init__(self, requested=None):
        explicit = requested or os.environ.get("ICU_LIBRARY")
        if explicit:
            candidates = [explicit]
        elif os.name == "nt":
            candidates = [str(Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "icu.dll")]
        else:
            candidates = [ctypes.util.find_library("icui18n"), ctypes.util.find_library("icucore"),
                          "libicui18n.so", "libicucore.A.dylib"]
        errors = []
        libraries = []
        for candidate in dict.fromkeys(value for value in candidates if value):
            try:
                libraries.append(ctypes.CDLL(candidate))
                break
            except OSError as error:
                errors.append(str(error))
        if not libraries:
            raise IcuUnavailable("No native ICU library could be loaded: " + " | ".join(errors))
        self.library = libraries[0]
        self.suffix = None
        for suffix in [""] + ["_" + str(version) for version in range(120, 39, -1)]:
            if hasattr(self.library, "uregex_open" + suffix):
                self.suffix = suffix
                break
        if self.suffix is None:
            raise IcuUnavailable("Loaded library does not export ICU uregex_open (including versioned symbols)")
        if not hasattr(self.library, "u_getVersion" + self.suffix):
            common_candidates = [ctypes.util.find_library("icuuc")]
            parent = Path(str(self.library._name)).parent
            if parent.is_dir():
                common_candidates += [str(path) for pattern in ("icuuc*.dll", "libicuuc.so*", "libicuuc*.dylib") for path in parent.glob(pattern)]
            for candidate in dict.fromkeys(value for value in common_candidates if value):
                try:
                    libraries.append(ctypes.CDLL(candidate))
                except OSError:
                    pass
        self._libraries = libraries  # Keep dependencies loaded for all function pointers.

        def bind(name, args, result):
            for library in libraries:
                function = getattr(library, name + self.suffix, None)
                if function is not None:
                    function.argtypes, function.restype = args, result
                    return function
            raise IcuUnavailable("Native ICU symbol is unavailable: " + name + self.suffix)

        pointer = ctypes.POINTER
        self._version = bind("u_getVersion", [pointer(ctypes.c_uint8)], None)
        self._error_name = bind("u_errorName", [ctypes.c_int32], ctypes.c_char_p)
        self._open = bind("uregex_open", [pointer(ctypes.c_uint16), ctypes.c_int32, ctypes.c_uint32,
                                           pointer(ParseError), pointer(ctypes.c_int32)], ctypes.c_void_p)
        self._close = bind("uregex_close", [ctypes.c_void_p], None)
        self._set_text = bind("uregex_setText", [ctypes.c_void_p, pointer(ctypes.c_uint16), ctypes.c_int32, pointer(ctypes.c_int32)], None)
        self._find = bind("uregex_find", [ctypes.c_void_p, ctypes.c_int32, pointer(ctypes.c_int32)], ctypes.c_int8)
        self._next = bind("uregex_findNext", [ctypes.c_void_p, pointer(ctypes.c_int32)], ctypes.c_int8)
        self._start = bind("uregex_start", [ctypes.c_void_p, ctypes.c_int32, pointer(ctypes.c_int32)], ctypes.c_int32)
        self._end = bind("uregex_end", [ctypes.c_void_p, ctypes.c_int32, pointer(ctypes.c_int32)], ctypes.c_int32)
        self._empty_text, _, _ = utf16_buffer("")

    def error_name(self, code):
        return self._error_name(code).decode("ascii", errors="replace")

    def metadata(self):
        version = (ctypes.c_uint8 * 4)()
        self._version(version)
        path = Path(str(self.library._name))
        return {"library": str(self.library._name), "version": list(version), "symbolSuffix": self.suffix,
                "librarySha256": sha256(path) if path.is_file() else None}

    def compile(self, pattern, flags):
        buffer, length, _ = utf16_buffer(pattern)
        error, parse = ctypes.c_int32(0), ParseError()
        handle = self._open(buffer, length, flags, ctypes.byref(parse), ctypes.byref(error))
        evidence = {"compiled": bool(handle) and error.value <= 0, "errorCode": error.value,
                    "errorName": self.error_name(error.value), "line": parse.line, "offsetUtf16": parse.offset}
        if handle and not evidence["compiled"]:
            self._close(handle)
            handle = None
        return handle, evidence

    def matches(self, handle, value):
        buffer, length, original = utf16_buffer(value)
        error = ctypes.c_int32(0)
        self._set_text(handle, buffer, length, ctypes.byref(error))
        found = self._find(handle, 0, ctypes.byref(error))
        matches = []
        while found and error.value <= 0:
            begin = self._start(handle, 0, ctypes.byref(error))
            end = self._end(handle, 0, ctypes.byref(error))
            matches.append({"text": original[begin * 2:end * 2].decode("utf-16-le", errors="surrogatepass"),
                            "startUtf16": begin, "endUtf16": end})
            found = self._next(handle, ctypes.byref(error))
        if error.value > 0:
            raise RuntimeError("ICU match failed: " + self.error_name(error.value))
        after = b"".join(int(buffer[index]).to_bytes(2, "little") for index in range(length))
        # Detach ICU from the temporary UChar buffer before it goes out of scope.
        self._set_text(handle, self._empty_text, 0, ctypes.byref(error))
        return matches, after == original

    def close(self, handle):
        if handle:
            self._close(handle)


def match_cases():
    return [
        ("template", "{{num}}", ["{{num}}"]),
        ("empty-template", "{{}}", ["{{}}"]),
        ("multiline-template", "before{{\nnum + 1\n}}after", ["{{\nnum + 1\n}}"]),
        ("multiple-templates", "{{one}}/{{two}}", ["{{one}}", "{{two}}"]),
        ("utf16-prefix", "\U0001f305{{num}}", ["{{num}}"]),
        ("js-tag", "<js>result</js>", ["<js>result</js>"]),
        ("mixed-case-js-tag", "<JS>\nresult\n</jS>", ["<JS>\nresult\n</jS>"]),
        ("at-js", "@js:result", ["@js:result"]),
        ("mixed-case-at-js", "@JS:\nresult", ["@JS:\nresult"]),
        ("template-keeps-url-fragment", "{{'https://example.test/a.svg#icon'}}", ["{{'https://example.test/a.svg#icon'}}"]),
        ("at-js-keeps-url-fragment", "@js:'https://example.test/a.svg#icon'", ["@js:'https://example.test/a.svg#icon'"]),
        ("ordinary-url-fragment", "https://example.test/a.svg#icon", []),
        ("content-url-fragment", "content://book/picture#page=3", []),
        ("percent-encoded-markers", "https://example.test/%7B%7Bn%7D%7D.svg#icon", []),
        ("url-query-and-fragment", "https://example.test/a.png?q=a%2Bb&x=1#part?x=2", []),
        ("png-data-uri", "data:image/png;base64,a+b/c==#literal-fragment", []),
        ("encoded-svg-data-uri", "data:image/svg+xml,%3Csvg%20fill=%22%23fff%22%3E%3C/svg%3E", []),
        ("encoded-svg-template", "data:image/svg+xml,%3Csvg%3E%7B%7Bliteral%7D%7D%3C/svg%3E", []),
        # The production data:image guard, tested in Kotlin, exempts these literal
        # payloads from source-script routing even though the pattern detects text.
        ("raw-svg-template-pattern-only", "data:image/svg+xml,<svg>{{literal}}</svg>", ["{{literal}}"]),
        ("raw-svg-js-tag-pattern-only", "data:image/svg+xml,<svg><js>literal</js></svg>", ["<js>literal</js>"]),
        ("ordinary-single-braces", "image-{number}.svg#icon", []),
        ("incomplete-template", "{{number}", []),
        ("incomplete-js-tag", "<js>result", []),
        ("non-marker-at", "image@js.png", []),
    ]


def check_linkage(path, source, report):
    linkage_path = Path(path).resolve()
    linkage = json.loads(linkage_path.read_text(encoding="utf-8"))
    pattern = linkage["scriptPattern"]
    actual = pattern["dexValue"]
    if not isinstance(actual, str):
        raise ValueError("Linkage report scriptPattern.dexValue must be a decoded string")
    equal = actual == source["value"] and pattern.get("sourceValue") == source["value"] and pattern.get("matchesSource") is True
    apk_path = Path(linkage["apk"]["path"])
    if not apk_path.is_absolute():
        apk_path = REPO_ROOT / apk_path
    apk_hash = sha256(apk_path)
    apk_matches = apk_hash == linkage["apk"]["sha256"]
    expected_option = source["regexOptions"][0] if len(source["regexOptions"]) == 1 else source["regexOptions"]
    option_matches = pattern.get("dexRegexOption") == pattern.get("sourceRegexOption") == expected_option
    report["linkage"] = {"report": str(linkage_path), "reportSha256": sha256(linkage_path),
                         "apk": str(apk_path), "apkSha256": apk_hash, "apkMatchesReport": apk_matches,
                         "patternMatchesSource": equal, "regexOptionMatchesSource": option_matches}
    if not equal:
        report["failures"].append("DEX scriptPattern differs from the current production source")
    if not apk_matches:
        report["failures"].append("APK changed since the linkage report was generated")
    if not option_matches:
        report["failures"].append("DEX RegexOption differs from the production source")
    return actual


def run(args):
    report = {"schemaVersion": 1, "status": "failed", "passed": False, "skipped": False,
              "completedAtUtc": None, "failures": [], "cases": [],
              "checkerSha256": sha256(Path(__file__).resolve()),
              "coverage": {"nativeIcuPatternCompilation": False, "nativeIcuPatternMatching": False,
                           "applicationRoutingPolicyExecuted": False, "androidArtExecuted": False,
                           "policyCoverage": "TextReaderImageResolverTest exercises source options and literal data URI routing"}}
    exit_code = 1
    try:
        path = source_path(args.source).resolve()
        source_hash = sha256(path)
        source = extract_pattern(path)
        report["source"] = {"path": str(path), "sha256": source_hash, "line": source["line"]}
        target = check_linkage(args.linkage_report, source, report) if args.linkage_report else source["value"]
        report["scriptPattern"] = {"sourceValue": source["value"], "testedValue": target,
                                   "kotlinLiteral": source["kotlinLiteral"], "regexOptions": source["regexOptions"],
                                   "icuFlags": source["icuFlags"], "fromDex": bool(args.linkage_report)}
        native = NativeIcu(args.icu_library)
        report["icu"] = native.metadata()
        negative, control = native.compile(LEGACY_PATTERN, source["icuFlags"])
        native.close(negative)
        control.update({"pattern": LEGACY_PATTERN, "expectedCompilation": False, "passed": not control["compiled"]})
        report["negativeControl"] = control
        if not control["passed"]:
            report["failures"].append("Native ICU accepted the known-bad unescaped legacy pattern; the negative control did not detect the regression")
        handle, compilation = native.compile(target, source["icuFlags"])
        report["compilation"] = compilation
        report["coverage"]["nativeIcuPatternCompilation"] = True
        try:
            if not compilation["compiled"]:
                report["failures"].append("Tested source/DEX pattern does not compile in native ICU: " + compilation["errorName"])
            else:
                report["coverage"]["nativeIcuPatternMatching"] = True
                for name, value, expected in match_cases():
                    matches, unchanged = native.matches(handle, value)
                    passed = [item["text"] for item in matches] == expected and unchanged
                    if name == "utf16-prefix":
                        passed = passed and matches[0]["startUtf16"] == 2
                    report["cases"].append({"name": name, "input": value, "expectedMatches": expected,
                                            "matches": matches, "inputPreserved": unchanged, "passed": passed,
                                            "scope": "native-pattern-only"})
                    if not passed:
                        report["failures"].append("Native ICU pattern case failed: " + name)
        finally:
            native.close(handle)
        if sha256(path) != source_hash:
            report["failures"].append("Production source changed during the ICU check")
        report["passed"] = not report["failures"]
        report["status"] = "passed" if report["passed"] else "failed"
        exit_code = 0 if report["passed"] else 1
    except IcuUnavailable as error:
        report["icuUnavailable"] = str(error)
        report["missingIcuPolicy"] = args.missing_icu
        report["skipped"] = args.missing_icu == "skip" and not report["failures"]
        report["status"] = "skipped" if report["skipped"] else "failed"
        if not report["skipped"]:
            report["failures"].append(str(error))
        exit_code = 0 if report["skipped"] else 2
    except Exception as error:
        report["failures"].append(type(error).__name__ + ": " + str(error))
    report["caseCount"] = len(report["cases"])
    report["completedAtUtc"] = datetime.now(timezone.utc).isoformat()
    return report, exit_code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", help="Kotlin production source or preserved pre-fix source snapshot")
    parser.add_argument("--icu-library", help="Native ICU library path/name; otherwise ICU_LIBRARY or platform discovery")
    parser.add_argument("--missing-icu", choices=("fail", "skip"), default="fail",
                        help="Missing/incompatible ICU policy (default: fail with exit 2; explicit skip exits 0)")
    parser.add_argument("--linkage-report", help="check_text_reader_apk_linkage.py JSON; verify/hash APK and test its decoded DEX pattern")
    parser.add_argument("--output", type=Path, help="Write UTF-8 JSON evidence here; JSON is also printed to stdout")
    args = parser.parse_args()
    report, exit_code = run(args)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
