#!/usr/bin/env bash
# Autoteste OFFLINE: não usa o aparelho. Confere que as transformações de lib.sh, aplicadas aos arquivos
# originais do firmware, geram exatamente os resultados esperados (SHA-256).
#
# Uso:  scripts/phase1/selftest.sh <pasta com os originais>
#   A pasta é a "fase1-originais" do seu backup (system__etc__hosts, system__build.prop, ...).
set -uo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib.sh
source ./lib.sh

O="${1:?informe a pasta com os arquivos originais (fase1-originais do backup)}"
FAIL=0
chk() { # $1 = nome, $2 = sha obtido, $3 = sha esperado
    if [ "$2" = "$3" ]; then printf '  OK     %s\n' "$1"; else printf '  FALHA  %s\n' "$1"; FAIL=1; fi
}
for f in system__etc__init__ttyunos.rc system__etc__init__preinstall.rc system__etc__hosts system__build.prop \
         system__etc__ttyunos.sh system__etc__v9ying.sh system__etc__custom.sh; do
    [ -f "$O/$f" ] || { echo "falta $O/$f"; exit 2; }
done
chk "original ttyunos.rc"    "$(sha < "$O/system__etc__init__ttyunos.rc")"    "$SHA_ORIG_TTYUNOS_RC"
chk "original preinstall.rc" "$(sha < "$O/system__etc__init__preinstall.rc")" "$SHA_ORIG_PREINSTALL_RC"
chk "original hosts"         "$(sha < "$O/system__etc__hosts")"               "$SHA_ORIG_HOSTS"
chk "original build.prop"    "$(sha < "$O/system__build.prop")"               "$SHA_ORIG_BUILD_PROP"
chk "original ttyunos.sh"    "$(sha < "$O/system__etc__ttyunos.sh")"          "$SHA_ORIG_TTYUNOS_SH"
chk "original v9ying.sh"     "$(sha < "$O/system__etc__v9ying.sh")"           "$SHA_ORIG_V9YING_SH"
chk "original custom.sh"     "$(sha < "$O/system__etc__custom.sh")"           "$SHA_ORIG_CUSTOM_SH"
chk "ttyunos.rc gerado"      "$(make_ttyunos_rc    < "$O/system__etc__init__ttyunos.rc"    | sha)" "$SHA_NEW_TTYUNOS_RC"
chk "preinstall.rc gerado"   "$(make_preinstall_rc < "$O/system__etc__init__preinstall.rc" | sha)" "$SHA_NEW_PREINSTALL_RC"
chk "hosts gerado"           "$(make_hosts         < "$O/system__etc__hosts"               | sha)" "$SHA_NEW_HOSTS"
chk "build.prop gerado"      "$(make_build_prop    < "$O/system__build.prop"               | sha)" "$SHA_NEW_BUILD_PROP"
chk "no-op gerado"           "$(make_noop_sh | sha)"                                              "$SHA_NOOP_SH"
[ "$FAIL" = 0 ] && echo "Autoteste: tudo certo." || { echo "Autoteste: falhou."; exit 1; }
