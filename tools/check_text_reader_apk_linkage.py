#!/usr/bin/env python3
"""Audit actual DEX definitions and bind TextReaderImageSource's compiled regex.

This checks APK bytecode, not a search for a string anywhere in an APK. It follows
the straight-line <clinit> instructions through Regex construction and assignment
to scriptPattern. Pair the JSON report with check_text_reader_icu.py to validate
the extracted pattern against native ICU. This is not an Android runtime test.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
import zlib


ROOT = Path(__file__).resolve().parents[1]
PREFIX = "Lio/legado/app/model/localBook/epubcore/direct/TextReader"
SOURCE_CLASS = PREFIX + "ImageSource;"
RESOURCE_CLASS = PREFIX + "ImageResource;"
REGEX_CLASS = "Lkotlin/text/Regex;"
SOURCE_DEFAULT = ROOT / "app/src/main/java/io/legado/app/model/localBook/epubcore/direct/TextReaderImageResource.kt"
PLATFORM_PREFIXES = ("Ljava/", "Ljavax/", "Landroid/", "Ldalvik/", "Llibcore/", "Lsun/", "Lorg/xml/", "Lorg/w3c/")
OBJECT_METHODS = {
    ("getClass", "()Ljava/lang/Class;"), ("hashCode", "()I"),
    ("equals", "(Ljava/lang/Object;)Z"), ("clone", "()Ljava/lang/Object;"),
    ("toString", "()Ljava/lang/String;"), ("notify", "()V"), ("notifyAll", "()V"),
    ("wait", "()V"), ("wait", "(J)V"), ("wait", "(JI)V"), ("finalize", "()V"),
}


def uleb(data, pos):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[pos]
        pos += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, pos
    raise ValueError("Invalid DEX ULEB128")


def sleb(data, pos):
    value = 0
    shift = 0
    while shift < 35:
        byte = data[pos]
        pos += 1
        value |= (byte & 127) << shift
        shift += 7
        if byte < 128:
            if byte & 64:
                value -= 1 << shift
            return value, pos
    raise ValueError("Invalid DEX SLEB128")


def object_type(descriptor):
    descriptor = descriptor.lstrip("[")
    return descriptor if descriptor.startswith("L") else None


class Dex:
    def __init__(self, name, data):
        if data[:8] != b"dex\n035\x00":
            raise ValueError(f"{name}: only DEX 035 is supported by this verifier")
        self.name, self.data = name, data
        self.integrity = {
            "name": name,
            "sha1Matches": data[12:32] == hashlib.sha1(data[32:]).digest(),
            "adler32Matches": struct.unpack_from("<I", data, 8)[0] == zlib.adler32(data[12:]) & 0xFFFFFFFF,
        }
        count, offset = struct.unpack_from("<II", data, 56)
        self.strings = []
        for index in range(count):
            pos = struct.unpack_from("<I", data, offset + 4 * index)[0]
            _, pos = uleb(data, pos)
            end = data.index(0, pos)
            # Relevant descriptors and the script detector are ASCII. Keep MUTF-8
            # surrogate code units intact for all other DEX strings as well.
            self.strings.append(data[pos:end].replace(b"\xc0\x80", b"\x00").decode("utf-8", "surrogatepass"))
        count, offset = struct.unpack_from("<II", data, 64)
        self.types = [self.strings[struct.unpack_from("<I", data, offset + 4 * i)[0]] for i in range(count)]
        count, offset = struct.unpack_from("<II", data, 72)
        self.protos = []
        for index in range(count):
            _, result, parameters = struct.unpack_from("<III", data, offset + 12 * index)
            arguments = self.type_list(parameters)
            self.protos.append({"return": self.types[result], "arguments": arguments,
                                "descriptor": "(" + "".join(arguments) + ")" + self.types[result]})
        count, offset = struct.unpack_from("<II", data, 80)
        self.fields = []
        for index in range(count):
            owner, kind, name_index = struct.unpack_from("<HHI", data, offset + 8 * index)
            self.fields.append({"owner": self.types[owner], "name": self.strings[name_index], "descriptor": self.types[kind]})
        count, offset = struct.unpack_from("<II", data, 88)
        self.methods = []
        for index in range(count):
            owner, proto, name_index = struct.unpack_from("<HHI", data, offset + 8 * index)
            self.methods.append({"owner": self.types[owner], "name": self.strings[name_index], **self.protos[proto]})
        count, offset = struct.unpack_from("<II", data, 96)
        self.classes = {}
        for index in range(count):
            owner, flags, superclass, interfaces, _, _, class_data, _ = struct.unpack_from("<8I", data, offset + 32 * index)
            descriptor = self.types[owner]
            if descriptor in self.classes:
                raise ValueError(f"{self.name}: duplicate class_def for {descriptor}")
            defined_fields, defined_methods = [], []
            if class_data:
                static_fields, pos = uleb(data, class_data)
                instance_fields, pos = uleb(data, pos)
                direct_methods, pos = uleb(data, pos)
                virtual_methods, pos = uleb(data, pos)
                for group, field_count in (("static", static_fields), ("instance", instance_fields)):
                    member_index = 0
                    for _ in range(field_count):
                        delta, pos = uleb(data, pos)
                        access, pos = uleb(data, pos)
                        member_index += delta
                        field = self.fields[member_index]
                        if field["owner"] != descriptor or bool(access & 8) != (group == "static"):
                            raise ValueError(f"Invalid class_data field owner or static group in {descriptor}")
                        defined_fields.append({**field, "accessFlags": access, "declarationKind": group})
                for group, method_count in (("direct", direct_methods), ("virtual", virtual_methods)):
                    member_index = 0
                    for _ in range(method_count):
                        delta, pos = uleb(data, pos)
                        access, pos = uleb(data, pos)
                        code, pos = uleb(data, pos)
                        member_index += delta
                        method = self.methods[member_index]
                        if method["owner"] != descriptor or (group == "virtual" and access & (8 | 2 | 0x10000)):
                            raise ValueError(f"Invalid class_data method owner or virtual group in {descriptor}")
                        defined_methods.append({**method, "accessFlags": access, "declarationKind": group,
                                                "codeOffset": code, "methodIndex": member_index})
            for members in (defined_fields, defined_methods):
                if len({(m["name"], m["descriptor"]) for m in members}) != len(members):
                    raise ValueError(f"Duplicate member declaration in {descriptor}")
            self.classes[descriptor] = {
                "descriptor": descriptor, "dex": name, "accessFlags": flags,
                "superclass": self.types[superclass] if superclass != 0xFFFFFFFF else None,
                "interfaces": self.type_list(interfaces), "fields": defined_fields, "methods": defined_methods,
            }
        self.integrity["classDefinitions"] = len(self.classes)

    def type_list(self, offset):
        if not offset:
            return []
        count = struct.unpack_from("<I", self.data, offset)[0]
        return [self.types[struct.unpack_from("<H", self.data, offset + 4 + 2 * i)[0]] for i in range(count)]

    def instructions(self, method):
        offset = method["codeOffset"]
        if not offset:
            return
        size = struct.unpack_from("<I", self.data, offset + 12)[0]
        units = struct.unpack_from(f"<{size}H", self.data, offset + 16)
        pc = 0
        while pc < size:
            word = units[pc]
            op = word & 255
            if op == 0 and word:
                if word == 0x100:
                    width = 4 + 2 * units[pc + 1]
                elif word == 0x200:
                    width = 2 + 4 * units[pc + 1]
                elif word == 0x300:
                    elements = units[pc + 2] | units[pc + 3] << 16
                    width = 4 + (units[pc + 1] * elements + 1) // 2
                else:
                    raise ValueError(f"Unknown DEX payload {word:x}")
            elif op in (0x03, 0x06, 0x09, 0x14, 0x17, 0x1B, 0x24, 0x25, 0x26, 0x2A, 0x2B, 0x2C) or 0x6E <= op <= 0x72 or 0x74 <= op <= 0x78:
                width = 3
            elif op == 0x18:
                width = 5
            elif op in (0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1F, 0x20, 0x22, 0x23, 0x29) or 0x2D <= op <= 0x3D or 0x44 <= op <= 0x6D or 0x90 <= op <= 0xAF or 0xD0 <= op <= 0xE2:
                width = 2
            elif op <= 0x12 or op in (0x1D, 0x1E, 0x21, 0x27, 0x28) or 0x7B <= op <= 0x8F or 0xB0 <= op <= 0xCF:
                width = 1
            else:
                raise ValueError(f"Unsupported DEX opcode {op:02x} in {method['owner']}.{method['name']}")
            if pc + width > size:
                raise ValueError("DEX instruction exceeds method bounds")
            item = {"codeUnitOffset": pc, "fileOffset": offset + 16 + 2 * pc, "opcode": op, "word": word}
            if op in (0x1A, 0x1B):
                string_index = units[pc + 1] | (units[pc + 2] << 16 if op == 0x1B else 0)
                item.update(string=self.strings[string_index], stringIndex=string_index, register=word >> 8)
            elif op in (0x1C, 0x1F, 0x20, 0x22, 0x23, 0x24, 0x25):
                item.update(type=self.types[units[pc + 1]], register=word >> 8)
            elif 0x52 <= op <= 0x6D:
                item.update(field=self.fields[units[pc + 1]], register=word >> 8)
            elif 0x6E <= op <= 0x72 or 0x74 <= op <= 0x78:
                if op <= 0x72:
                    packed = units[pc + 2]
                    registers = [(packed >> shift) & 15 for shift in (0, 4, 8, 12)] + [(word >> 8) & 15]
                    registers = registers[:word >> 12]
                else:
                    registers = list(range(units[pc + 2], units[pc + 2] + (word >> 8)))
                item.update(method=self.methods[units[pc + 1]], registers=registers)
            elif op in (0x01, 0x04, 0x07):
                item.update(register=(word >> 8) & 15, fromRegister=word >> 12)
            elif op in (0x02, 0x05, 0x08):
                item.update(register=word >> 8, fromRegister=units[pc + 1])
            elif op in (0x03, 0x06, 0x09):
                item.update(register=units[pc + 1], fromRegister=units[pc + 2])
            yield item
            pc += width

    def catch_types(self, method):
        offset = method["codeOffset"]
        if not offset:
            return []
        tries = struct.unpack_from("<H", self.data, offset + 6)[0]
        if not tries:
            return []
        size = struct.unpack_from("<I", self.data, offset + 12)[0]
        pos = offset + 16 + 2 * size + (2 if size & 1 else 0) + tries * 8
        count, pos = uleb(self.data, pos)
        types = []
        for _ in range(count):
            length, pos = sleb(self.data, pos)
            for _ in range(abs(length)):
                kind, pos = uleb(self.data, pos)
                _, pos = uleb(self.data, pos)
                types.append(self.types[kind])
            if length <= 0:
                _, pos = uleb(self.data, pos)
        return types


def load_apk(path):
    dex_files, definitions, duplicates = {}, {}, []
    with zipfile.ZipFile(path) as archive:
        for name in sorted(n for n in archive.namelist() if re.fullmatch(r"classes(?:\d+)?\.dex", n)):
            dex = Dex(name, archive.read(name))
            duplicates.extend(set(definitions).intersection(dex.classes))
            definitions.update(dex.classes)
            dex_files[name] = dex
    if not dex_files:
        raise ValueError("APK contains no classes*.dex")
    return dex_files, definitions, sorted(duplicates)


def compiled_pattern(dex, definition):
    fields = [field for field in definition["fields"] if field["name"] == "scriptPattern"]
    if (definition["descriptor"] != SOURCE_CLASS or len(fields) != 1
            or fields[0]["owner"] != SOURCE_CLASS or fields[0]["descriptor"] != REGEX_CLASS
            or fields[0]["accessFlags"] & (8 | 16) != (8 | 16)
            or fields[0]["declarationKind"] != "static"):
        raise ValueError("Expected an actual static final Regex scriptPattern field declaration")
    initializers = [m for m in definition["methods"] if m["name"] == "<clinit>" and m["descriptor"] == "()V"]
    if len(initializers) != 1:
        raise ValueError("Expected exactly one TextReaderImageSource.<clinit>")
    method = initializers[0]
    if (method["owner"] != SOURCE_CLASS or not method["accessFlags"] & 8
            or method["declarationKind"] != "direct" or not method["codeOffset"]):
        raise ValueError("Expected an actual static direct TextReaderImageSource.<clinit>")
    instructions = list(dex.instructions(method))
    assignments = [item for item in instructions if 0x67 <= item["opcode"] <= 0x6D
                   and item["field"]["owner"] == SOURCE_CLASS and item["field"]["name"] == "scriptPattern"]
    if len(assignments) != 1:
        raise ValueError("Expected exactly one scriptPattern assignment in <clinit>")
    registers, trace = {}, []
    for instruction in instructions:
        trace.append(instruction)
        op = instruction["opcode"]
        if op in (0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09):
            registers[instruction["register"]] = registers.get(instruction["fromRegister"])
        elif op in (0x1A, 0x1B):
            registers[instruction["register"]] = {"kind": "string", "value": instruction["string"],
                                                    "instructionOffset": instruction["fileOffset"]}
        elif op == 0x22:
            registers[instruction["register"]] = {"kind": "instance", "type": instruction["type"]}
        elif 0x60 <= op <= 0x66:
            registers[instruction["register"]] = {"kind": "field", **instruction["field"]}
        elif op in (0x70, 0x76):
            target = instruction["method"]
            if target["owner"] == REGEX_CLASS and target["name"] == "<init>":
                if target["descriptor"] != "(Ljava/lang/String;Lkotlin/text/RegexOption;)V":
                    raise ValueError("Unsupported Regex constructor; update this verifier before release")
                receiver, literal, option = [registers.get(r, {}) for r in instruction["registers"]]
                if receiver.get("type") != REGEX_CLASS or literal.get("kind") != "string" or option.get("owner") != "Lkotlin/text/RegexOption;":
                    raise ValueError("Cannot bind Regex constructor operands")
                receiver.update(pattern=literal["value"], option=option["name"],
                                stringInstructionOffset=literal["instructionOffset"],
                                constructorInstructionOffset=instruction["fileOffset"])
        elif 0x67 <= op <= 0x6D:
            field = instruction["field"]
            if field["owner"] == SOURCE_CLASS and field["name"] == "scriptPattern":
                value = registers.get(instruction["register"], {})
                if op != 0x69 or field["descriptor"] != REGEX_CLASS or "pattern" not in value:
                    raise ValueError("scriptPattern is not assigned the traced Regex instance")
                return {"dexValue": value["pattern"], "dexRegexOption": value["option"], "dex": dex.name,
                        "classDescriptor": SOURCE_CLASS, "method": "<clinit>()V", "methodIndex": method["methodIndex"],
                        "codeOffset": method["codeOffset"], "stringInstructionOffset": value["stringInstructionOffset"],
                        "constructorInstructionOffset": value["constructorInstructionOffset"],
                        "fieldAssignmentInstructionOffset": instruction["fileOffset"], "instructionTrace": trace}
        elif op == 0x00:
            pass
        else:
            raise ValueError(f"Unsupported <clinit> instruction {op:02x} before scriptPattern assignment")
    raise ValueError("No traced assignment to TextReaderImageSource.scriptPattern")


def kotlin_string(literal):
    result, index = [], 1
    escapes = {"t": "\t", "b": "\b", "n": "\n", "r": "\r", "'": "'", '"': '"', "\\": "\\", "$": "$"}
    while index < len(literal) - 1:
        char = literal[index]
        index += 1
        if char == "\\":
            escaped = literal[index]
            index += 1
            if escaped == "u":
                result.append(chr(int(literal[index:index + 4], 16)))
                index += 4
            elif escaped in escapes:
                result.append(escapes[escaped])
            else:
                raise ValueError(f"Unsupported Kotlin string escape: {escaped}")
        elif char == "$":
            raise ValueError("Kotlin string interpolation is not supported by this verifier")
        else:
            result.append(char)
    return "".join(result)


def source_pattern(path):
    source = path.read_text(encoding="utf-8")
    start = source.index("object TextReaderImageSource")
    matches = list(re.finditer(r'\bscriptPattern\s*=\s*Regex\(\s*("(?:\\.|[^"\\])*")\s*,\s*RegexOption\.(\w+)\s*\)', source[start:]))
    if len(matches) != 1:
        raise ValueError("Expected one literal scriptPattern Regex in TextReaderImageSource source")
    match = matches[0]
    return {"sourceValue": kotlin_string(match.group(1)), "sourceRegexOption": match.group(2),
            "sourcePath": str(path.resolve()), "sourceSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "sourceLine": source.count("\n", 0, start + match.start()) + 1}


def dependencies(dex_files, definitions, platform_classes):
    selected, referenced, member_references = {}, set(), {}
    all_types = {t for dex in dex_files.values() for d in dex.types if (t := object_type(d))}
    for name, definition in sorted(definitions.items()):
        if not name.startswith(PREFIX):
            continue
        dex = dex_files[definition["dex"]]
        declaration_types = {definition["superclass"], *definition["interfaces"]}
        declaration_types.update(f["descriptor"] for f in definition["fields"])
        for method in definition["methods"]:
            declaration_types.update([method["return"], *method["arguments"]])
            referenced.update(dex.catch_types(method))
            for item in dex.instructions(method):
                if "type" in item:
                    referenced.add(item["type"])
                if "field" in item:
                    field = item["field"]
                    referenced.update((field["owner"], field["descriptor"]))
                    member_references[("field", field["owner"], field["name"], field["descriptor"])] = field
                if "method" in item:
                    target = item["method"]
                    referenced.update([target["owner"], target["return"], *target["arguments"]])
                    member_references[("method", target["owner"], target["name"], target["descriptor"])] = target
        declaration_types = {t for d in declaration_types if d and (t := object_type(d))}
        referenced.update(declaration_types)
        selected[name] = {"dex": dex.name, "superclass": definition["superclass"],
                          "interfaces": definition["interfaces"], "declarationTypeReferences": sorted(declaration_types),
                          "declaredMethodCount": len(definition["methods"])}
    # Audit superclass/interface chains, including Kotlin coroutine base classes.
    hierarchy = set(selected)
    queue = list(selected)
    while queue:
        name = queue.pop()
        definition = definitions.get(name)
        if not definition:
            continue
        for parent in [definition["superclass"], *definition["interfaces"]]:
            if parent and parent not in hierarchy:
                hierarchy.add(parent)
                queue.append(parent)
    referenced.update(hierarchy)
    referenced = {t for d in referenced if (t := object_type(d))}
    external = sorted(referenced - definitions.keys())
    if platform_classes is None:
        missing = [t for t in external if not t.startswith(PLATFORM_PREFIXES)]
    else:
        missing = [t for t in external if t not in platform_classes]
    # Exact member resolution within the APK. Reaching the boot classpath is
    # reported separately; this deliberately makes no ART verification claim.
    def resolve_member(owner, name, descriptor, kind, seen=None, inherited=False):
        seen = set() if seen is None else seen
        if owner in seen:
            return "absent"
        seen.add(owner)
        definition = definitions.get(owner)
        if definition is None:
            if inherited:
                # Do not turn a missing APK member into success merely because
                # every class eventually inherits Object. These are the stable
                # Object declarations available from Android API 1; Object has
                # no fields. Other platform inheritance needs explicit support.
                return "platform" if owner == "Ljava/lang/Object;" and kind == "method" and (name, descriptor) in OBJECT_METHODS else "absent"
            return "platform" if owner in external or owner.startswith(PLATFORM_PREFIXES) else "absent"
        members = definition["methods" if kind == "method" else "fields"]
        if any(m["name"] == name and m["descriptor"] == descriptor for m in members):
            return "apk"
        if name == "<init>":
            return "absent"
        parents = [definition["superclass"], *definition["interfaces"]]
        statuses = [resolve_member(p, name, descriptor, kind, seen, inherited=True) for p in parents if p]
        return "apk" if "apk" in statuses else "platform" if "platform" in statuses else "absent"
    unresolved_members, platform_members, resolved_members = [], [], 0
    for (kind, owner, name, descriptor), reference in sorted(member_references.items()):
        status = resolve_member(owner, name, descriptor, kind)
        detail = {"kind": kind, **reference}
        if status == "absent":
            unresolved_members.append(detail)
        elif status == "platform":
            platform_members.append(detail)
        else:
            resolved_members += 1
    return {"selectedClasses": selected, "selectedClassCount": len(selected),
            "hierarchyTypeReferences": sorted(hierarchy), "selectedTypeReferences": sorted(referenced),
            "missingSelectedTypeReferences": missing, "platformTypeReferences": external,
            "platformTypesCheckedAgainstAndroidJar": platform_classes is not None,
            "missingAppTypeReferences": sorted(t for t in all_types if t.startswith("Lio/legado/") and t not in definitions),
            "missingDesugarTypeReferences": sorted(t for t in all_types if t.startswith("Lj$/") and t not in definitions),
            "resolvedApkMemberReferences": resolved_members, "unresolvedMemberReferences": unresolved_members,
            "platformMemberReferencesNotRuntimeVerified": platform_members}


def nio_evidence(dex_files, definitions):
    required = ("Lj$/io/FileRetargetClass;", "Lj$/nio/file/Files;", "Lj$/nio/file/Path;", "Lj$/nio/file/CopyOption;",
                "Lj$/nio/file/StandardCopyOption;", "Ljava/nio/file/AtomicMoveNotSupportedException;")
    definition = definitions.get(RESOURCE_CLASS)
    calls, initialization = [], []
    if definition:
        dex = dex_files[definition["dex"]]
        for method in definition["methods"]:
            if method["name"] not in ("persist", "<clinit>"):
                continue
            for instruction in dex.instructions(method):
                target = instruction.get("method")
                if target and (method["name"] == "<clinit>" or target["owner"].startswith(("Lj$/", "Ljava/nio/"))):
                    (initialization if method["name"] == "<clinit>" else calls).append(
                        {"caller": method["name"] + method["descriptor"], "fileOffset": instruction["fileOffset"], **target})
    return {"requiredClassDefinitions": {t: definitions[t]["dex"] if t in definitions else None for t in required},
            "persistCalls": calls, "resourceStaticInitializerCalls": initialization,
            "androidRuntimeVerified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--source", type=Path, default=SOURCE_DEFAULT)
    parser.add_argument("--baseline-apk", type=Path)
    parser.add_argument("--android-jar", type=Path, help="Optional platform class inventory; does not run ART")
    parser.add_argument("--api-versions", type=Path, help="Optional Android SDK data/api-versions.xml")
    parser.add_argument("--min-sdk", type=int, default=21)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = {"schemaVersion": 1, "androidRuntimeVerified": False, "nativeIcuVerified": False,
              "apk": {"path": str(args.apk.resolve()), "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                      "size": args.apk.stat().st_size}}
    failures = []
    try:
        dex_files, definitions, duplicates = load_apk(args.apk)
        report["dex"] = {"classDefinitionCount": len(definitions), "duplicateClassDefinitions": duplicates,
                         "files": [dex.integrity for dex in dex_files.values()]}
        if duplicates or any(not d.integrity["sha1Matches"] or not d.integrity["adler32Matches"] for d in dex_files.values()):
            failures.append("Invalid DEX integrity or duplicate class definitions")
        definition = definitions.get(SOURCE_CLASS)
        if definition is None:
            raise ValueError("TextReaderImageSource has no actual class_def in this APK")
        pattern = {**compiled_pattern(dex_files[definition["dex"]], definition), **source_pattern(args.source)}
        pattern["matchesSource"] = pattern["dexValue"] == pattern["sourceValue"] and pattern["dexRegexOption"] == pattern["sourceRegexOption"]
        report["scriptPattern"] = pattern
        if not pattern["matchesSource"]:
            failures.append("APK scriptPattern or RegexOption differs from current source")
        platform_classes = None
        if args.android_jar:
            with zipfile.ZipFile(args.android_jar) as archive:
                platform_classes = {"L" + n[:-6] + ";" for n in archive.namelist() if n.endswith(".class")}
            report["androidJar"] = {"path": str(args.android_jar.resolve()), "sha256": hashlib.sha256(args.android_jar.read_bytes()).hexdigest()}
        audit = dependencies(dex_files, definitions, platform_classes)
        report["dependencies"] = audit
        for key in ("missingSelectedTypeReferences", "missingAppTypeReferences", "missingDesugarTypeReferences", "unresolvedMemberReferences"):
            if audit[key]:
                failures.append(f"{key}: {len(audit[key])}")
        report["nio"] = nio_evidence(dex_files, definitions)
        moves = [call for call in report["nio"]["persistCalls"] if call["name"] == "move"]
        if args.min_sdk < 26 and moves:
            if any(call["owner"] != "Lj$/nio/file/Files;" for call in moves):
                failures.append("Image persistence uses platform NIO below Android API 26")
            if any(value is None for value in report["nio"]["requiredClassDefinitions"].values()):
                failures.append("Image persistence is missing a required NIO backport class definition")
        if args.baseline_apk:
            _, baseline, _ = load_apk(args.baseline_apk)
            report["baseline"] = {"path": str(args.baseline_apk.resolve()),
                                  "sha256": hashlib.sha256(args.baseline_apk.read_bytes()).hexdigest(),
                                  "newSelectedClasses": sorted(n for n in audit["selectedClasses"] if n not in baseline),
                                  "removedAppClasses": sorted(n for n in baseline if n.startswith("Lio/legado/") and n not in definitions)}
        if args.api_versions:
            platform_api = {"L" + c.attrib["name"] + ";": c for c in ET.parse(args.api_versions).getroot().findall("class")}
            higher = []
            for name in audit["platformTypeReferences"]:
                metadata = platform_api.get(name)
                if metadata is not None and int(metadata.get("since", "1")) > args.min_sdk:
                    higher.append({"descriptor": name, "since": int(metadata.get("since"))})
            report["platformApi"] = {"minSdkAssumption": args.min_sdk, "typeReferencesAboveMinSdk": higher,
                                     "note": "Class availability only; guarded platform calls and ART verification require runtime validation."}
    except (ValueError, KeyError, IndexError, struct.error, UnicodeError, OSError, zipfile.BadZipFile) as error:
        failures.append(str(error))
    report["failures"] = failures
    report["ok"] = report["passed"] = not failures
    report["apkSha256"] = report["apk"]["sha256"]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"ok": report["ok"], "passed": report["passed"], "apkSha256": report["apkSha256"],
                      "report": str(args.output.resolve()), "failures": failures}, ensure_ascii=True))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
