#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""
Publishes the screenshots to docs/screenshots/, one folder and README per app section.

Sources:
  phone/src/test/snapshots/images   Paparazzi goldens (./gradlew recordPaparazziDebug)
  wear/src/test/snapshots/images    Paparazzi goldens; the card goldens go through card_previews.py
  docs/screenshots/wear/tiles       written by tools/screenshots/card_previews.py
  docs/screenshots/widgets          written by WidgetRenderTest with HEARTLINE_WIDGET_SHOTS=1

Every PNG is optimised losslessly with oxipng (pip install -r tools/screenshots/requirements.txt).

  python3 tools/screenshots/sync.py           update docs/screenshots
  python3 tools/screenshots/sync.py --check   fail if docs/screenshots is out of date (CI)
"""
import filecmp
import pathlib
import re
import shutil
import sys
import tempfile

import oxipng

ROOT = pathlib.Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs" / "screenshots"
GOLDENS = {
    "phone": ROOT / "phone/src/test/snapshots/images",
    "wear": ROOT / "wear/src/test/snapshots/images",
}
VARIANTS = {"phone": ("light", "dark"), "wear": ("small", "large")}
VARIANT_TITLES = {"light": "Light", "dark": "Dark", "small": "Small watch (192 dp)", "large": "Large watch (454 px)"}
WIDTH = {"phone": 240, "wear": 200, "tiles": 160, "widgets": 260}

# (slug, title, description, [(screen, caption)…]). Screens not listed here are placed by
# PREFIXES, or end up in "other" with a caption made from their name.
PHONE = [
    ("onboarding", "Onboarding", "First launch: terms, welcome, profile and connecting the watch.", [
        ("terms", "Terms of Use and Privacy Policy, accepted before anything else"),
        ("onboarding", "Welcome"),
        ("profile", "Your profile"),
        ("profile_errors", "Profile with errors shown under each field"),
        ("profile_prefer_not_to_say", "Profile: sex not given"),
        ("connect_watch_found", "Connect your watch: watch found"),
        ("connect_watch_app_missing", "Connect your watch: Heartline not installed on the watch"),
    ]),
    ("home", "Home", "Today at a glance, with a card for every measurement.", [
        ("home", "Home"),
        ("home_scrolled", "Home, scrolled"),
        ("home_empty", "Home before the first measurement"),
        ("home_no_watch", "Home without a connected watch"),
    ]),
    ("ecg", "ECG", "30-second ECG history, the waveform on ECG paper, symptoms and the PDF report.", [
        ("ecg_home", "ECG overview"),
        ("ecg_home_empty", "ECG overview, no recordings yet"),
        ("ecg_history", "History"),
        ("ecg_detail_sinus", "Recording: sinus rhythm"),
        ("ecg_detail_afib", "Recording: signs of AFib"),
        ("ecg_detail_recording", "Recording details"),
        ("symptoms_sheet", "Symptoms"),
        ("ecg_report_page", "PDF report"),
    ]),
    ("blood-pressure", "Blood pressure", "Cuff calibration, trends and the accuracy of the watch estimate.", [
        ("bp_home", "Blood pressure overview"),
        ("bp_home_uncalibrated", "Before calibration"),
        ("bp_home_accuracy", "Accuracy against your cuff"),
        ("bp_calibration_intro", "Calibration: introduction"),
        ("bp_calibration_cuff", "Calibration: enter the cuff reading"),
        ("bp_calibration_waiting", "Calibration: waiting for the watch"),
        ("bp_calibration_done", "Calibration done"),
    ]),
    ("heart-rate", "Heart rate and alerts", "Heart rate over the day and week, resting rate, HRV and rhythm alerts.", [
        ("heart_rate", "Heart rate"),
        ("heart_rate_empty", "Heart rate, no data yet"),
        ("hr_chart_selected", "Day chart with a selected hour"),
        ("hr_chart_week", "Week chart"),
        ("alerts", "Irregular rhythm and high / low heart rate alerts"),
    ]),
    ("more-measurements", "SpO₂, temperature and stress", "Blood oxygen, skin temperature and stress details.", [
        ("spo2", "Blood oxygen"),
        ("skin_temperature", "Skin temperature"),
        ("stress", "Stress"),
    ]),
    ("body-composition", "Body composition", "Body fat, muscle, water and body type, with trends and ranges.", [
        ("body_composition", "Body composition"),
        ("body_composition_measures", "All measures"),
        ("body_composition_more", "Ranges and body type"),
    ]),
    ("settings", "Settings, updates and sharing", "Settings, updates, what's new, sharing, help and about.", [
        ("settings", "Settings"),
        ("settings_monitoring", "Background monitoring"),
        ("settings_sharing", "Sharing"),
        ("settings_updates", "Updates and beta versions"),
        ("update_up_to_date", "Up to date"),
        ("whats_new", "What's new after an update"),
        ("share_sheet", "Share a result, with AI apps"),
        ("share_sheet_no_ai", "Share a result, no AI apps installed"),
        ("dev_mode_help", "Help: turning on developer mode on the watch"),
        ("about", "About"),
        ("legal", "Terms of Use"),
        ("launcher_icon", "App icon"),
    ]),
]

WEAR = [
    ("setup", "Setup", "Connecting to the phone, permissions and the sensor check.", [
        ("setup_checking_phone", "Connecting to the phone"),
        ("setup_no_phone", "Phone not found"),
        ("setup_no_response", "Phone not responding"),
        ("setup_app_missing", "Heartline missing on the phone"),
        ("setup_incomplete", "Finish setup on the phone"),
        ("setup_terms", "Accept the terms on the phone"),
        ("profile_needed", "Profile needed"),
        ("setup_permissions", "Permissions"),
        ("setup_checking_sensors", "Checking the sensors"),
        ("dev_mode", "Developer mode guide"),
        ("dev_mode_end", "Developer mode guide, last step"),
        ("error_sdk_policy", "Sensor access blocked (developer mode off)"),
        ("error_not_supported", "Measurement not supported on this watch"),
        ("diagnostics", "Diagnostics"),
    ]),
    ("launcher", "Launcher and history", "The start screen, its options, history and celebrations.", [
        ("launcher", "Launcher"),
        ("launcher_birthday", "Launcher on your birthday"),
        ("launcher_options", "Choose what the launcher shows"),
        ("history", "History"),
        ("confetti_late", "Daily goal reached"),
        ("edge_glow", "Edge glow effect"),
    ]),
    ("ecg", "ECG", "Recording a 30-second ECG and its result.", [
        ("ecg_instruction", "How to record"),
        ("ecg_waiting", "Waiting for your finger"),
        ("ecg_arming", "Checking contact"),
        ("ecg_measuring", "Recording"),
        ("ecg_lead_off", "Finger lifted"),
        ("ecg_struggling", "Weak signal hint"),
        ("ecg_analyzing", "Analysing"),
        ("ecg_result_sinus", "Result: sinus rhythm"),
        ("ecg_result_afib", "Result: signs of AFib"),
        ("ecg_result_inconclusive_note", "Result: inconclusive, with the reason"),
        ("ecg_result_poor", "Result: poor recording, with the reason"),
    ]),
    ("blood-pressure", "Blood pressure", "Calibrated blood pressure estimates from the PPG sensor.", [
        ("bp_instruction", "How to measure"),
        ("bp_measuring", "Measuring"),
        ("bp_measuring_live", "Measuring, live pulse"),
        ("bp_moving", "Keep your arm still"),
        ("bp_result", "Result"),
        ("bp_result_beyond", "Result beyond the calibration range"),
        ("bp_result_very_high", "Very high result"),
        ("bp_out_of_range", "Out of range"),
        ("bp_needs_calibration", "Calibration needed"),
        ("bp_needs_calibration_opened", "Calibration needed, opened on the phone"),
        ("bp_calibration_round", "Calibration round"),
        ("bp_calibration_recorded", "Calibration round recorded"),
    ]),
    ("heart-rate", "Heart rate", "Live heart rate.", [
        ("heart_rate", "Heart rate"),
        ("heart_rate_off_body", "Watch not on the wrist"),
    ]),
    ("more-measurements", "SpO₂, temperature and stress", "Quick measurements.", [
        ("spo2_instruction", "Blood oxygen: how to measure"),
        ("spo2_measuring", "Blood oxygen: measuring"),
        ("spo2_measuring_live", "Blood oxygen: measuring, live"),
        ("spo2_result", "Blood oxygen: result"),
        ("temperature_measuring", "Skin temperature: measuring"),
        ("temp_result", "Skin temperature: result"),
        ("stress_measuring", "Stress: measuring"),
        ("stress_result", "Stress: result"),
    ]),
    ("body-composition", "Body composition", "Bioelectrical impedance with the two keys.", [
        ("body_weight", "Today's weight, set with the bezel"),
        ("body_instruction", "How to measure"),
        ("body_top_key", "Finger missing on the upper key"),
        ("body_measuring", "Measuring"),
        ("body_result", "Result"),
        ("body_result_cards", "Result details"),
    ]),
    ("settings", "Settings", "Watch settings.", [
        ("settings", "Settings"),
        ("settings_toggles", "Settings: alerts and effects"),
        ("about", "About"),
    ]),
]

PREFIXES = {
    "phone": [("connect_watch", "onboarding"), ("profile", "onboarding"), ("home", "home"), ("ecg", "ecg"),
              ("bp_", "blood-pressure"), ("heart_rate", "heart-rate"), ("hr_", "heart-rate"),
              ("body_composition", "body-composition"), ("settings", "settings"), ("share", "settings")],
    "wear": [("setup", "setup"), ("error", "setup"), ("dev_mode", "setup"), ("launcher", "launcher"), ("ecg", "ecg"),
             ("bp_", "blood-pressure"), ("heart_rate", "heart-rate"), ("spo2", "more-measurements"),
             ("temp", "more-measurements"), ("stress", "more-measurements"), ("body", "body-composition"),
             ("settings", "settings")],
}

CARDS = [
    ("heart", "Heart rate"), ("heart_empty", "Heart rate, no data yet"), ("bp", "Blood pressure"), ("ecg", "ECG"),
    ("spo2", "Blood oxygen"), ("stress", "Stress"), ("body", "Body composition"), ("today", "Today"),
    ("wellness", "Wellness"), ("quick", "Measure"),
]
WIDGETS = {
    "dashboard": "Dashboard", "heart_rate": "Heart rate", "ecg": "ECG", "bp": "Blood pressure", "stress": "Stress",
    "quick": "Measure on watch", "heart_day": "Heart rate today", "measure": "Measure button",
    "tile_hr": "Health tile: heart rate", "tile_bp": "Health tile: blood pressure", "tile_ecg": "Health tile: ECG",
    "tile_spo2": "Health tile: blood oxygen", "tile_body": "Health tile: body composition",
    "tile_stress": "Health tile: stress", "tile_hr_wallpaper": "Health tile on the wallpaper theme",
}


def snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def golden_name(file: str) -> tuple[str, str | None]:
    """com.heartline.phone_PhoneScreenshotTest_home[dark]_home.png -> ("home", "dark");
    com.heartline.phone_ReportScreenshotTest_ecgReportPage.png -> ("ecg_report_page", None)."""
    stem = file.removesuffix(".png").split("_", 2)[2]
    m = re.match(r"^[^\[]+\[([a-z0-9]+)\]_(.+)$", stem)
    if m:
        return m.group(2), m.group(1)
    return snake(stem), None


def caption(name: str) -> str:
    return name.replace("_", " ").capitalize()


def collect(platform: str) -> dict[str, dict[str | None, pathlib.Path]]:
    screens: dict[str, dict[str | None, pathlib.Path]] = {}
    for path in sorted(GOLDENS[platform].glob("*.png")):
        if "CardScreenshotTest" in path.name:
            continue
        name, variant = golden_name(path.name)
        screens.setdefault(name, {})[variant] = path
    return screens


def section_of(platform: str, name: str, config) -> str:
    for slug, _, _, listed in config:
        if any(screen == name for screen, _ in listed):
            return slug
    for prefix, slug in PREFIXES[platform]:
        if name.startswith(prefix):
            return slug
    return "other"


def img(path: str, width: int) -> str:
    return f'<img src="{path}" width="{width}" alt="">'


def write_platform(out: pathlib.Path, platform: str, config) -> list[tuple[str, str, str, str, int]]:
    screens = collect(platform)
    sections: dict[str, list[tuple[str, str]]] = {slug: [] for slug, *_ in config}
    for slug, _, _, listed in config:
        sections[slug] = [(screen, cap) for screen, cap in listed if screen in screens]
    for name in screens:
        slug = section_of(platform, name, config)
        if not any(name == screen for screen, _ in sections.get(slug, [])):
            sections.setdefault(slug, []).append((name, caption(name)))
    titles = {slug: (title, text) for slug, title, text, _ in config}
    titles.setdefault("other", ("Other", "Screens not yet assigned to a section."))
    index = []
    for slug, rows in sections.items():
        if not rows:
            continue
        folder = out / platform / slug
        folder.mkdir(parents=True, exist_ok=True)
        title, text = titles[slug]
        a, b = VARIANTS[platform]
        lines = [f"# {'Phone' if platform == 'phone' else 'Watch'} · {title}", "", text, "",
                 "[← All screenshots](../../README.md)", "", f"| | {VARIANT_TITLES[a]} | {VARIANT_TITLES[b]} |", "|---|:-:|:-:|"]
        hero = None
        for name, cap in rows:
            cells = []
            for variant in (a, b):
                src = screens[name].get(variant) or (screens[name].get(None) if variant == a else None)
                if src is None:
                    cells.append("—")
                    continue
                file = f"{name}_{variant}.png" if variant in screens[name] else f"{name}.png"
                shutil.copyfile(src, folder / file)
                cells.append(img(file, WIDTH[platform]))
                hero = hero or f"{platform}/{slug}/{file}"
            lines.append(f"| **{cap}** | {cells[0]} | {cells[1]} |")
        (folder / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        index.append((platform, slug, title, hero, len(rows)))
    return index


def write_tiles(out: pathlib.Path):
    folder = out / "wear" / "tiles"
    files = {p.name for p in folder.glob("*.png")} if folder.exists() else set()
    if not files:
        return None
    lines = ["# Watch · Tiles and cards", "",
             "Heartline cards for the tile stack (small and large, One UI 9 Watch / Wear OS 7) and full-screen tiles "
             "for older watches. Complications show the same values on the watch face.", "",
             "[← All screenshots](../../README.md)", "", "| | Small card | Large card | Full-screen tile |", "|---|:-:|:-:|:-:|"]
    for name, cap in CARDS:
        cells = [img(f"{name}_{size}.png", WIDTH["tiles"]) if f"{name}_{size}.png" in files else "—" for size in ("small", "large", "full")]
        if any(c != "—" for c in cells):
            lines.append(f"| **{cap}** | {' | '.join(cells)} |")
    (folder / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return ("wear", "tiles", "Tiles and cards", "wear/tiles/heart_large.png", len(CARDS))


def write_widgets(out: pathlib.Path):
    folder = out / "widgets"
    files = sorted(p.name for p in folder.glob("*.png")) if folder.exists() else []
    if not files:
        return None
    groups: dict[tuple[str, int], set[str]] = {}
    for f in files:
        m = re.match(r"^(.+)_(\d+)_(full|empty)_(light|dark)\.png$", f)
        if m:
            groups.setdefault((m.group(1), int(m.group(2))), set()).add(f)
    lines = ["# Phone · Widgets", "",
             "Home screen widgets in every size, with data and before the first measurement.", "",
             "[← All screenshots](../README.md)", "", "| | Light | Dark | No data yet |", "|---|:-:|:-:|:-:|"]
    order = list(WIDGETS)
    for (name, size), group in sorted(groups.items(), key=lambda g: (order.index(g[0][0]) if g[0][0] in order else 99, g[0][1])):
        cells = [img(f, WIDTH["widgets"]) if f in group else "—"
                 for f in (f"{name}_{size}_full_light.png", f"{name}_{size}_full_dark.png", f"{name}_{size}_empty_light.png")]
        lines.append(f"| **{WIDGETS.get(name, caption(name))}**<br>size {size + 1} | {' | '.join(cells)} |")
    (folder / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return ("phone", "widgets", "Widgets", "widgets/dashboard_2_full_light.png", len(groups))


def write_index(out: pathlib.Path, index) -> None:
    lines = ["# Screenshots", "",
             "Every screen of the phone and watch apps, rendered by the Paparazzi and Robolectric tests. "
             "Pick a section to see all of its screens. The phone is shown in light and dark, the watch "
             "on a small and a large round screen.", "",
             "Regenerate with `./gradlew recordPaparazziDebug` and `python3 tools/screenshots/sync.py` "
             "(see [CONTRIBUTING.md](../../CONTRIBUTING.md)). Don't edit these files by hand.", ""]
    for platform, heading in (("phone", "Phone"), ("wear", "Watch")):
        entries = [e for e in index if e and e[0] == platform]
        lines += [f"## {heading}", "", "| Section | Preview | Screens |", "|---|:-:|:-:|"]
        for _, slug, title, hero, count in entries:
            link = "widgets/README.md" if slug == "widgets" else f"{platform}/{slug}/README.md"
            width = 160 if platform == "wear" else 120
            lines.append(f"| [**{title}**]({link}) | [{img(hero, width)}]({link}) | {count} |")
        lines.append("")
    (out / "README.md").write_text("\n".join(lines), encoding="utf-8")


def optimise(out: pathlib.Path) -> None:
    for png in sorted(out.rglob("*.png")):
        oxipng.optimize(png, level=4, strip=oxipng.StripChunks.safe())


def build(out: pathlib.Path) -> None:
    for platform in ("phone", "wear"):
        target = out / platform
        if target.exists():
            for child in target.iterdir():
                if child.name != "tiles":
                    shutil.rmtree(child) if child.is_dir() else child.unlink()
    index = write_platform(out, "phone", PHONE)
    index.append(write_widgets(out))
    index += write_platform(out, "wear", WEAR)
    index.append(write_tiles(out))
    write_index(out, index)
    optimise(out)


def main() -> int:
    if "--check" not in sys.argv:
        build(DOCS)
        print(f"Synced {sum(1 for _ in DOCS.rglob('*.png'))} screenshots to {DOCS.relative_to(ROOT)}")
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        copy = pathlib.Path(tmp) / "screenshots"
        shutil.copytree(DOCS, copy)
        build(copy)
        diff = filecmp.dircmp(DOCS, copy)
        problems = []

        def walk(d, rel=""):
            problems.extend(f"{rel}{n}" for n in d.left_only + d.right_only + d.diff_files)
            for name, sub in d.subdirs.items():
                walk(sub, f"{rel}{name}/")

        walk(diff)
        if problems:
            print("docs/screenshots is out of date; run python3 tools/screenshots/sync.py and commit:")
            print("\n".join(f"  {p}" for p in problems[:50]))
            return 1
    print("docs/screenshots is up to date")
    return 0


if __name__ == "__main__":
    sys.exit(main())
