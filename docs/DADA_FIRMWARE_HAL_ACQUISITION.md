# dada：官方发布包与 MiCharge HAL 取得记录

日期：2026-10-13。接续 [第一阶段方案](DADA_IMPLEMENTATION_NEXT_STEPS.md)。本轮只在电脑下载、解析和比较文件，ADB、手机读取、写入、安装、运行厂商程序及实机试验均为 0。

**已取得 OS3.0.303.0.WOCCNXM 发布包内的原厂 MiCharge AIDL v2 服务、配套 NDK 库和配置。包内 20 份已研究驱动、25 份已采 ADSP 文件与此前电脑工件逐字节相同。** 这使原生接口调查从未知来源的公共样本推进到了配套发布包；它仍不证明手机当前 HAL 文件或运行 ADSP 内存身份，也没有取得 MCA 源码或 PMIC 字段定义。

## 来源、版本和取得方法

先前本地 [device-root-inventory.txt](../.tooling/bypass-check/device-root-inventory.txt) 前五行给出 `24129PN74C / dada`、`ro.build.version.incremental=OS3.0.303.0.WOCCNXM` 和运行内核 `6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k`。没有为确定版本重新查询手机。

[MiFirm 索引](https://mifirm.net/downloadzip/28255)与 [XM Firmware Updater 索引](https://xmfirmwareupdater.com/hyperos/dada/stable/OS3.0.303.0.WOCCNXM/)提供包名及地址线索；具体分析材料取自[小米下载域名上的发布包](https://cdnorg.d.miui.com/OS3.0.303.0.WOCCNXM/dada-ota_full-OS3.0.303.0.WOCCNXM-user-16.0-527efd3838.zip)。二手索引没有替代包内 metadata、manifest 或原始 ELF 的证据。

原 ZIP 大小为 **8,452,290,755 bytes**。服务器返回 `Accept-Ranges: bytes`，七次限定范围取得前后 ZIP 目录、payload metadata 和四个分区的数据，共 **734,486,055 bytes**；没有下载完整 ZIP，也没有安装 ROM。来源 URL、HTTP Content-Range、文件大小和 SHA-256 见 [acquisition-manifest.json](../.tooling/bypass-check/stage1/root-firmware/acquisition-manifest.json)。

包内身份应分别记录，不能把不同分区的版本字符串自动视为同一值：

| 证据 | 实际内容 |
| --- | --- |
| 下载包名 | `dada-ota_full-OS3.0.303.0.WOCCNXM-user-16.0-527efd3838.zip` |
| OTA metadata 的 post-build | `Xiaomi/dada/dada:16/BP2A.250605.031.A3/16OS3.1.260529.201026374.QCPECN.S:user/release-keys` |
| OTA post-build-incremental / SDK | `16OS3.1.260529.201026374.QCPECN.S` / 36 |
| vendor build.prop 增量 | `OS3.0.303.0.WOCCNXM` |
| vendor fingerprint | `Xiaomi/mivendor/mivendor:15/AQ3A.250226.002/OS3.0.303.0.WOCCNXM:user/release-keys` |
| vendor security patch | `2026-02-01` |

因此，整体 Android 16 与 vendor 分区 Android 15 标识并列保留；不能据此推断内核被谁替换，也不能只凭展示版本认定当前全部文件匹配。

## 重建与校验

payload.bin 在 ZIP 内偏移为 **4830**，采用 Store，未额外压缩。CrAU v2 头 24 bytes、manifest 309106 bytes、metadata signature 267 bytes，后续数据起点是 ZIP 绝对偏移 **314227**。`payload_properties.txt` 的 `METADATA_SIZE=309130` 只覆盖头和 manifest，前 309130 bytes 的 SHA-256 与 `METADATA_HASH` 相符；这与含 signature 的下载长度 309397 bytes不同。

自写 [inspect_payload.py](../.tooling/bypass-check/stage1/root-firmware/inspect_payload.py)仅解析保存文件、解压已取得的分区数据，依据 Android [update_metadata.proto](https://android.googlesource.com/platform/system/update_engine/+/refs/heads/main/update_metadata.proto) 的字段定义。该规范取自浮动 main，本地原文 SHA-256 已记录，没有声称固定到 commit。未执行下载仓库的脚本。

| 分区 | 镜像大小 bytes | 操作数 | 重建后整镜像 SHA-256 |
| --- | ---: | ---: | --- |
| vendor | 794562560 | 379 | `6e38127f1bc0cbd3b192a5bdc0c9ab2fb024ba6c0bf17c95b8bf38b4cebf82ba` |
| vendor_dlkm | 77938688 | 38 | `b11940f67f673076ce5f0ac409919505d7af7495f4999f123891f426ae63042b` |
| modem | 198373376 | 95 | `f1d92bea5ea087478e35bbcb5400e043a70dcac005283d21ac655952934ad35d` |
| dsp | 67108864 | 32 | `10ed4d423090e3754f716b80872521b28e7a711e8a7c8e9d45669f117446921f` |

每个含数据的操作先检验 manifest 所给压缩数据 SHA-256，再按 REPLACE / BZ / XZ 解码并写入限定镜像范围，最后比较整分区 SHA-256。实际未遇到 ZERO 操作。独立审查另行解析 manifest 与 ZIP 头、对 vendor 全部操作解码后逐字节比较目标 extents，确认完整覆盖、无重叠；见 [取得独立审查](DADA_FIRMWARE_ACQUISITION_REVIEW.md)。

这些校验支持离线分析的材料一致性；没有完成厂家签名验证、完整 payload FILE_HASH 或 ZIP MD5 校验。没有据此进行刷写、加载或硬件操作。

## 实际取得的 HAL

vendor 是 EROFS，使用按 Homebrew 公布散列核对的本地 `dump.erofs`，以 `--cat` 仅提取已知目标。工具解包在项目目录，通过已有 lz4/xz 库运行，没有系统安装。早期 7-Zip 对 vendor 误识内嵌 ZIP 的失败列表不作文件系统证据。

| 包内文件 | 大小 bytes | SHA-256 |
| --- | ---: | --- |
| `bin/hw/vendor.xiaomi.hardware.micharge-service` | 1201336 | `0dcfb7b5816d962978e8724fdbb7798627f0575c6a72beae277c55b51f1f158b` |
| `lib64/vendor.xiaomi.hardware.micharge-V2-ndk.so` | 146728 | `42e7271e3da28f2e844b460b577e4a4f38463d1a57d7982ff776ca4c90c525fc` |
| `etc/init/vendor.xiaomi.hardware.micharge-service.rc` | 12658 | `bec27a47990f9396908e4e0f0f54c42a0ed77879c117ec4d45404c48958c238b` |
| `etc/vintf/manifest/vendor.xiaomi.hardware.micharge.xml` | 323 | `f3013394f19766489eee6d20fd22ee8a89a5007ccbcb69b92a751b525fe4687d` |
| `build.prop` | 14164 | `c5445a1f99905b51cfd0b707d329dcb84eaa6dfca70d5fb136209fd11c60dfa8` |

ELF metadata 独立确认 service Build ID 为 `7d86416112f836f15071f609c9b50b9f`，其依赖上述 NDK SONAME；NDK Build ID 为 `78ada333da98e40a4727b4fa3c5d38ae`。这与 MiChargeShim 公共样本的 service Build ID 不同，不混用样本地址或实现。rc/VINTF 指定 `vendor.xiaomi.hardware.micharge.IMiCharge/default`，AIDL version 2。

文件保存在 [vendor-extract](../.tooling/bypass-check/stage1/root-firmware/vendor-extract/)，原生 transaction、实际方法、路径及错误处理审查见 [DADA_NATIVE_INTERFACE_STAGE1.md](DADA_NATIVE_INTERFACE_STAGE1.md)。没有运行服务或发出 Binder 请求。

## 与已有驱动及 ADSP 工件的比较

vendor_dlkm 提取的 19 个 MCA 模块及 sc8581，与先前 `driver-binaries/` 中对应 20 个文件逐字节相同；不只是版本或 build-id 相同。详见 [mca-package-comparison.json](../.tooling/bypass-check/stage1/root-firmware/mca-package-comparison.json)。这支持 HAL 所配驱动与旧分析对象对应，没有补齐源码、头文件、最终配置、完整 Module.symvers 或新扩展加载条件。

包内 `vendor/etc/fstab.qcom:93-95` 明确 modem 挂载 `/vendor/firmware_mnt`，modemfirmware 挂载 `/vendor/modem_firmware`，dsp 挂载 `/vendor/dsp`。早期取得的 dsp 是动态库分区，不能当作 ADSP 主固件身份；随后按已确认 fstab 提取 modem。

modem 的 FAT16 BPB 声明总容量 211812352 bytes，而 OTA 重建镜像为 198373376 bytes，7-Zip 因此报告 `Unexpected end of archive`。没有改原镜像、补零或将工具错误作为成功。自写 [compare_adsp_fat.py](../.tooling/bypass-check/stage1/root-firmware/compare_adsp_fat.py)从原文件只读解析有效 FAT16 链，确认所选文件的全部 cluster 都在保存镜像内，再按目录长度提取。

选定的 25 个原已采文件全部与旧文件逐字节相同，包括 `adsp.b19..b22` 充电代码、`adsp.mdt`、`adsp_dtb.b01` 和 `adsp_dtb.mdt`。文件散列及逐 cluster 范围见 [adsp-package-comparison.json](../.tooling/bypass-check/stage1/root-firmware/adsp-package-comparison.json)，几何及边界记录见 [fat-extraction-geometry.json](../.tooling/bypass-check/stage1/root-firmware/fat-extraction-geometry.json)。

上述是**包内文件与此前磁盘采集件的对应**。当前运行 ADSP/charger PD、所选 PMIC model/revision、HAL 现场身份以及真实系统供电路径仍未由本轮证明；不能用文件相同替代这些条件或物理旁路验收。
