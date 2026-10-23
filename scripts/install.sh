#!/usr/bin/env bash
# Instala o HY Home no projetor e o define como tela inicial.
# Uso:  scripts/install.sh <ip:porta>          instala e ativa
#       scripts/install.sh <ip:porta> --rollback   volta ao launcher anterior
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # Git Bash no Windows não pode converter /data/... em C:/...

DEV="${1:?informe o dispositivo, ex.: 192.168.0.15:5555}"
A="adb -s $DEV"
PKG=com.paulo.hyhome

if [ "${2:-}" = "--rollback" ]; then
    # Reativa o JmLauncher (atende a tecla HOME.JM) e devolve o papel de HOME ao FLauncher.
    $A shell pm enable com.jm.jmlauncher
    $A shell cmd role add-role-holder android.app.role.HOME me.efesser.flauncher
    $A uninstall "$PKG"
    exit 0
fi

[ -f build/hyhome.apk ] || bash build.sh
$A install -r build/hyhome.apk
$A shell cmd role add-role-holder android.app.role.HOME "$PKG"
# Sem isso, dois apps atendem HOME.JM e a tecla HOME abre um seletor.
$A shell pm disable-user --user 0 com.jm.jmlauncher
$A shell am start -n "$PKG/.MainActivity"
