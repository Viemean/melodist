#!/usr/bin/env python3
"""
Melodist 项目依赖更新检查与同步工具。
支持查询 Maven Central、Google Maven、Gradle Plugin Portal 的最新版本，
并检查 gradle/libs.versions.toml 的依赖版本状态。
"""

import argparse
import json
import re
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
from packaging.version import parse as parse_version
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
TOML_PATH = PROJECT_ROOT / "gradle" / "libs.versions.toml"
WRAPPER_PROPS_PATH = PROJECT_ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"

DEPENDENCY_MAP = {
    "agp": [
        (
            "https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml",
            "google",
        )
    ],
    "kotlin": [
        (
            "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml",
            "central",
        )
    ],
    "kotlinx-coroutines": [
        (
            "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml",
            "central",
        )
    ],
    "kotlinx-serialization": [
        (
            "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml",
            "central",
        )
    ],
    "okhttp": [
        (
            "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml",
            "central",
        )
    ],
    "media3": [
        (
            "https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer/maven-metadata.xml",
            "google",
        )
    ],
    "coil": [
        (
            "https://repo1.maven.org/maven2/io/coil-kt/coil3/coil/maven-metadata.xml",
            "central",
        )
    ],
    "junit": [
        (
            "https://repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter/maven-metadata.xml",
            "central",
        )
    ],
    "androidx-core-ktx": [
        (
            "https://dl.google.com/android/maven2/androidx/core/core-ktx/maven-metadata.xml",
            "google",
        )
    ],
    "androidx-lifecycle": [
        (
            "https://dl.google.com/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx/maven-metadata.xml",
            "google",
        )
    ],
    "androidx-activity-compose": [
        (
            "https://dl.google.com/android/maven2/androidx/activity/activity-compose/maven-metadata.xml",
            "google",
        )
    ],
    "tv-foundation": [
        (
            "https://dl.google.com/android/maven2/androidx/tv/tv-foundation/maven-metadata.xml",
            "google",
        )
    ],
    "tv-material": [
        (
            "https://dl.google.com/android/maven2/androidx/tv/tv-material/maven-metadata.xml",
            "google",
        )
    ],
    "compose-bom": [
        (
            "https://dl.google.com/android/maven2/androidx/compose/compose-bom/maven-metadata.xml",
            "google",
        )
    ],
    "androidx-navigation": [
        (
            "https://dl.google.com/android/maven2/androidx/navigation/navigation-compose/maven-metadata.xml",
            "google",
        )
    ],
    "androidx-palette": [
        (
            "https://dl.google.com/android/maven2/androidx/palette/palette-ktx/maven-metadata.xml",
            "google",
        )
    ],
    "androidx-material-icons": [
        (
            "https://dl.google.com/android/maven2/androidx/compose/material/material-icons-extended/maven-metadata.xml",
            "google",
        )
    ],
    "detekt": [
        (
            "https://repo1.maven.org/maven2/io/gitlab/arturbosch/detekt/detekt-gradle-plugin/maven-metadata.xml",
            "central",
        )
    ],
    "detekt-rules-compose": [
        (
            "https://repo1.maven.org/maven2/io/nlopez/compose/rules/detekt/maven-metadata.xml",
            "central",
        )
    ],
    "ktlint": [
        (
            "https://plugins.gradle.org/m2/org/jlleitschuh/gradle/ktlint/org.jlleitschuh.gradle.ktlint.gradle.plugin/maven-metadata.xml",
            "gradle",
        )
    ],
    "java-websocket": [
        (
            "https://repo1.maven.org/maven2/org/java-websocket/Java-WebSocket/maven-metadata.xml",
            "central",
        )
    ],
    "zxing": [
        (
            "https://repo1.maven.org/maven2/com/google/zxing/core/maven-metadata.xml",
            "central",
        )
    ],
    "camerax": [
        (
            "https://dl.google.com/android/maven2/androidx/camera/camera-core/maven-metadata.xml",
            "google",
        )
    ],
    "leakcanary": [
        (
            "https://repo1.maven.org/maven2/com/squareup/leakcanary/leakcanary-android/maven-metadata.xml",
            "central",
        )
    ],
    "androidx-media": [
        (
            "https://dl.google.com/android/maven2/androidx/media/media/maven-metadata.xml",
            "google",
        )
    ],
    "baselineprofile": [
        (
            "https://dl.google.com/android/maven2/androidx/baselineprofile/androidx.baselineprofile.gradle.plugin/maven-metadata.xml",
            "google",
        )
    ],
    "benchmark": [
        (
            "https://dl.google.com/android/maven2/androidx/benchmark/benchmark-macro-junit4/maven-metadata.xml",
            "google",
        )
    ],
    "profileinstaller": [
        (
            "https://dl.google.com/android/maven2/androidx/profileinstaller/profileinstaller/maven-metadata.xml",
            "google",
        )
    ],
    "uiautomator": [
        (
            "https://dl.google.com/android/maven2/androidx/test/uiautomator/uiautomator/maven-metadata.xml",
            "google",
        )
    ],
    "androidx-test-ext": [
        (
            "https://dl.google.com/android/maven2/androidx/test/ext/junit/maven-metadata.xml",
            "google",
        )
    ],
    "androidx-test-runner": [
        (
            "https://dl.google.com/android/maven2/androidx/test/runner/maven-metadata.xml",
            "google",
        )
    ],
}


def parse_toml_versions():
    versions = {}
    with open(TOML_PATH, "r", encoding="utf-8") as f:
        in_versions = False
        for line in f:
            stripped = line.strip()
            if stripped.startswith("[versions]"):
                in_versions = True
                continue
            if stripped.startswith("[") and in_versions:
                break
            if in_versions and "=" in stripped and not stripped.startswith("#"):
                k, v = stripped.split("=", 1)
                versions[k.strip()] = v.strip().strip('"').strip("'")
    return versions


def fetch_versions(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            root = ET.fromstring(resp.read())
            raw_versions = [v.text for v in root.findall(".//version") if v.text]
            parsed = []
            for rv in raw_versions:
                try:
                    pv = parse_version(rv)
                    parsed.append((pv, rv))
                except Exception:
                    continue
            parsed.sort(key=lambda x: x[0])
            return [x[1] for x in parsed]
    except Exception:
        return []


def check_gradle_wrapper():
    current_version = None
    if WRAPPER_PROPS_PATH.exists():
        with open(WRAPPER_PROPS_PATH, "r", encoding="utf-8") as f:
            for line in f:
                if line.startswith("distributionUrl"):
                    m = re.search(r"gradle-([0-9.]+)-bin\.zip", line)
                    if m:
                        current_version = m.group(1)
                    break
    if not current_version:
        return None

    try:
        req = urllib.request.Request(
            "https://services.gradle.org/versions/all",
            headers={"User-Agent": "Mozilla/5.0"},
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            releases = [
                v
                for v in data
                if not v.get("snapshot") and not v.get("nightly") and not v.get("broken")
            ]
            stables = [
                v["version"]
                for v in releases
                if not v.get("rcFor") and "milestone" not in v["version"]
            ]
            all_vers = [v["version"] for v in releases]

            parsed_stables = []
            for v in stables:
                try:
                    parsed_stables.append((parse_version(v), v))
                except Exception:
                    pass
            parsed_stables.sort(key=lambda x: x[0])
            latest_stable = parsed_stables[-1][1] if parsed_stables else current_version

            parsed_all = []
            for v in all_vers:
                try:
                    parsed_all.append((parse_version(v), v))
                except Exception:
                    pass
            parsed_all.sort(key=lambda x: x[0])
            latest_all = parsed_all[-1][1] if parsed_all else current_version

            curr_pv = parse_version(current_version)
            latest_pv = parse_version(latest_all)

            if current_version == latest_all:
                status = "Latest"
            elif current_version == latest_stable:
                status = "Latest Stable"
            elif latest_pv > curr_pv:
                status = f"Update Available -> {latest_all}"
            else:
                status = "Up-to-date"

            return current_version, latest_stable, latest_all, status
    except Exception:
        return current_version, "Query Failed", "Query Failed", "Query Failed"


def main():
    parser = argparse.ArgumentParser(
        description="Check and update Gradle version catalog dependencies and wrapper."
    )
    parser.add_argument(
        "--update",
        action="store_true",
        help="Automatically write latest versions into libs.versions.toml and update wrapper",
    )
    args = parser.parse_args()

    current_versions = parse_toml_versions()
    results = []
    updates = {}

    wrapper_info = check_gradle_wrapper()
    wrapper_update = None
    if wrapper_info:
        w_curr, w_stable, w_latest, w_status = wrapper_info
        if "Update Available" in w_status:
            wrapper_update = w_latest
        results.append(("gradle-wrapper", w_curr, w_stable, w_latest, w_status))

    for key, curr in current_versions.items():
        if key not in DEPENDENCY_MAP:
            results.append((key, curr, "-", "-", "Not Mapped"))
            continue

        urls = DEPENDENCY_MAP[key]
        vers = []
        for url, _ in urls:
            vers = fetch_versions(url)
            if vers:
                break

        if not vers:
            results.append((key, curr, "Not Found", "Not Found", "Query Failed"))
            continue

        parsed = [(parse_version(v), v) for v in vers]
        parsed.sort(key=lambda x: x[0])

        stable = [
            v for pv, v in parsed if not pv.is_prerelease and not pv.is_devrelease
        ]
        latest_all = parsed[-1][1]
        latest_stable = stable[-1] if stable else "-"

        curr_pv = parse_version(curr)
        latest_pv = parse_version(latest_all)

        if curr == latest_all:
            status = "Latest"
        elif curr == latest_stable:
            status = "Latest Stable"
        elif latest_pv > curr_pv:
            status = f"Update Available -> {latest_all}"
            updates[key] = latest_all
        else:
            status = "Up-to-date"

        results.append((key, curr, latest_stable, latest_all, status))

    print(
        f"{'Dependency Key':<26} | {'Current':<16} | {'Latest Stable':<16} | {'Latest Release':<16} | {'Status'}"
    )
    print("=" * 105)
    for key, curr, stable, latest, status in results:
        flag = " *" if "Update Available" in status else "  "
        print(f"{flag}{key:<24} | {curr:<16} | {stable:<16} | {latest:<16} | {status}")

    if args.update and updates:
        print(f"\nApplying {len(updates)} update(s) to {TOML_PATH}...")
        with open(TOML_PATH, "r", encoding="utf-8") as f:
            lines = f.readlines()

        new_lines = []
        in_versions = False
        for line in lines:
            if line.strip().startswith("[versions]"):
                in_versions = True
                new_lines.append(line)
                continue
            if line.strip().startswith("[") and in_versions:
                in_versions = False
            if in_versions and "=" in line and not line.strip().startswith("#"):
                k, _ = line.split("=", 1)
                k = k.strip()
                if k in updates:
                    new_lines.append(f'{k} = "{updates[k]}"\n')
                    continue
            new_lines.append(line)

        with open(TOML_PATH, "w", encoding="utf-8") as f:
            f.writelines(new_lines)
        print("Updated gradle/libs.versions.toml successfully.")

    if args.update and wrapper_update:
        print(f"\nUpdating Gradle Wrapper to {wrapper_update}...")
        gradlew = PROJECT_ROOT / "gradlew"
        if gradlew.exists():
            subprocess.run(
                [str(gradlew), "wrapper", "--gradle-version", wrapper_update],
                cwd=PROJECT_ROOT,
                check=True,
            )
            print(f"Updated Gradle Wrapper to {wrapper_update} successfully.")


if __name__ == "__main__":
    main()
