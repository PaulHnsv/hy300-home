#!/usr/bin/env bash
# Funções e constantes comuns da fase 1 (reaplicação e verificação). Para ser lido com `source`.
#
# O que a fase 1 faz no HY300-T (detalhes em docs/ENGENHARIA-REVERSA.md, seção 4):
#   - neutraliza os gatilhos de boot do fabricante que baixavam e executavam scripts como root;
#   - bloqueia domínios do fabricante, de anúncios e de analytics no /etc/hosts;
#   - exige autorização do PC para o ADB (ro.adb.secure=1).
#
# As transformações abaixo partem do arquivo ORIGINAL do firmware e produzem o arquivo modificado.
# Cada uma só roda se o SHA-256 do arquivo atual for o do original (ou já o do resultado), então um
# firmware diferente (por exemplo depois de uma OTA) nunca é sobrescrito sem aviso.

PHASE1_DOMAINS=(
    v7ying.com jm.v7ying.com v8ying.com apk.v8ying.com v9ying.com jm.v9ying.com
    jiumaoos.com txy.jiumaoos.com ajpmk.jiumaoos.com api.360mads.com
    youtube.ad.akrdinfo.cn errlog.umeng.com errlogos.umeng.com android.bugly.qq.com
)

# SHA-256 dos originais do firmware (HY300-T, build de 2025-07-24) e dos resultados esperados.
SHA_ORIG_TTYUNOS_RC=539419de3e65005906064da6d6fe3752e0431148dde1d2ddd492c124a98b3413
SHA_NEW_TTYUNOS_RC=52cff90b06b94aaaae3cf56662f01f1346e754ac51d463beb0d9c7f6a7926e8b
SHA_ORIG_PREINSTALL_RC=17e1c8996796a0216a7dfa9e5bd120d98db9ad6e11c0d9eab897ba64ba1c34e7
SHA_NEW_PREINSTALL_RC=b4033d33925e7536e66e4bceb41bfe7704ffcd959040d62cdf6aa296949805e3
SHA_ORIG_HOSTS=425c3e713d5bae19b031bc8639c20c6a23e311a54647ba1824cbf45969a11ff4
SHA_NEW_HOSTS=51c9776aff8db79b4a8e5eff725e7c5aa45722e67209f04ec1f3a2079917cc5f
SHA_ORIG_BUILD_PROP=1591fbb90fbd5cb5a0c889ddee49725189e61bcba5a2b456fa3f7535ea9109e1
SHA_NEW_BUILD_PROP=97956b461fcef3625e86f1818c17d7a26817df90252eb5b45e1616ea1e37cceb
SHA_NOOP_SH=10e2d59fb4138bc8d69a436b8f27c752d85cc24cb3de23da8392f9cff129759b
# Os três scripts originais do fabricante (os mesmos em /system/etc e em /data/ttyunos).
SHA_ORIG_TTYUNOS_SH=799e0defdce44f02149ef964494d2be834d147cb3a78fa47e6e577eec56edce1
SHA_ORIG_V9YING_SH=7aa92a9b9a421957325f9cb7b5c7c1ed210bb231c166001bccddcc4892ea5383
SHA_ORIG_CUSTOM_SH=e52f9dc038b764748a6cd95bcd3f7977df1ab820bb6f6fffb2e746b22ed3e21c

sha() { sha256sum | cut -d' ' -f1; }

# stdin = original, stdout = modificado
make_ttyunos_rc() {
    printf '%s\n' '# Desativado em 2026-10-22 (executava scripts baixados da internet como root)'
    # awk garante a quebra de linha final, que o original não tem. As quebras CRLF do original ficam.
    awk '{ if ($0 != "") print "#" $0; else print $0 }'
}

make_preinstall_rc() {
    sed -e '17,18s/^/#DESATIVADO# /' -e '20,21s/^/#DESATIVADO# /' -e '23,24s/^/#DESATIVADO# /' \
        -e '26,27s/^/#DESATIVADO# /' -e '31s/^/#DESATIVADO# /'
}

make_hosts() {
    cat
    printf '\n# Bloqueios adicionados em 2026-10-22\n'
    local d
    for d in "${PHASE1_DOMAINS[@]}"; do printf '0.0.0.0 %s\n' "$d"; done
}

make_build_prop() {
    cat
    printf '\n# Exige autorização do PC para ADB (2026-10-22)\nro.adb.secure=1\n'
}

make_noop_sh() {
    printf '#!/system/bin/sh\n# Neutralizado em 2026-10-22. Original em HY300-T-backup/fase1-originais\nexit 0\n'
}

# adb com o dispositivo escolhido. MSYS_NO_PATHCONV impede o Git Bash de converter /data/... em C:/...
export MSYS_NO_PATHCONV=1
ADB_DEV="${ADB_DEV:-192.168.0.15:5555}"
adbd() { adb -s "$ADB_DEV" "$@"; }
adbsh() { adb -s "$ADB_DEV" shell "$@" | tr -d '\r'; }
