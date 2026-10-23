#!/usr/bin/env bash
# Reaplica a fase 1 no HY300-T depois de um reset de fábrica ou de uma OTA que desfaça o overlay.
#
# Uso:  scripts/phase1/reapply.sh [ip:porta]            só mostra o que faria (padrão, não altera nada)
#       scripts/phase1/reapply.sh --apply [ip:porta]    aplica
#       --reboot                                        reinicia o projetor ao final (com --apply)
#
# Depois de aplicar, reinicie o projetor e rode scripts/phase1/verify.sh.
#
# Antes de qualquer escrita o script confere TUDO em modo leitura e aborta sem mudar nada se:
#   - o aparelho não for o HY300-T (txhd2 / t950s_be311) ou o ADB não for root;
#   - o /system não tiver overlay (não há onde gravar);
#   - algum arquivo não tiver o SHA-256 do original nem o do resultado esperado (firmware diferente);
#   - a chave ADB deste PC (~/.android/adbkey.pub) não existir. Sem ela, ro.adb.secure=1 trancaria você fora.
#
# NÃO reaplica os arquivos de vendor/product do overlay (build.prop, media_codecs_amlogic_audio.xml):
# eles são de 18/04/2026, da personalização remota do fabricante, e não são mudanças desta fase.
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib.sh
source ./lib.sh

APPLY=0 REBOOT=0
for a in "$@"; do
    case "$a" in
        --apply) APPLY=1 ;;
        --reboot) REBOOT=1 ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        *) ADB_DEV="$a" ;;
    esac
done

say() { printf '%s\n' "$*"; }
die() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }
# Executa a ação só com --apply; sem ele, apenas descreve.
act() { if [ "$APPLY" = 1 ]; then "$@"; else say "  [simulação] $*"; fi; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# ------------------------------------------------------------------ 1. conferências (só leitura)
say "== Conferindo o aparelho ($ADB_DEV) =="
adbd get-state >/dev/null 2>&1 || die "aparelho não encontrado. Rode: adb connect $ADB_DEV"
[ "$(adbsh 'id -u')" = "0" ] || die "o ADB não está como root"
plat="$(adbsh 'getprop ro.board.platform')"; soc="$(adbsh 'getprop ro.soc.model')"
[ "$plat" = "txhd2" ] && [ "$soc" = "t950s_be311" ] || die "aparelho inesperado ($plat / $soc). Este script é só para o HY300-T."

UPPER="$(adbsh 'mount' | sed -n 's#^overlay on /system type overlay .*upperdir=\([^,]*\),.*#\1#p' | head -1)"
[ -n "$UPPER" ] || die "o /system não tem overlay montado, não sei onde gravar. Tente 'adb root && adb remount' e rode de novo."
say "overlay de /system: $UPPER"

dev_exists() { [ "$(adbsh "[ -e '$1' ] && echo s || echo n")" = "s" ]; }
dev_sha() { adbd exec-out "cat '$1'" | sha; }
# Estado de um arquivo no aparelho: orig | new | missing | unknown
state() {
    dev_exists "$1" || { echo missing; return; }
    local h; h="$(dev_sha "$1")"
    if [ "$h" = "$2" ]; then echo orig; elif [ "$h" = "$3" ]; then echo new; else echo unknown; fi
}

# nome-do-passo | caminho no aparelho | sha original | sha resultado
declare -A ST
check() {
    ST["$1"]="$(state "$2" "$3" "$4")"
    printf '  %-26s %s\n' "$1" "${ST[$1]}"
    [ "${ST[$1]}" != unknown ] || die "$2 não é nem o original nem o resultado esperado. Firmware diferente? Nada foi alterado."
}
check ttyunos.rc   /system/etc/init/ttyunos.rc   "$SHA_ORIG_TTYUNOS_RC"    "$SHA_NEW_TTYUNOS_RC"
check preinstall.rc /system/etc/init/preinstall.rc "$SHA_ORIG_PREINSTALL_RC" "$SHA_NEW_PREINSTALL_RC"
check hosts        /system/etc/hosts            "$SHA_ORIG_HOSTS"          "$SHA_NEW_HOSTS"
check build.prop   /system/build.prop           "$SHA_ORIG_BUILD_PROP"     "$SHA_NEW_BUILD_PROP"
check etc/ttyunos.sh /system/etc/ttyunos.sh     "$SHA_ORIG_TTYUNOS_SH"     "$SHA_NOOP_SH"
check etc/v9ying.sh  /system/etc/v9ying.sh      "$SHA_ORIG_V9YING_SH"      "$SHA_NOOP_SH"
check etc/custom.sh  /system/etc/custom.sh      "$SHA_ORIG_CUSTOM_SH"      "$SHA_NOOP_SH"
for n in ttyunos v9ying custom; do
    case $n in ttyunos) o=$SHA_ORIG_TTYUNOS_SH;; v9ying) o=$SHA_ORIG_V9YING_SH;; custom) o=$SHA_ORIG_CUSTOM_SH;; esac
    check "data/ttyunos/$n.sh" "/data/ttyunos/$n.sh" "$o" "$SHA_NOOP_SH"
done
# Estes quatro precisam existir: o resultado é calculado a partir do conteúdo atual. Se faltarem (por
# exemplo um hosts vazio), gerar o resultado apagaria o conteúdo essencial. Os .sh podem faltar.
for k in ttyunos.rc preinstall.rc hosts build.prop; do
    [ "${ST[$k]}" != missing ] || die "$k não existe no aparelho. Firmware diferente? Nada foi alterado."
done

PUB="$HOME/.android/adbkey.pub"
[ -f "$PUB" ] || die "falta $PUB (a chave ADB deste PC). Sem ela, ro.adb.secure=1 trancaria o ADB. Nada foi alterado."
KEYTOK="$(awk '{print $1; exit}' "$PUB")"
HAS_KEY="$(adbsh "grep -cF '$KEYTOK' /data/misc/adb/adb_keys 2>/dev/null || true")"
say "  chave ADB deste PC         $([ "${HAS_KEY:-0}" != 0 ] && echo 'já autorizada' || echo 'será autorizada')"

# ------------------------------------------------------------------ 2. ações
say
say "== $([ "$APPLY" = 1 ] && echo Aplicando || echo 'Simulação (nada será alterado)') =="

# 2.1 chave do PC: tem de vir ANTES de ro.adb.secure=1 entrar em vigor.
if [ "${HAS_KEY:-0}" = 0 ]; then
    say "- autorizando a chave ADB deste PC"
    cp "$PUB" "$TMP/key"; printf '\n' >> "$TMP/key"
    act adbd push "$TMP/key" /data/local/tmp/hy_adbkey >/dev/null
    # adb_keys é 640 system:shell. Se não existir (depois de um reset), cria com o mesmo dono e modo.
    act adbsh "[ -e /data/misc/adb/adb_keys ] || { mkdir -p /data/misc/adb; : > /data/misc/adb/adb_keys; chmod 640 /data/misc/adb/adb_keys; chown system:shell /data/misc/adb/adb_keys; }; cat /data/local/tmp/hy_adbkey >> /data/misc/adb/adb_keys && rm -f /data/local/tmp/hy_adbkey"
fi

# 2.2 grava um arquivo no overlay de /system (modo 644, dono root). $1 = caminho relativo, $2 = arquivo local.
put_overlay() {
    local rel="$1" src="$2" dir; dir="$(dirname "$UPPER/$rel")"
    act adbd push "$src" /data/local/tmp/hy_stage >/dev/null
    act adbsh "mkdir -p '$dir' && cp /data/local/tmp/hy_stage '$UPPER/$rel' && chmod 644 '$UPPER/$rel' && chown 0:0 '$UPPER/$rel' && rm -f /data/local/tmp/hy_stage"
}

# 2.3 os arquivos de /system, a partir do conteúdo atual (conferido por hash acima)
transform() { # $1 = passo, $2 = caminho no aparelho, $3 = função, $4 = destino relativo
    [ "${ST[$1]}" = new ] && { say "- $1: já aplicado, pulando"; return; }
    say "- $1"
    adbd exec-out "cat '$2'" > "$TMP/in"
    "$3" < "$TMP/in" > "$TMP/out"
    put_overlay "$4" "$TMP/out"
}
transform ttyunos.rc    /system/etc/init/ttyunos.rc    make_ttyunos_rc    etc/init/ttyunos.rc
transform preinstall.rc /system/etc/init/preinstall.rc make_preinstall_rc etc/init/preinstall.rc
transform hosts         /system/etc/hosts              make_hosts         etc/hosts
make_noop_sh > "$TMP/noop"
for n in ttyunos v9ying custom; do
    case "${ST[etc/$n.sh]}" in
        new) say "- etc/$n.sh: já aplicado, pulando" ;;
        missing) say "- etc/$n.sh: não existe neste firmware, pulando" ;;
        *) say "- etc/$n.sh"; put_overlay "etc/$n.sh" "$TMP/noop" ;;
    esac
done

# 2.4 os scripts em /data/ttyunos e os payloads baixados (só se existirem)
for n in ttyunos v9ying custom; do
    case "${ST[data/ttyunos/$n.sh]}" in
        orig) say "- /data/ttyunos/$n.sh"; act adbd push "$TMP/noop" /data/local/tmp/hy_noop >/dev/null
              act adbsh "cp /data/local/tmp/hy_noop /data/ttyunos/$n.sh && chmod 644 /data/ttyunos/$n.sh && chown 0:0 /data/ttyunos/$n.sh && rm -f /data/local/tmp/hy_noop" ;;
        *) : ;;
    esac
done
if dev_exists /data/ttyunos; then
    say "- /data/ttyunos: permissão 755, dono root"; act adbsh "chmod 755 /data/ttyunos && chown 0:0 /data/ttyunos"
fi
for p in install-end.sh install-host.sh install-recovery.sh; do
    if dev_exists "/data/$p"; then say "- removendo payload /data/$p (cópia em HY300-T-backup/fase1-originais)"; act adbsh "rm -f /data/$p"; fi
done

# 2.5 build.prop por último: só depois de a chave do PC estar em adb_keys.
transform build.prop /system/build.prop make_build_prop build.prop

# ------------------------------------------------------------------ 3. conferência do que foi gravado
if [ "$APPLY" = 1 ]; then
    say; say "== Conferindo o que foi gravado =="
    bad=0
    chkup() { # $1 = rel, $2 = sha esperado
        local h; h="$(adbd exec-out "cat '$UPPER/$1' 2>/dev/null" | sha)"
        if [ "$h" = "$2" ]; then printf '  OK    %s\n' "$1"; else printf '  FALHA %s\n' "$1"; bad=1; fi
    }
    chkup etc/init/ttyunos.rc "$SHA_NEW_TTYUNOS_RC"; chkup etc/init/preinstall.rc "$SHA_NEW_PREINSTALL_RC"
    chkup etc/hosts "$SHA_NEW_HOSTS"; chkup build.prop "$SHA_NEW_BUILD_PROP"
    for n in ttyunos v9ying custom; do
        [ "${ST[etc/$n.sh]}" = missing ] || chkup "etc/$n.sh" "$SHA_NOOP_SH"
    done
    [ "$bad" = 0 ] || die "alguma gravação não confere. Não reinicie; revise e rode de novo."
    say
    say "Pronto. As mudanças só entram em vigor depois de reiniciar o projetor (o overlay é lido no boot)."
    if [ "$REBOOT" = 1 ]; then say "Reiniciando…"; adbd reboot; else say "Reinicie com: adb -s $ADB_DEV reboot   e depois rode scripts/phase1/verify.sh"; fi
else
    say; say "Simulação concluída. Para aplicar: scripts/phase1/reapply.sh --apply"
fi
