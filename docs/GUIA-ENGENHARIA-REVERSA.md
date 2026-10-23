# Guia: como analisar e melhorar o seu próprio projetor Android

Este guia descreve o método que usamos no HY300-T (Amlogic T950S, Android 13) e como você pode repeti-lo no
seu projetor. Os **resultados** do nosso aparelho estão em [ENGENHARIA-REVERSA.md](ENGENHARIA-REVERSA.md);
aqui está o **como**.

> **Só no seu equipamento.** Alterar sistema, partições ou apps pode deixar o aparelho sem boot e pode
> anular a garantia. Tudo abaixo foi feito em **um** aparelho. Em outro lote, outra placa ou outro
> firmware, os resultados podem ser diferentes. Itens marcados como *não testado* não foram exercitados.

## Sumário

1. [Princípios](#1-princípios)
2. [O que você precisa](#2-o-que-você-precisa)
3. [Fase 0: acesso por ADB](#3-fase-0-acesso-por-adb)
4. [Fase 1: descobrir o hardware real](#4-fase-1-descobrir-o-hardware-real)
5. [Fase 2: backup completo e verificado](#5-fase-2-backup-completo-e-verificado)
6. [Fase 3: inventário de software e gatilhos de boot](#6-fase-3-inventário-de-software-e-gatilhos-de-boot)
7. [Fase 4: o que o aparelho faz na rede](#7-fase-4-o-que-o-aparelho-faz-na-rede)
8. [Fase 5: analisar um APK](#8-fase-5-analisar-um-apk)
9. [Fase 6: mudanças reversíveis](#9-fase-6-mudanças-reversíveis)
10. [Fase 7: launcher próprio e controle remoto](#10-fase-7-launcher-próprio-e-controle-remoto)
11. [Verificar, desfazer e sobreviver a um reset](#11-verificar-desfazer-e-sobreviver-a-um-reset)
12. [Adaptar a outro projetor](#12-adaptar-a-outro-projetor)
13. [O que não fazer](#13-o-que-não-fazer)
14. [Usando o REA (opcional)](#14-usando-o-rea-opcional)
15. [Problemas comuns](#15-problemas-comuns)
16. [Compartilhar sem expor seus dados](#16-compartilhar-sem-expor-seus-dados)

---

## 1. Princípios

1. **Backup antes de qualquer mudança**, conferido por SHA-256. Sem backup, não prossiga.
2. **Leia antes de escrever.** Quase tudo nas fases 1 a 5 é leitura e não muda nada.
3. **Uma mudança por vez**, e anote como desfazê-la antes de fazê-la.
4. **Prefira o que é reversível:** `pm disable-user`, arquivo de `hosts` e overlay vêm antes de gravar partição.
5. **Verifique com um comando**, não com a memória. Escreva um `verify` que diga se cada mudança está ativa.
6. **Separe observação de inferência.** "O app lê esta propriedade" é um fato; "então ele envia dados" é uma
   hipótese até você ver o envio.

## 2. O que você precisa

| Item | Para quê | Obrigatório |
|---|---|---|
| PC com `adb` ([platform-tools](https://developer.android.com/tools/releases/platform-tools)) | Falar com o aparelho | Sim |
| Rede local com o projetor | ADB por Wi-Fi | Ou cabo USB |
| Espaço em disco (algumas dezenas de GB) | Backup das partições | Sim |
| Python 3 e `bash` (Git Bash no Windows) | Scripts deste repositório | Para os scripts |
| JDK 17 e Android SDK (`build-tools` 34, `platforms/android-33`) | Compilar o launcher e o app do celular | Opcional |
| `aapt2`, `apksigner` (do `build-tools`) | Inspecionar APKs | Para a fase 5 |
| Adaptador USB-TTL de 3,3 V | Console serial (UART) como rede de segurança | **Só** para gravar partições |
| [REA](https://github.com/morluto/rea) | Análise assistida por agente | Opcional |

## 3. Fase 0: acesso por ADB

Ative as **Opções do desenvolvedor** e a **depuração USB** no projetor. Para usar pela rede:

```bash
adb connect 192.168.0.X:5555      # troque pelo IP do projetor
adb devices -l                    # deve listar "device"
adb shell id                      # uid=0(root) significa shell root
adb shell getprop ro.debuggable   # 1 = build de depuração
adb shell getprop ro.adb.secure   # 1 = exige autorização do PC; vazio ou 0 = ADB aberto
```

- Se o aparelho só aceitar USB, faça uma vez por cabo: `adb tcpip 5555`.
- **No nosso aparelho** o ADB pela rede estava aberto (sem autenticação) e já entrava como root. Isso é
  grave: qualquer pessoa na rede teria root. Veja a [fase 6](#9-fase-6-mudanças-reversíveis).
- Se `adb shell id` não mostrar `uid=0`, várias etapas (ler `/data`, gravar overlay, `lpdump`) não funcionam.
  Não force: trabalhe só com o que o seu acesso permite.

## 4. Fase 1: descobrir o hardware real

Projetores baratos costumam **mostrar** especificações diferentes das reais. Meça, não confie na tela "Sobre".

```bash
adb shell getprop | grep -E "ro.board.platform|ro.hardware|ro.soc|ro.product.(model|device|name)"
adb shell cat /proc/cpuinfo                  # arquitetura e a linha "Hardware"
adb shell cat /proc/meminfo | head -3        # RAM real (MemTotal)
adb shell uname -a                           # kernel e se o userland é de 32 ou 64 bits
adb shell getprop ro.product.cpu.abilist     # só armeabi-v7a = userland de 32 bits
adb shell wm size; adb shell wm density     # resolução real do painel
adb shell ls -l /dev/block/by-name           # mapa de partições
adb shell lpdump                              # partições dinâmicas (super), se existir
adb shell getprop | grep -E "virtual|identity" # propriedades que falsificam a tela "Sobre"
```

**O que encontramos** (como exemplo do que procurar):

- RAM real de ~1 GB, enquanto `persist.sys.virtual.ddr=2` fazia o sistema mostrar 2 GB.
- Painel de 800×480, enquanto o device tree trazia tempos de 1080p.
- Fingerprint falsificado de um Pixel 5 e modelo "ADT-3".
- Motor de foco e sensores TOF e giroscópio **ausentes**, embora o app tenha código para eles.

Anote o SoC (`ro.board.platform`), o Android, o kernel, a RAM, o armazenamento e o esquema de partições.
Esses seis dados decidem [o que pode ser reaproveitado](#12-adaptar-a-outro-projetor).

## 5. Fase 2: backup completo e verificado

Faça isso **antes** de qualquer escrita. Em modo leitura, partição por partição:

```bash
mkdir backup
adb shell ls -l /dev/block/by-name           # anote os nomes
# uma partição de cada vez (exec-out entrega binário sem corromper):
adb exec-out "dd if=/dev/block/by-name/boot_a bs=1M 2>/dev/null" > backup/boot_a.img
sha256sum backup/boot_a.img                  # hash no PC
adb shell "sha256sum /dev/block/by-name/boot_a"   # hash no aparelho, se houver sha256sum
```

- Compare os dois hashes. Se diferirem, refaça a cópia.
- Guarde **tudo** (bootloader, `boot`, `vbmeta`, `super`, `param`, `tee`...). `userdata` é opcional e grande.
- Escreva um `checksums.txt` e guarde uma cópia **fora** do PC onde você trabalha.
- Guarde também `getprop`, a lista de pacotes e a saída de `ls -l /dev/block/by-name`.
- No nosso caso o `super` tinha 2,2 GB. A cópia pela rede é lenta; tenha paciência.

> O backup só ajuda se você tiver **como regravá-lo** com o aparelho quebrado. Veja a [seção 13](#13-o-que-não-fazer).

## 6. Fase 3: inventário de software e gatilhos de boot

```bash
adb shell pm list packages -f -3             # apps de terceiros (com o caminho do APK)
adb shell pm list packages -f -s             # apps de sistema
adb shell ps -A -o USER,PID,NAME             # o que está rodando
adb shell dumpsys package <pacote> | grep -E "userId|sharedUser|versionName|installerPackageName"
adb shell getprop | grep "init.svc"          # serviços do init e seus estados
adb shell ls /system/etc/init /vendor/etc/init /odm/etc/init /product/etc/init
```

Os arquivos `.rc` de `init` mostram **o que roda a cada boot e como**. Leia todos, procurando:

```bash
adb shell "grep -rnE 'curl|wget|http|pm install|su |nc ' /system/etc/init /vendor/etc/init 2>/dev/null"
adb pull /system/etc/init ./init-system      # e leia no PC com calma
```

Sinais de alerta que encontramos:

- um `.rc` que executava, **como root e a cada boot**, scripts de uma pasta com permissão 777;
- scripts que baixavam e executavam outros scripts da internet (parte por HTTP, sem verificação);
- apps de sistema com permissão para instalar pacotes em silêncio e baixar arquivos.

Veja também logs deixados pelo fabricante (por exemplo em `/data/local/`): o nosso mostrava instalações
remotas de apps de anúncio com data e URL.

## 7. Fase 4: o que o aparelho faz na rede

```bash
adb shell ss -tunp            # conexões e processos (ou netstat, conforme existir)
adb shell dumpsys connectivity | grep -iE "dns|private"
adb logcat -d | grep -iE "http|dns|ssl"
```

- Para ver o tráfego de fato, capture no **roteador** ou num ponto de espelhamento e abra no Wireshark.
  Isso não foi automatizado aqui.
- Quando achar um domínio suspeito, **pesquise-o nos scripts e nos APKs** antes de concluir algo.
- Um domínio aparecer numa propriedade (`persist.sys.*.url`) não prova que algo o contacta. Confirme.

## 8. Fase 5: analisar um APK

```bash
adb shell pm path <pacote>                   # caminho no aparelho
adb pull /caminho/base.apk .
aapt2 dump badging base.apk                  # pacote, versão, SDK, ABIs, atividades
aapt2 dump permissions base.apk              # permissões pedidas
apksigner verify --print-certs base.apk      # quem assinou
```

O que observar:

- **UID de sistema** (`sharedUser=android.uid.system`) e permissões como `INSTALL_PACKAGES`,
  `DELETE_PACKAGES`, `REBOOT`, `RECOVERY`: um app assim controla o aparelho.
- **Assinante inesperado.** Para um APK baixado, compare com o publicado pelo autor. Ao atualizar um app já
  instalado, o Android só aceita a mesma chave, o que impede adulteração.
- **Strings** do `classes.dex` (URLs, nomes de propriedades) dão pistas rápidas. Para decompilar de fato,
  use o [jadx](https://github.com/skylot/jadx).
- Bibliotecas `.so` do app mostram a cadeia nativa (por exemplo, no nosso aparelho o keystone usa
  `libjmkeystone_jni.so` e `libkeystonecorrection.so`).

Resultado do nosso caso: a cadeia do keystone (acelerômetro, app, propriedades, compositor) está descrita
no guia de resultados. Com o JADX vimos que o app **só grava uma propriedade** e o compositor a lê. O que
**ainda não** sabemos é *quando* o compositor relê. Cuidado: uma propriedade com `debug` no nome pode ser
escrita pelo próprio app, e não pelo componente que você quer observar. Confirme **quem escreve** antes de
usá-la como prova.

## 9. Fase 6: mudanças reversíveis

Use a ordem abaixo. Cada passo tem o comando de desfazer.

### 9.1 Desativar apps (reversível)

```bash
adb shell pm disable-user --user 0 <pacote>   # desfazer: adb shell pm enable <pacote>
```

Antes de desativar um app de sistema, descubra o que depende dele. No nosso aparelho, desativar o
`com.jm.jmlauncher` sem outro app que atendesse a categoria `HOME.JM` deixou a tecla HOME abrir uma tela vazia.

### 9.2 Bloquear domínios no `hosts`

Acrescente `0.0.0.0 dominio` ao `/system/etc/hosts`. Em muitos aparelhos `/system` é somente leitura; veja o
overlay abaixo. DNS privado (DNS sobre TLS) cobre o resto, sem tocar em `/system`:

```bash
adb shell settings put global private_dns_specifier dns.adguard-dns.com
adb shell settings put global private_dns_mode hostname
# desfazer: adb shell settings put global private_dns_mode opportunistic
```

### 9.3 Alterar arquivos de `/system` pelo overlay

Em builds de depuração o `/system` costuma ter um **overlay** gravável:

```bash
adb shell mount | grep "overlay on /system"   # procure upperdir=...
```

O que escrever em `upperdir` (por exemplo `/mnt/scratch/overlay/system/upper/etc/hosts`) passa a valer
**depois de reiniciar**. Escrever na camada de baixo com o overlay montado é um comportamento que o overlayfs
não garante, por isso reiniciamos. Limites que você precisa conhecer:

- O overlay do nosso aparelho mora dentro do `userdata`: **um reset de fábrica o apaga**.
- Os arquivos de `vendor` e `product` do nosso overlay eram do fabricante (de 18/04/2026), não nossos.
  **Olhe a data de cada arquivo antes de assumir que a mudança é sua.**

### 9.4 Neutralizar scripts do fabricante

Em vez de apagar, troque por um script que só faz `exit 0`, **depois de guardar o original**. Confira o
SHA-256 do arquivo antes de sobrescrever: se não for o esperado, o firmware é outro e você deve parar.

### 9.5 Exigir autorização do PC no ADB, sem se trancar fora

`ro.adb.secure=1` faz o aparelho pedir a chave do PC. **A ordem importa:**

1. autorize a chave do PC primeiro (acrescente `~/.android/adbkey.pub` a `/data/misc/adb/adb_keys`);
2. só então grave `ro.adb.secure=1` no `build.prop`;
3. reinicie e confirme que o PC ainda conecta.

Se fizer ao contrário, perde o acesso ao aparelho.

Os scripts de [`scripts/phase1/`](../scripts/phase1) automatizam as seções 9.2 a 9.5 para o HY300-T.

## 10. Fase 7: launcher próprio e controle remoto

- **Launcher:** o [HY Home](../README.md) é leve (sem rede, ~20 a 25 MB de RAM) e atende `HOME` e `HOME.JM`.
  Para descobrir a categoria que a sua tecla HOME usa:
  `adb shell dumpsys package <launcher-do-fabricante> | grep -B2 -A4 "HOME"`.
  Defina como padrão com `adb shell cmd role add-role-holder android.app.role.HOME <pacote>`.
- **Controle pelo PC:** `tools/remote/hy300_remote.py` (Python, só `adb`).
- **Controle pelo celular:** o app `phone/` fala o protocolo ADB direto com o `adbd` do projetor, usando a mesma
  autenticação do PC. **Nada novo escuta no projetor.** Rejeitamos, de propósito, instalar um receptor com UID
  de sistema, porque seria uma nova superfície de execução remota.

## 11. Verificar, desfazer e sobreviver a um reset

```bash
scripts/phase1/verify.sh                  # só leitura: confere cada mudança
scripts/phase1/reapply.sh                 # simulação: o que faria depois de um reset
scripts/phase1/reapply.sh --apply         # aplica (depois reinicie e rode verify.sh)
scripts/phase1/selftest.sh <pasta>        # offline: confere as transformações com os originais
```

- Cada passo da [fase 6](#9-fase-6-mudanças-reversíveis) tem um desfazer. Guarde-os num arquivo.
- Para voltar ao estado original de um arquivo, restaure o original do backup e reinicie.
- **Gravar na partição `super` não foi feito.** Descrevemos o caminho, mas ele só vale com UART e um modo de
  gravação USB conhecido (veja a [seção 13](#13-o-que-não-fazer)).

## 12. Adaptar a outro projetor

**"HY300" é um nome de marketing, não uma placa.** Encontramos outro projeto de engenharia reversa de um
"HY300" cujo aparelho era um **Rockchip RK3326**, com Android 12, kernel 4.19 e partições totalmente
diferentes das do nosso **Amlogic T950S** (Android 13, kernel 5.15, A/B). Imagens, partições e ROMs de um
**não servem no outro**. Gravar uma imagem de outro SoC pode deixar o aparelho sem boot.

Antes de reaproveitar qualquer coisa de outra pessoa, compare **estes seis dados** (fase 1):

| Dado | Como ver |
|---|---|
| SoC / plataforma | `getprop ro.board.platform`, linha "Hardware" do `/proc/cpuinfo` |
| Versão do Android | `getprop ro.build.version.release` |
| Kernel | `uname -a` |
| Mapa de partições | `ls -l /dev/block/by-name` |
| Esquema A/B ou não | `getprop ro.build.ab_update`, `ro.boot.slot_suffix` |
| Partições dinâmicas | `lpdump` |

**O que muda por plataforma (conhecimento geral, não verificado no seu aparelho):** a cadeia de boot, a
ferramenta de gravação via USB (Amlogic, Rockchip e Allwinner têm as suas), onde ficam os parâmetros de
painel e os nomes das partições.

**O que costuma valer em qualquer Android:** as fases 0, 2, 3, 4 e 5, o `pm disable-user`, o `hosts`, o DNS
privado, o launcher próprio e o controle por ADB. O `reapply.sh` de `scripts/phase1/` confere o SoC
(`txhd2` / `t950s_be311`) e se recusa a rodar em outro aparelho; o `verify.sh` só avisa. Para o seu, adapte as constantes e **os
hashes**.

## 13. O que não fazer

- **Não grave partições** (`super`, `boot`, bootloader) sem **UART funcionando** e sem saber como regravar o
  aparelho quebrado. No nosso, as partições físicas de boot do slot B estavam zeradas: não há reserva.
- **Não grave imagens de outro SoC ou de outro firmware.**
- **Não aceite chaves ADB nem instale APKs que você não verificou** (hash e assinante).
- **Não use as lojas de terceiros que vêm de fábrica**: no nosso aparelho elas instalavam pacotes em silêncio.
- **Não deixe o ADB por Wi-Fi aberto.** Ative `ro.adb.secure=1` (com a chave do PC já autorizada) e use só em
  rede confiável.
- **Não desative um app de sistema sem checar suas dependências** (a tecla HOME é o exemplo clássico).
- **Não tente contornar CAPTCHAs nem verificações anti-robô** de sites de download. Baixe de fontes oficiais
  (GitHub do autor, site do projeto) e confira o SHA-256 que eles publicam.

## 14. Usando o REA (opcional)

O [REA](https://github.com/morluto/rea) é um servidor MCP de engenharia reversa que um agente (como o Claude
Code) usa para analisar binários, pacotes e dispositivos. O que usamos e o que não:

| Uso | Funcionou? |
|---|---|
| Ferramentas ADB de **leitura** (identidade do dispositivo, processos, pacotes, logcat, resolução) | Sim, sem alterar o aparelho |
| Análise estática de APK (`inspect_android_package`, classes e métodos) | **Não**: exige um JAR do JADX (`REA_JADX_MCP_JAR`) que não configuramos |
| Análise de bibliotecas nativas (`.so`) | Não testamos |

Sem o JADX, usamos `aapt2`, `apksigner`, `strings` do dex e `dumpsys package`, que já respondem às perguntas
das fases 3 e 5. O REA **não** é necessário para seguir este guia.

## 15. Problemas comuns

| Sintoma | Causa provável | O que fazer |
|---|---|---|
| `adb: device not found` | IP mudou ou ADB por Wi-Fi caiu | `adb connect <ip>:5555`; reserve o IP no roteador |
| `unauthorized` | ADB exige chave | aceite a chave na tela do projetor ("Sempre permitir") |
| `adb shell id` sem `uid=0` | sem root | várias fases ficam só em leitura |
| `/system` sem overlay | build de produção | não há onde gravar sem remontar; pare ou use `pm disable-user` e DNS |
| A tecla HOME abre uma tela vazia | launcher do fabricante usa categoria própria | veja a [seção 10](#10-fase-7-launcher-próprio-e-controle-remoto) |
| Play Store diz "incompatível" | o app é a versão de celular num aparelho de TV, ou o aparelho não é certificado | procure a versão de Android TV do app |
| App de navegador fecha ao abrir (OpenGL) | GPU Mali com driver limitado | use um navegador com WebView do sistema (nós usamos o TV Bro) |
| `DELETE_FAILED_INTERNAL_ERROR` ao desinstalar | não sei a causa; no nosso caso o pacote saiu mesmo assim | confira com `pm path <pacote>` |
| Mudanças sumiram depois de um reset | o overlay mora no `userdata` | `scripts/phase1/reapply.sh --apply` |

## 16. Compartilhar sem expor seus dados

Antes de publicar logs, capturas ou relatórios, remova ou troque:

- **número de série, MAC, IMEI, ID do eMMC** e qualquer `dnum`/identificador do fabricante;
- **nome (SSID) e IP da sua rede**, e endereços de e-mail ou contas Google;
- **chaves** (`adbkey`, `adbkey.pub`), tokens e senhas, inclusive em capturas de tela;
- conteúdo de **outras janelas** na captura da tela do PC (capture só a janela que importa).

Não publique firmware de terceiros (imagens `.img`). Publique **o método, os hashes e os scripts**, como este
repositório faz.
