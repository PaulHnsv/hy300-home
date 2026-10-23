#!/usr/bin/env bash
# Verifica, SÓ EM LEITURA, se a fase 1 está ativa no HY300-T. Não altera nada no aparelho.
#
# Uso:  scripts/phase1/verify.sh [ip:porta]
# Saída: OK / FALHA / AVISO por item. Código de saída: 0 = tudo certo, 1 = alguma FALHA, 2 = sem conexão.
set -uo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib.sh
source ./lib.sh
[ $# -ge 1 ] && ADB_DEV="$1"

FAIL=0 WARN=0
ok()   { printf '  OK     %s\n' "$*"; }
fail() { printf '  FALHA  %s\n' "$*"; FAIL=$((FAIL+1)); }
warn() { printf '  AVISO  %s\n' "$*"; WARN=$((WARN+1)); }

adbd get-state >/dev/null 2>&1 || { echo "aparelho não encontrado ($ADB_DEV). Rode: adb connect $ADB_DEV"; exit 2; }
echo "== Verificação da fase 1 ($ADB_DEV) =="

exists() { [ "$(adbsh "[ -e '$1' ] && echo s || echo n")" = "s" ]; }
dsha()   { adbd exec-out "cat '$1'" | sha; }

echo "[aparelho]"
plat="$(adbsh 'getprop ro.board.platform')"; soc="$(adbsh 'getprop ro.soc.model')"
if [ "$plat" = "txhd2" ] && [ "$soc" = "t950s_be311" ]; then ok "HY300-T ($plat / $soc)"; else warn "aparelho diferente do esperado ($plat / $soc)"; fi
[ "$(adbsh 'id -u')" = "0" ] && ok "ADB como root" || warn "ADB sem root: algumas leituras podem falhar"

echo "[ADB exige autorização]"
[ "$(adbsh 'getprop ro.adb.secure')" = "1" ] && ok "ro.adb.secure=1 em vigor" || fail "ro.adb.secure não é 1 (falta reiniciar depois de reapply.sh?)"
adbd exec-out "grep -c '^ro.adb.secure=1' /system/build.prop" | tr -d '\r' | grep -qx '[1-9][0-9]*' && ok "/system/build.prop tem ro.adb.secure=1" || fail "/system/build.prop sem ro.adb.secure=1"
nk="$(adbsh 'grep -c . /data/misc/adb/adb_keys 2>/dev/null || true')"
nk="${nk:-0}"
[ "${nk:-0}" -ge 1 ] 2>/dev/null && ok "adb_keys tem $nk chave(s) autorizada(s)" || fail "adb_keys vazio: ninguém consegue autorizar o ADB"

echo "[scripts do fabricante neutralizados]"
for f in /system/etc/ttyunos.sh /system/etc/v9ying.sh /system/etc/custom.sh /data/ttyunos/ttyunos.sh /data/ttyunos/v9ying.sh /data/ttyunos/custom.sh; do
    if ! exists "$f"; then
        case "$f" in /data/*) ok "$f não existe (nada a executar)";; *) warn "$f não existe";; esac
        continue
    fi
    h="$(dsha "$f")"
    if [ "$h" = "$SHA_NOOP_SH" ]; then ok "$f é o no-op"
    elif [ "$h" = "$SHA_ORIG_TTYUNOS_SH" ] || [ "$h" = "$SHA_ORIG_V9YING_SH" ] || [ "$h" = "$SHA_ORIG_CUSTOM_SH" ]; then fail "$f é o ORIGINAL do fabricante (baixa e executa como root)"
    else warn "$f tem conteúdo desconhecido"; fi
done
if exists /data/ttyunos; then
    perm="$(adbsh "stat -c '%a %u' /data/ttyunos")"
    [ "$perm" = "755 0" ] && ok "/data/ttyunos é 755 root" || fail "/data/ttyunos com permissão $perm (esperado 755 root; 777 deixa qualquer app gravar)"
fi

echo "[gatilhos de boot]"
active() { adbd exec-out "grep -vE '^[[:space:]]*#|^[[:space:]]*\$' '$1' 2>/dev/null" | tr -d '\r'; }
if exists /system/etc/init/ttyunos.rc; then
    n="$(active /system/etc/init/ttyunos.rc | wc -l | tr -d ' ')"
    [ "$n" = 0 ] && ok "ttyunos.rc inteiro comentado" || fail "ttyunos.rc tem $n linha(s) ativa(s)"
else warn "ttyunos.rc não existe"; fi
if exists /system/etc/init/preinstall.rc; then
    bad="$(active /system/etc/init/preinstall.rc | grep -cE 'start (update_apps|activation|upgrade|installxapk|agent)|vendor\.jm\.(usb\.upgrade|apk\.install)|vendor\.sys\.disable\.apps|persist\.sys\.preinstall\.active' | tr -d ' ')"
    [ "${bad:-0}" = 0 ] && ok "preinstall.rc sem os gatilhos de USB, xapk, activation, update_apps e agent" || fail "preinstall.rc com $bad gatilho(s) ativo(s)"
fi
svcbad=0
for svc in ttyunos v9ying custom copytty upgrade installxapk agent activation update_apps; do
    s="$(adbsh "getprop init.svc.$svc")"
    case "$s" in ""|stopped) : ;; *) fail "serviço $svc está '$s'"; svcbad=1 ;; esac
done
[ "$svcbad" = 0 ] && ok "nenhum serviço do fabricante em execução"
payloads="$(adbsh 'ls /data/install-end.sh /data/install-host.sh /data/install-recovery.sh 2>/dev/null | wc -l' | tr -d ' ')"
[ "$payloads" = 0 ] && ok "sem payloads /data/install-*.sh" || fail "$payloads payload(s) /data/install-*.sh presente(s)"

echo "[bloqueio de domínios em /etc/hosts]"
hosts="$(adbd exec-out 'cat /system/etc/hosts' | tr -d '\r')"; miss=0
for d in "${PHASE1_DOMAINS[@]}"; do printf '%s\n' "$hosts" | grep -qE "^0\.0\.0\.0[[:space:]]+${d//./\\.}\$" || { fail "falta $d no hosts"; miss=1; }; done
[ "$miss" = 0 ] && ok "${#PHASE1_DOMAINS[@]} domínios bloqueados"

echo "[informativo]"
UPPER="$(adbsh 'mount' | sed -n 's#^overlay on /system type overlay .*upperdir=\([^,]*\),.*#\1#p' | head -1)"
if [ -n "$UPPER" ]; then
    echo "  INFO   as mudanças de /system estão no overlay $UPPER (dentro do userdata): um reset de fábrica as apaga; rode reapply.sh de novo."
else echo "  INFO   /system sem overlay: as mudanças estariam na própria partição."; fi
echo "  INFO   vendor/product têm 3 arquivos no overlay de 18/04/2026 (personalização remota do fabricante). Não fazem parte desta fase."

echo
if [ "$FAIL" = 0 ]; then echo "Resultado: tudo certo ($WARN aviso(s))."; exit 0; else echo "Resultado: $FAIL falha(s), $WARN aviso(s)."; exit 1; fi
