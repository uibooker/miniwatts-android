# dada OTA 固件取得与 vendor 重建独立审查

日期：2026-10-13。范围：独立审查主线程已保存的 OTA 范围响应、payload 元数据、自写 `inspect_payload.py`、vendor镜像与提取工件。本轮没有执行该重建脚本，没有下载、手机读取、ADB、安装或驱动加载，也没有修改脚本。

**当前保存的 vendor 取得与重建通过独立校验，没有发现阻断这份镜像离线分析的偏移、字段映射或内容完整性错误。** 审查重新解析了47个分区的 manifest，逐项验证 vendor 的379个操作数据哈希，并将解压后的每个目标范围与 `vendor.img` 逐字节比较。全部379项及整分区SHA256一致，目标范围连续、无重叠且完整覆盖794,562,560字节。

这一结果确认的是已保存包内镜像与其 manifest 的一致性，不是厂家公钥签名验真、完整 OTA 校验、现场HAL身份或正在运行的远端固件身份。包内版本字段及与旧驱动的对应关系需要按以下范围解释。

## 1. 字节偏移与范围响应

独立从 `ota-prefix.bin` 解析ZIP local headers，校验可完整取得的前置stored entries CRC32，并与保存的 `ota-zip-entries.json` 对照：

- payload.bin local header偏移4763，名字/extra长度后实际数据偏移为 **4830**。ZIP method=0，payload未被ZIP层压缩；ZIP64尺寸为8,452,282,253字节。
- 该偏移处是 `CrAU`；payload major=2，BE64 manifest长度=309,106，BE32 metadata signature长度=267。头长24，因此数据blob相对payload偏移为 **24+309106+267=309397**。
- 下载vendor blob的绝对基准是 **4830+309397=314227**，不是ZIP local header偏移，也不是单独protobuf结束位置。
- vendor manifest的blob区间是 `[7799372472,8381980342)`；对应HTTP inclusive范围为 **7799686699–8382294568**，长度 **582,607,870**。保存的 vendor-range.headers 与 vendor.blob大小完全对应。
- payload-metadata.headers 是4830–314226，恰好包含309,397字节；保存的metadata前段与ZIP prefix中payload起点处的字节相同。

prefix、tail、metadata和vendor的响应均为206，Content-Range总长度8,452,290,755一致，ETag一致。OTA文本 metadata 的 property-files 也记录 payload.bin:4830、payload_metadata.bin:4830:309397。这里不把multipart ETag视为SHA256，也没有因HTTP响应成立就跳过内容校验。

新增独立核对tail中的payload_properties.txt：ZIP local header、实际文件字节和CRC32均通过。其 `METADATA_SIZE=309130` 是 **24字节header+309106字节manifest，不含267字节signature**；HTTP metadata范围则包含signature，长309397，二者不应直接相等。对前309130字节独立计算SHA256为 `31ca48c8719d3d1ba8ee3b421f4684249285435aebce3a2cdd55447d632f9f86`，与properties中base64编码的METADATA_HASH完全一致。FILE_SIZE也等于ZIP中payload大小；没有验证FILE_HASH，亦未验证signature。这与主线程 `ota-property-verification.json` 修正后的解释一致。

详见 `.tooling/bypass-check/stage1/root-firmware/acquisition-review.json` 的 `zip_local_headers`、`independent_header`、`range_headers` 和 `range_calculation`。

## 2. protobuf字段与操作处理

格式参照是已保存的 `update_metadata.proto`，来源为官方 update_engine 的 **refs/heads/main**：

<https://android.googlesource.com/platform/system/update_engine/+/refs/heads/main/update_metadata.proto?format=TEXT>

它没有固定不可变commit。审查用保存原文字节及其SHA256作格式依据，已独立确认 `.base64` 解码与 `.proto`相同，不把它叫此OTA生产构建对应源码或固定版本发布。具体hash记录在review JSON的 `format_reference`。

`inspect_payload.py` 所用映射与该格式定义一致：

| 层 | 字段编号/意义 | 审查结果 |
| --- | --- | --- |
| DeltaArchiveManifest | 3 block_size，12 minor_version，13 partitions | 正确；本包4096、minor0 |
| PartitionUpdate | 1 partition_name，7 new_partition_info，8 operations | 正确 |
| PartitionInfo | 1 size，2 hash | 正确；读取新分区而非旧分区 |
| InstallOperation | 1 type，2 data_offset，3 data_length，6 dst_extents，8 data_sha256_hash | 正确；hash对应操作原始blob，压缩操作应先验hash再解压 |
| Extent | 1 start_block，2 num_blocks | 正确；按block_size换算并按列表顺序写目标范围 |

独立解析器没有import或调用被审脚本，以带wire类型和边界检查的另一份主机代码重新读取metadata；全部47个分区、所有记录与已保存payload-manifest.json一致。此次使用的关键singular字段没有重复，不存在被审脚本 `first()` 与标准protobuf重复singular处理差异影响本工件的问题。

| 操作类型 | 脚本处理 | 本包vendor实际覆盖 |
| --- | --- | --- |
| 0 REPLACE | 原始数据直接作为目标内容 | 2项，hash/解码长度/目标字节通过 |
| 1 REPLACE_BZ | 先验blob SHA，再bz2解压 | 1项，通过 |
| 8 REPLACE_XZ | 先验blob SHA，再lzma/XZ解压 | 376项，通过 |
| 6 ZERO | 合成目标长度的零字节，未读blob/hash | 整份manifest没有ZERO；仅静态审查该分支，不能叫它已由实际操作验证 |

未知操作会显式报错，SOURCE_COPY/差分/DISCARD等没有被错误当作REPLACE执行。脚本要求解码长度与dst范围总长严格相等；它没有实现格式文档中可能需要的块尾padding，因而不是通用OTA重建器。这份vendor全部解码长度恰好相等，不受该限制影响。

## 3. 操作与整分区校验

本轮从 vendor.blob 重新读取379个操作范围，独立执行以下只读检查：

1. 原始blob SHA256等于manifest中的data_sha256_hash。
2. 按0/1/8解码，解码后长度等于各dst_extents的总字节数。
3. 解码数据按extent列表分段，与保存的vendor.img对应范围逐字节相同。
4. 所有目标范围在分区大小内，排序后连续、无重叠，覆盖整个分区。
5. 重新流式计算整镜像SHA256，等于new_partition_info.hash：

`6e38127f1bc0cbd3b192a5bdc0c9ab2fb024ba6c0bf17c95b8bf38b4cebf82ba`

这比只读取先前脚本打印的“verified”或只复核它生成的JSON更强。被审脚本自身亦使用xb创建、长度/范围检查、逐操作blob校验及整镜像hash；失败不会被打印成成功。但它失败后可能留下部分输出，后续不得仅凭镜像文件存在判定取得成功。

EROFS magic在镜像offset1024处为 `e2 e1 f5 e0`。早期 `vendor-list.txt` 是7-Zip误识镜像内嵌ZIP的失败输出，含Headers Error/Unconfirmed start，不能当作完整vendor文件系统清单，也不能据它判vendor镜像坏。主线程之后使用EROFS工具取得文件；本独审核对保存的5项提取文件大小与SHA、build.prop副本一致性，没有重新执行文件系统提取器。因此“逐操作与分区一致”是独立确认，“提取文件来源路径”仍引用主线程EROFS取得记录，不能把两者混写为相同检查。

## 4. 包、OTA整体与vendor身份字段

保存的响应路径/包名是：

`dada-ota_full-OS3.0.303.0.WOCCNXM-user-16.0-527efd3838.zip`

但不能只用名字声明所有内部字段都等于手机的系统增量。实际已保存内容是：

| 身份层 | 字段与值 | 能支持的判断 |
| --- | --- | --- |
| OTA文本metadata | pre-device=dada，post-sdk-level=36；post-build的Android16与内部incremental `16OS3.1.260529.201026374.QCPECN.S` | 包内整体目标身份；不是与本机OS增量字符串逐字相同 |
| vendor/build.prop | vendor incremental=`OS3.0.303.0.WOCCNXM`；fingerprint=`Xiaomi/mivendor/mivendor:15/AQ3A.250226.002/OS3.0.303.0.WOCCNXM:user/release-keys`；release15/sdk35 | vendor分区自身版本标记与同名OS修订对应；不要求它采用OTA整体SDK36 |
| 既有本机inventory | 型号24129PN74C/dada；系统增量OS3.0.303.0.WOCCNXM；运行6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k | 当前研究设备基准；没有本轮现场HAL/firmware采集 |

不同层身份字段并存没有构成偏移或解压错误的证据；整分区hash已经独立通过。准确表述是：**来自同名OS包的vendor分区，其vendor增量与本机系统增量相同，但包内OTA整体使用另一个内部目标incremental，当前HAL的现场文件身份尚未证明。** 不能写“整个OTA post-build已与现场完全相同”，也不从Android16系统/Android15 vendor或GKI字串推断内核替换来源。

另独立读取 `mca-package-comparison.json` 指向的包内vendor_dlkm提取文件及既有driver-binaries，不仅比较JSON值：20份模块逐字节完全相同，SHA也与记录一致。这个20/20结论仅覆盖已列的MCA/sc8581磁盘驱动集，不是全vendor_dlkm、现场HAL、完整运行内存或远端固件身份；也没有补齐完整MCA源码/头文件。

`vendor-fstab.qcom:93-95` 将modem映射到 `/vendor/firmware_mnt`、vfat，而dsp映射到 `/vendor/dsp`、ext4。因此包内dsp分区不能仅按名字视为旧工件中firmware_mnt/adsp.b06等远端固件所在分区。后续modem磁盘文件的独立对照见下一节，仍不宣称运行ADSP身份匹配。

## 5. 追加：modem分区与25份ADSP文件

主线程后来取得modem.img和限定FAT16读取结果。本追加独审没有执行其 `compare_adsp_fat.py`、没有修改或补零镜像，也没有重复下载。

先重新计算modem整分区SHA256，并核对它与本轮原metadata独立解析过的manifest、后续modem-payload-manifest.json的记录一致。分区大小是 **198,373,376**，SHA256为：

`f1d92bea5ea087478e35bbcb5400e043a70dcac005283d21ac655952934ad35d`

进一步独立验证全部95个modem操作（REPLACE_XZ92项、REPLACE_BZ3项）的原blob哈希、解码后长度与目标字节；范围连续无重叠覆盖整个分区。HTTP绝对range也与314227基准加manifest blob范围一致。这说明保存镜像确实等于该OTA的new_partition目标，不是重建器少写或被本轮补齐出来的文件。

**7-Zip 的全局 `Unexpected end of archive` 仍然是有效错误记录，不能覆盖为“整个FAT卷正常”。** BPB声明51,712个4096字节sector，即 **211,812,352**，比OTA目标镜像长 **13,438,976**。本独审保留实际198,373,376字节镜像；没有将BPB大小当作应补零的授权，也没有推断缺失尾部无关所有文件。

对限定25份ADSP文件另作独立FAT16读取和比较，验证范围是文件自身完整链：

- 读取BPB重新计算sector4096、每cluster4 sectors即16384、reserved1、FAT2份、每份7 sectors、root512 entries；root_offset61440、data_offset77824。按declared几何计算data cluster数落在FAT16区间，不只依赖卷标字符串；两份FAT逐字节相同。
- 独立解析root和image目录，验证遇到的LFN顺序及short-name checksum；选中目录项文件size与保存comparison一致。
- 每条文件链逐项检查FAT索引、合法data cluster、有效EOC、无循环；选中25条链之间没有cross-link。每个完整cluster读取都在实际198,373,376字节镜像内，最远读取结束位置 **48,246,784**。
- 按目录文件size截取后，与旧 `.tooling/bypass-check/reconnect-acquisition/firmware/` 工件和主线程modem-extract工件逐字节相同；25/25 SHA一致，cluster_ranges与原comparison逐项一致。包含此前反汇编所用adsp.b19..b22及MDT/DTB文件。

这里没有以7-Zip全局错误情况下的默认成功输出证明结果，也未再调用它提取最大文件；独立目录/checksum/FAT链、逐字节比较以及95个OTA操作和整分区校验已经提供本次限定文件的验证。原7-Zip错误保存在modem-list.txt，继续列为failed-tool记录。

准确新增结论是：**该包modem分区的25份已列ADSP磁盘文件与旧取证文件完全相同。** 这加强离线反汇编工件的版本来源对应，不证明整个FAT卷完整、其他未列文件可用、现场firmware_mnt文件未变化、ADSP内存正在执行哪份镜像，也不证明Linux控制调用已改变硬件或完成旁路。

几何、每文件chain/file size/hash、modem操作与整分区校验摘要存于 acquisition-review.json 的 `modem_fat_independent_review`。

## 可接受结果与剩余边界

当前vendor的字段映射、范围基准、379项blob/解码目标和整分区hash通过，可以继续对已取得包内HAL进行离线语义分析。现阶段仍需保留：

- 未验证厂家公钥metadata/payload签名，未取得完整8.45GB OTA以核对完整ZIP payload CRC或完整文件摘要；本报告只确认保存范围与manifest的内容一致性。
- 未证明vendor HAL与现场文件相同，也没有验证服务实际运行/加载或其用户接口生效。
- 未把dsp分区或20份相同磁盘驱动等同于现场远端ADSP运行身份。
- 自写脚本仅支持限定完整替换操作；当前工件的独立通过，不等于可以用于任意增量OTA或未审查输入。

原始独审结果、每项操作摘要、范围与输入hash保存于 `.tooling/bypass-check/stage1/root-firmware/acquisition-review.json`。本报告没有扩大到手机控制或安装验证。
