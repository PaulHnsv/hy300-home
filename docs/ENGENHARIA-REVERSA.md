# HY300-T: guia de engenharia reversa

Como o aparelho foi analisado, o que foi encontrado e o que foi (ou não) alterado.
Tudo abaixo foi observado em **uma unidade** com Android 13, build `userdebug` assinado com
`test-keys`. Itens marcados como *inferência* não foram testados no aparelho.

> Use só em equipamento seu. Alterar `/system` ou partições pode deixar o aparelho sem boot.

## 1. Preparação e segurança

1. **Backup antes de qualquer mudança.** Todas as partições foram copiadas e conferidas por
   SHA-256. Sem isso, não prossiga.
2. Acesso por **ADB sobre Wi-Fi** (porta 5555). O shell já entrou como root (`ro.debuggable=1`).
3. Use uma ferramenta de análise para leitura (processos, pacotes, logs, settings, display) e o
   shell do `adb` para o resto. As ferramentas de leitura do REA (Reverse Engineer Anything) não
   alteram o aparelho.
4. **Regra de ouro:** primeiro leia, depois mude uma coisa por vez, e anote como desfazer.

## 2. Hardware real versus o que o sistema mostra

| Item | Real (medido) | Mostrado pelo sistema |
|---|---|---|
| SoC | Amlogic T950S, 4× Cortex-A53, userland 32 bits | |
| RAM | ~1 GB | `persist.sys.virtual.ddr=2` faz a tela "Sobre" mostrar 2 GB |
| eMMC | 16 GB | `persist.sys.virtual.emmc=8` mostra 8 GB |
| Painel | LCD LVDS 800×480 | Device tree traz timings 1080p genéricos |
| Saída Android | `dummy_panel` 800×480 a 30 fps | |
| Acelerômetro | SC7A20 no I2C-1 | |
| Giroscópio, TOF | ausentes | o app tem código para eles |
| Motor de foco | **ausente** (`motor0` com `status = "disable"` no DT) | o app suporta autofoco |
| Áudio | TI TAS5805 | |
| Wi-Fi/BT | Seekwave SWT6621S (USB) e iComm SV6160 (SDIO) | |
| UART | `ttyS0`, 115200 baud | |

O firmware tem um mecanismo explícito (`persist.sys.virtual.*`, `virtual.identity=true`) para a
tela "Sobre" exibir especificações diferentes das reais. O fingerprint também é falsificado
(Pixel 5, modelo "ADT-3").

## 3. Software e segurança

- Kernel 5.15.119 de 32 bits, **sem SELinux**, sem assinatura de módulos, `/proc/config.gz`.
- Boot em estado *orange* (bootloader destravado), `ro.debuggable=1`, ADB root direto.
- Esquema A/B, mas o **slot B está vazio**. Não existe fallback de boot.
- `/system` aparece com overlay gravável (`/mnt/scratch/overlay/system/upper`). As edições feitas
  ali **não** estão na partição `super`.
- ADB na 5555 **sem autenticação** no estado original (`ro.adb.secure` ausente).

## 4. Backdoor do fabricante

Descoberto lendo os `.rc` de `/system/etc/init` e seguindo o que eles executavam.

- `ttyunos.rc` rodava, como root e a cada boot, scripts em `/data/ttyunos/` (pasta 777: qualquer
  app podia gravar ali).
- Esses scripts baixavam e executavam, como root, scripts de domínios do fabricante, parte
  por HTTP.
- O payload enviava serial, MAC, modelo e país a um servidor e executava os `.tar.gz` devolvidos.
- O log mostrava instalações remotas de apps de anúncio.
- `preinstall.rc` tinha outros gatilhos root (script em pendrive, instalação silenciosa de xapk).

**Como foi neutralizado** (cada passo reversível, com a cópia original guardada):

| Mudança | Desfazer |
|---|---|
| scripts de `/data/ttyunos/*.sh` viraram no-op, pasta passou a 755 | restaurar dos originais |
| `ttyunos.rc` comentado inteiro | restaurar o original |
| gatilhos de `preinstall.rc` desativados | restaurar o original |
| domínios do fabricante, de anúncio e de analytics apontados para 127.0.0.1 em `/system/etc/hosts` | restaurar o original |
| `ro.adb.secure=1` + chave do PC em `/data/misc/adb/adb_keys` | remover a linha do `build.prop` |

Verificado depois do reboot: nenhum serviço do backdoor iniciou e o ADB passou a exigir chave.

## 5. Debloat reversível

`pm disable-user --user 0 <pacote>` (desfazer com `pm enable`). Desativados: launcher/protetor de
tela com anúncios, app de anúncios instalado remotamente, duas lojas de terceiros, um serviço de
sistema que instalava pacotes em silêncio, o app de teste de fábrica, o atualizador OTA, e três
apps de TV sem uso (`droidlivetvsettings`, `tvrecommendations`, `leanbacklauncher`).

**Não desative `com.jm.jmlauncher` sem outro launcher que atenda `HOME.JM`.** A tecla HOME deste
firmware usa a categoria `android.intent.category.HOME.JM`. Sem um app que a atenda, o HOME cai no
`FallbackHome` (tela vazia). O HY Home atende essa categoria e o substitui.

## 6. Keystone (correção trapezoidal)

Cadeia identificada:

```
SC7A20 (acelerômetro)
  -> com.softwinner.tcorrection (+ libjmkeystone_jni.so): calcula os ângulos
       grava persist.keystone.{vertical,horizontal,rotation}.degree e
       persist.vendor.hwc.keystone (4 cantos, espaço 1920x1080)
  -> hwcomposer + libkeystonecorrection.so: monta a matriz de perspectiva e deforma cada
       frame por OpenGL
```

- O keystone é **100% digital**: cada grau de correção custa resolução efetiva no painel 800×480.
  A melhor correção é física (calçar o projetor).
- `persist.sys.auto.trapezoid=true` faz o `tcorrection` recalcular os cantos pelo acelerômetro.
- **Teste de escrita direta:** com o auto desligado, gravei outros cantos em
  `persist.vendor.hwc.keystone` e li `persist.vendor.hwc.keystone.debug` depois de 3 s. Nenhuma
  mudança observável. Não consegui ver a imagem projetada, então isso **não prova** que o
  compositor ignora a propriedade; só que não vi reação. *Em aberto:* como o `tcorrection` avisa o
  compositor (broadcast, binder, outra propriedade).
- Cuidado ao testar: leia e guarde o valor atual antes de gravar. Os valores mudam com a
  inclinação, e um valor de relatório antigo já estava desatualizado.

## 7. Foco

O código de autofoco existe (`motor0/motor_ctrl`), mas o nó está desativado no device tree e
`/sys/class/motor` está vazio. A unidade analisada provavelmente tem foco manual. *Inferência.*

## 8. Desempenho

Gargalo principal: **1 GB de RAM** com swap em uso e temperatura do SoC por volta de 82 °C
(sem redução de frequência observada nos cooling devices). Efeito medido das mudanças de software:
poucos MB de RAM. Ventilação física é o que mais ajuda.

| Medida | Valor |
|---|---|
| PSS do FLauncher (Flutter) | ~36 MB |
| PSS do HY Home | ~20–25 MB |

## 9. Como reproduzir a análise

```bash
adb connect <ip>:5555
adb devices -l
adb shell getprop                 # propriedades (virtual.*, keystone.*, adb.secure)
adb shell ps -A                   # processos residentes
adb shell pm list packages -3     # apps de terceiros
adb shell dumpsys package <pkg>   # filtros de intent, permissões, uid
adb shell cat /proc/meminfo
adb shell cat /sys/class/thermal/thermal_zone0/temp
adb pull /system/etc/init ./init  # leia os .rc para achar gatilhos de boot
```

Para analisar APKs: extraia o `classes.dex`, use `jadx` ou `dexdump`. O REA precisa de um JAR do
JADX configurado (`REA_JADX_MCP_JAR`) para inspecionar APKs; sem ele, use as ferramentas acima.

## 10. Bloqueio de anúncios na rede (DNS privado)

Além do `hosts`, o DNS privado do Android (DNS sobre TLS) cobre todo o sistema:

```bash
adb shell settings put global private_dns_specifier dns.adguard-dns.com
adb shell settings put global private_dns_mode hostname
# desfazer:
adb shell settings put global private_dns_mode opportunistic
```

Validado: `dumpsys connectivity` mostra `UsePrivateDns: true` e `ValidatedPrivateDnsAddresses`.
O provedor passa a ver os domínios consultados. O bloqueio é por domínio e não remove anúncios
servidos pelo mesmo domínio do conteúdo (por exemplo, dentro do app do YouTube).

**Brave no Android não aceita extensões.** O bloqueio dele é embutido (Shields). Para YouTube sem
anúncios em TV, o SmartTube resolve melhor que um navegador.

## 11. O que ainda não foi feito

- Gravar as mudanças de `/system` na partição `super` (exige UART como rede de segurança).
- Descobrir como o `tcorrection` recarrega o keystone no compositor.
- Mapear o tráfego de rede dos apps (`ss`/`netstat`) e decompilar o launcher original com `jadx`.
- Ligar um USB-TTL 3,3 V no UART (`ttyS0`, 115200) para ter console de U-Boot.
