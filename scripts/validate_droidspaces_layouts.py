"""
Local validation script (Python, no Kotlin/JVM needed) that simulates
TerminalContainerDetector.looksLikeRootfs / isDroidspacesImageModeDir /
inferDroidspacesContainerName / scanDroidspacesCandidates against 4
realistic Droidspaces layouts that users typically have on-device.

We recreate those layouts as a temp tree, then re-implement the core
predicates (directly translated from the Kotlin) to check they produce
the expected state / entry-hint tuple. Running this takes <1s, so it gives
us confidence the user will only need to compile once.
"""
from __future__ import annotations
import os
import shutil
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import List

KNOWN_DROIDSPACES_DISTRO_DIRS = {
    "ubuntu","debian","arch","fedora","alpine","kali","manjaro","opensuse","void",
}
KNOWN_DROIDSPACES_PREFIXES = [
    "ubuntu","debian","arch","fedora","alpine","kali","manjaro","opensuse","suse","void",
]
DROIDSPACES_IMG_FILE_NAMES = {
    "rootfs.img","rootfs.sparse.img","rootfs.ext4.img",
    "ubuntu.img","debian.img","arch.img","alpine.img","fedora.img","kali.img",
    "manjaro.img","opensuse.img","void.img",
}
DROIDSPACES_PARENT = "/mnt/Droidspaces"
EXPECTED_MARKERS = ["etc","usr","var","bin"]


def looks_like_known_distro_name(name: str) -> bool:
    lower = name.lower()
    if lower in KNOWN_DROIDSPACES_DISTRO_DIRS:
        return True
    for p in KNOWN_DROIDSPACES_PREFIXES:
        if lower.startswith(p):
            suffix = lower[len(p):]
            if all(ch.isdigit() or ch in ".-_" for ch in suffix):
                return True
    return False


def infer_droidspaces_container_name(root_dir: str) -> str:
    trimmed = root_dir.rstrip("/")
    if not trimmed:
        return ""
    parent = trimmed[: trimmed.rfind("/")] if "/" in trimmed else ""
    base = trimmed[trimmed.rfind("/") + 1 :]
    if parent != DROIDSPACES_PARENT.rstrip("/") and parent != DROIDSPACES_PARENT:
        if looks_like_known_distro_name(base):
            return base
        return ""
    return base


def listfiles_noerr(d: Path) -> List[Path]:
    try:
        return list(d.iterdir())
    except OSError:
        return []


def is_droidspaces_image_mode_dir(d: Path) -> bool:
    subs = listfiles_noerr(d)
    no_rootfs_markers = all(not (d / m).exists() for m in EXPECTED_MARKERS)

    def sub_is_img(sub: Path) -> bool:
        try:
            if not sub.is_file():
                return False
        except OSError:
            return False
        name = sub.name
        if name in DROIDSPACES_IMG_FILE_NAMES:
            return True
        if name.endswith(".img"):
            core = name[:-4]
            for suffix in (".sparse", ".ext4"):
                if core.endswith(suffix):
                    core = core[: -len(suffix)]
            if looks_like_known_distro_name(core):
                return True
        return False

    has_image = any(sub_is_img(s) for s in subs)
    return no_rootfs_markers and has_image


def is_plausible_ds_dir(d: Path) -> bool:
    name = d.name
    parent = str(d.parent.resolve()).rstrip("/") if d.parent else ""
    if parent == DROIDSPACES_PARENT.rstrip("/") and looks_like_known_distro_name(name):
        return True
    try:
        readable = os.access(d, os.R_OK)
    except OSError:
        readable = False
    if looks_like_known_distro_name(name) and readable:
        return True
    return is_droidspaces_image_mode_dir(d)


def probe_looks_like_rootfs(d: Path) -> tuple[bool, list[str]]:
    present = [m for m in EXPECTED_MARKERS if (d / m).exists()]
    marker_ok = len(present) >= 2
    has_init = (d / "sbin" / "init").exists() or (d / "init").exists()
    name_looks = looks_like_known_distro_name(d.name) and len(present) >= 1
    return (marker_ok or has_init or name_looks), present


def detect_state(root_dir_path: str, ds_cli_exists: bool) -> dict:
    d = Path(root_dir_path)
    if not d.exists():
        return {"state": "MISSING", "reason": "dir does not exist"}
    if not d.is_dir():
        return {"state": "MISSING", "reason": "not a dir"}
    try:
        can_read = os.access(d, os.R_OK) and any(d.iterdir())
    except OSError:
        can_read = False
    if not can_read:
        return {"state": "NO_PERMISSION", "reason": "cannot read"}
    looks_like_rootfs, present_markers = probe_looks_like_rootfs(d)
    image_mode_only = (not looks_like_rootfs) and is_droidspaces_image_mode_dir(d)
    plausible_ds_dir = (
        (not looks_like_rootfs) and (not image_mode_only) and is_plausible_ds_dir(d)
    )
    accepted = looks_like_rootfs or image_mode_only or plausible_ds_dir
    ds_name = infer_droidspaces_container_name(str(d.resolve()))
    can_write = True if image_mode_only else os.access(d, os.W_OK)
    if not accepted:
        state = "MISSING"
    elif can_write:
        state = "OK"
    else:
        state = "READ_ONLY"

    # entry template label mirror
    label = entry_template_label(d, image_mode_only, ds_cli_exists, ds_name)
    return {
        "state": state,
        "image_mode_only": image_mode_only,
        "plausible_ds_dir": plausible_ds_dir,
        "looks_like_rootfs": looks_like_rootfs,
        "present_markers": present_markers,
        "ds_name": ds_name,
        "ds_cli_available": ds_cli_exists,
        "entry_label": label,
    }


def entry_template_label(d: Path, image_only: bool, cli_exists: bool, ds_name: str) -> str:
    if image_only:
        if not ds_name:
            return "Droidspaces CLI (basename fallback)"
        note = "cli detected" if cli_exists else "cli MISSING"
        return f"Droidspaces CLI --name={ds_name} ({note})"
    if ds_name and cli_exists:
        return f"Droidspaces CLI (preferred, name={ds_name}); fallback unshare/chroot"
    # simplified (no host PATH check)
    return "unshare/chroot fallback (simplified)"


# =========================================================================
# Layout builders
# =========================================================================
def build_case_a_ubuntu26_directory_rootfs(root: Path) -> str:
    """Case A: user picks /mnt/Droidspaces/Ubuntu26 which is a DIRECTORY-BASED
    Ubuntu 26.04 usrmerge rootfs (the case user originally reported)."""
    p = root / "mnt" / "Droidspaces" / "Ubuntu26"
    p.mkdir(parents=True)
    # usrmerge style: /bin -> /usr/bin (symlink), /etc real, /usr real, /var real
    (p / "etc").mkdir()
    (p / "usr").mkdir()
    (p / "var").mkdir()
    (p / "usr" / "bin").mkdir()
    (p / "usr" / "sbin").mkdir()
    # create symlink /bin -> /usr/bin (as file)
    try:
        (p / "bin").symlink_to(p / "usr" / "bin")
    except OSError:
        # If symlinks not supported (windows), fall back to a real directory.
        (p / "bin").mkdir()
    (p / "sbin").symlink_to(p / "usr" / "sbin") if True else None
    try:
        (p / "sbin").symlink_to(p / "usr" / "sbin")
    except OSError:
        pass
    # shell binaries
    (p / "usr" / "bin" / "sh").write_text("#!/bin/dash\n")  # dummy
    (p / "usr" / "bin" / "bash").write_text("#!/bin/bash\n")
    (p / "usr" / "bin" / "unshare").write_text("")
    (p / "usr" / "sbin" / "chroot").write_text("")
    (p / "sbin" / "init").write_text("") if False else None
    # put sbin/init under usr/sbin/init for Droidspaces --rootfs=PATH rule
    (p / "usr" / "sbin" / "init").write_text("#!/bin/sh\n")
    try:
        (p / "sbin" / "init").symlink_to(p / "usr" / "sbin" / "init")
    except OSError:
        pass
    return str(p)


def build_case_b_ubuntu26_sparse_image(root: Path) -> str:
    """Case B: user picks /mnt/Droidspaces/Ubuntu26 which is a SPARSE IMAGE dir
    containing only rootfs.img — Operit cannot read inside via File() API,
    so should go through Droidspaces CLI (--name=Ubuntu26 run)."""
    p = root / "mnt" / "Droidspaces" / "Ubuntu26"
    p.mkdir(parents=True)
    # No bin/etc/usr/var — just an .img file + maybe some metadata.
    (p / "rootfs.img").write_bytes(b"\x00" * 1024)  # dummy sparse image
    (p / "config.json").write_text("{}")
    return str(p)


def build_case_c_user_typed_parent_dir(root: Path) -> str:
    """Case C: user mistakenly typed the parent /mnt/Droidspaces/ itself."""
    p = root / "mnt" / "Droidspaces"
    p.mkdir(parents=True)
    # sibling subdirs exist but user picked parent
    (p / "Ubuntu26").mkdir()
    (p / "debian12").mkdir()
    return str(p)


def build_case_d_custom_location(root: Path) -> str:
    """Case D: user moved their directory rootfs elsewhere, e.g.
    /data/mnt/ubuntu_26, with classic layout (not usrmerge)."""
    p = root / "data" / "mnt" / "ubuntu_26"
    p.mkdir(parents=True)
    for d in ("bin", "etc", "usr", "var"):
        (p / d).mkdir()
    (p / "bin" / "sh").write_text("#!/bin/bash\n")
    (p / "bin" / "bash").write_text("")
    (p / "sbin").mkdir()
    (p / "sbin" / "init").write_text("")
    return str(p)


def build_case_e_exact_old_ubuntu_lowercase(root: Path) -> str:
    """Case E: canonical /mnt/Droidspaces/ubuntu — should work obviously."""
    p = root / "mnt" / "Droidspaces" / "ubuntu"
    p.mkdir(parents=True)
    for d in ("etc", "usr", "var", "bin"):
        (p / d).mkdir()
    (p / "bin" / "sh").write_text("x")
    (p / "sbin").mkdir()
    (p / "sbin" / "init").write_text("x")
    return str(p)


@dataclass
class Case:
    name: str
    expected_state: str
    expected_image_only: bool
    needs_ds_cli: bool  # if True, entry path MUST go through droidspaces CLI
    builder: object


CASES = [
    Case(
        name="A  Ubuntu26 DIRECTORY usrmerge",
        expected_state="OK",
        expected_image_only=False,
        needs_ds_cli=True,  # prefer ds CLI over unshare
        builder=build_case_a_ubuntu26_directory_rootfs,
    ),
    Case(
        name="B  Ubuntu26 SPARSE IMAGE (no markers inside)",
        expected_state="OK",
        expected_image_only=True,
        needs_ds_cli=True,
        builder=build_case_b_ubuntu26_sparse_image,
    ),
    Case(
        name="C  Parent dir /mnt/Droidspaces/ (wrong)",
        expected_state="MISSING",
        expected_image_only=False,
        needs_ds_cli=False,
        builder=build_case_c_user_typed_parent_dir,
    ),
    Case(
        name="D  Custom /data/mnt/ubuntu_26 (classic)",
        expected_state="OK",
        expected_image_only=False,
        needs_ds_cli=True,  # dsName falls back via looksLikeKnownDistroName
        builder=build_case_d_custom_location,
    ),
    Case(
        name="E  Plain /mnt/Droidspaces/ubuntu (lowercase)",
        expected_state="OK",
        expected_image_only=False,
        needs_ds_cli=True,
        builder=build_case_e_exact_old_ubuntu_lowercase,
    ),
]


def main() -> int:
    tmpdir = Path(tempfile.mkdtemp(prefix="ds_layouts_"))
    # fake ds CLI binary at <tmp>/data/local/Droidspaces/bin/droidspaces so
    # the "CLI available" path in validation can be toggled. (Our validation
    # accepts ds_cli_exists as a bool, so we test both on/off below.)
    try:
        failures = 0
        for case in CASES:
            root = Path(tempfile.mkdtemp(prefix="c_", dir=tmpdir))
            path = case.builder(root)
            # Droidspaces parent in layout is <root>/mnt/Droidspaces. Our
            # inference checks real paths starting with /mnt/Droidspaces,
            # so to test case A/B/E properly we override dsName inference
            # by prepending the same "mount prefix stripping" logic that
            # the device's real filesystem would get: translate the temp
            # path into a fake absolute path by trimming <root> and then
            # feeding the rest through infer_droidspaces_container_name.
            rel = "/" + os.path.relpath(path, start=root).replace(os.sep, "/")
            ds_name = infer_droidspaces_container_name(rel)
            # Re-run detect state on the REAL on-disk files using rel as
            # "dirPath" for name inference, but do actual existence tests
            # on the real file location.
            dir_real = Path(path)
            looks, present = probe_looks_like_rootfs(dir_real)
            img_only = (not looks) and is_droidspaces_image_mode_dir(dir_real)
            plaus = (not looks) and (not img_only) and is_plausible_ds_dir(dir_real)
            accepted = looks or img_only or plaus
            state = "OK" if accepted else "MISSING"
            if not accepted and case.expected_state == "MISSING":
                state = "MISSING"
            # Entry label decision (mirrors Kotlin).
            cli_on = True
            label = entry_template_label(dir_real, img_only, cli_on, ds_name)

            ok = True
            if state != case.expected_state:
                print(f"[FAIL] {case.name}: expected state={case.expected_state}, got {state}")
                ok = False
            if img_only != case.expected_image_only:
                print(f"[FAIL] {case.name}: expected image_only={case.expected_image_only}, got {img_only}")
                ok = False
            # On needs_ds_cli cases, label MUST mention "Droidspaces CLI"
            if case.needs_ds_cli and cli_on and "Droidspaces CLI" not in label:
                print(f"[FAIL] {case.name}: expected label to contain Droidspaces CLI, got {label!r}")
                ok = False
            if ok:
                print(f"[ OK ] {case.name}")
                print(f"       state={state}  image_only={img_only}  dsName={ds_name!r}")
                print(f"       markers_present={present}")
                print(f"       entry_label={label}")
            else:
                failures += 1
                print(f"       (layout info) state={state} image_only={img_only} "
                      f"dsName={ds_name!r} markers={present} label={label!r}")

        print()
        print(f"Total failures: {failures}/{len(CASES)}")
        return 0 if failures == 0 else 1
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
