# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['pc_v2\\plugins\\system_stats\\sensor_helper.py'],
    pathex=[],
    binaries=[],
    datas=[('pc_v2/plugins/system_stats/bin/LibreHardwareMonitorLib.dll', 'bin')],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='MonitHomeHelper',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    version='pc_v2\\plugins\\system_stats\\version_info.txt',
    uac_admin=True,
    icon=['icons\\pc_icon.ico'],
)
