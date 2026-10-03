# Seira

Seira 是一个提供 osu! 成绩查询的 QQ 机器人。
支持生成最好成绩图、最近成绩图、排行榜等，持续更新中...

Seira 依赖 [oStella](https://github.com/bptSeira/oStella) 作为上游数据服务。

详细使用文档在[这里](https://docs.seira.top/)~

## 添加机器人

扫描下面的二维码添加机器人↓

<img width="100" alt="Image_1777528636326_363" src="https://github.com/user-attachments/assets/37ca0619-5ace-4168-8265-a47ac2422407" />

## 注意事项

Seira正在活跃开发中，在使用的过程中可能会有一些Bug，也会有维护的情况发生🥹

如果使用过程中遇到了问题，或是有功能建议，欢迎Issue & PR 🥰

另外，由于QQ机器人最近正在进行业务调整，部分功能可能会不稳定或暂时不可用

## Seira 能干什么？

### 查询最好成绩！

<img width="400" alt="image" src="https://github.com/user-attachments/assets/465d5ae1-e2b7-4295-ae79-5856bb9c2689" />

<img width="400" alt="image" src="https://github.com/user-attachments/assets/a87afa85-bd55-4e9f-b8e3-8880f39e7bf1" />

### 查询谱面、谱面集、分数信息！

<img width="400" alt="image" src="https://github.com/user-attachments/assets/ee494e4f-18e7-49fb-b7f2-98dbe36b17ef" />

<img width="400" alt="image" src="https://github.com/user-attachments/assets/627de8cf-30e6-4bfb-8459-733aa11f91ae" />

<img width="400" alt="image" src="https://github.com/user-attachments/assets/d8d88e52-f1d3-4d1a-8752-f9845f57a993" />

### 分析成绩！

<img width="400" alt="image" src="https://github.com/user-attachments/assets/024fe6b2-c10a-4c9e-aefc-9c784826a9b7" />

<img width="400" alt="image" src="https://github.com/user-attachments/assets/dd9ab809-b7aa-44b2-a749-8a841781b30c" />

### 玩猜 Rank！

<img width="400"  alt="image" src="https://github.com/user-attachments/assets/c1caf387-381c-442f-99da-5b4257657576" />

### 查询群友的排行榜！

<img width="400" alt="image" src="https://github.com/user-attachments/assets/cbc361a4-61e0-440d-908e-f1d52009373e" />

### 渲染成绩回放视频！

不论是单人回放...

<img width="400" alt="image" src="https://github.com/user-attachments/assets/cb8b0a95-1e22-4bdf-aff2-ac0309534617" />

还是群友的同屏回放！

<img width="400" alt="image" src="https://github.com/user-attachments/assets/e5f7cea1-d759-4a0e-a8ec-5ad0f0fcc274" />

## 常用命令

> 所有命令都以 `/` 开头。指令详情请见[文档](https://docs.seira.top/overview/commands.html)。

| 命令            | 用法                                                    | 结果                                                                            |
|-----------------|---------------------------------------------------------|---------------------------------------------------------------------------------|
| `/bind`         | `/bind`                                                 | 开始osu账号绑定流程                                                             |
| `/unbind`       | `/unbind`                                               | 解除当前用户的osu账号绑定                                                       |
| `/clearhistory` | `/clearhistory`                                         | 清除当前用户在群聊中的记录                                                      |
| `/f`            | `/f`                                                    | 获取好友列表                                                                    |
| `/fall`         | `/fall`                                                 | 获取全部好友列表                                                                |
| `/fclear`       | `/fclear`                                               | 清除好友记录                                                                    |
| `/bp`           | `/bp [n/a-b] [uid/username/@user] [filters...]`         | 获取最多200条最好成绩或指定范围后过滤；省略数量时返回第一个匹配成绩详情         |
| `/rs`           | `/rs [n/a-b] [uid/username/@user] [filters...]`         | 获取最多200条最近成绩或指定范围后过滤；省略数量时返回第一个匹配成绩详情         |
| `/rp`           | `/rp [n/a-b] [uid/username/@user] [filters...]`         | 获取最多200条最近通过成绩或指定范围后过滤；省略数量时返回第一个匹配成绩详情     |
| `/tb`           | `/tb [#days] [uid/username/@user]`                      | 获取近N天达成的BP（默认1天），并保留完整BP排名编号                              |
| `/m`            | `/m <id/rsN/bpN> [Mod]`                                 | 获取指定谱面信息                                                                |
| `/ap`           | `/ap <id/rsN/bpN>`                                      | 获取指定谱面音频预览                                                            |
| `/bgp`          | `/bgp <id/rsN/bpN>`                                     | 获取指定谱面背景预览                                                            |
| `/s`            | `/s <id/locId/rsN/bpN>`                                 | 获取指定的在线或本地成绩图                                                      |
| `/sa`           | `/sa <id/locId/rsN/bpN>`                                | 获取指定成绩分析图                                                              |
| `/ma`           | `/ma [id/locId/rsN/bpN] [n/#n]`                         | 获取指定或最近目标成绩的Miss分析；省略目标时用`#n`指定Miss                      |
| `/snap`         | `/snap [成绩目标] <mm:ss.fff/秒数s/objN/#N> [±偏移ms]` | 使用 WhiteCat 2.1 生成指定时间、物件或 Miss 的完整回放快照；别名 `/snapshot` |
| `/u`            | `/u [uid/username/@user]`                               | 获取指定用户信息                                                                |
| `/@`            | `/@[someone]`                                           | 获取自己或指定用户的文字版信息                                                  |
| `/r`            | `/r [id/locId/rsN/bpN] [[mm:ss]-[mm:ss]]`               | 生成并发送指定或最近目标的回放视频。省略范围时自动识别高光，使用`-`渲染整个回放 |
| `/rg`           | `/rg <start/group/#Rank/end/wish/stats [all]/lb [all]>` | 猜 Rank 游戏及个人战绩查询                                                      |
| `/rsc`          | `/rsc [id/locId/rsN/bpN] [+<id1>,<id2>...]`             | 生成并发送指定或最近目标的成绩同屏回放视频；追加用户和范围顺序不限              |
| `/rstat`        | `/rstat [id]`                                           | 获取视频生成进度                                                                |
| `/rcancel`      | `/rcancel <id>`                                         | 取消排队中、渲染中或上传中的回放任务                                            |
| `/ms`           | `/ms <id/rsN/bpN>`                                      | 获取指定谱面集信息                                                              |
| `/dl`           | `/dl <id/rsN/bpN/mp>`                                   | 获取指定谱面集的镜像下载链接                                                    |
| `/sms`          | `/sms <query>`                                          | 搜索谱面集                                                                      |
| `/lb`           | `/lb [id] [<uid1>,<uid2>...]`                           | 列出指定谱面排行或表现分排行                                                    |
| `/daily`        | `/daily`                                                | 每日挑战信息                                                                    |
| `/roll`         | `/roll [dice]`                                          | 掷骰子                                                                          |
| `/luck`         | `/luck`                                                 | 今日人品                                                                        |
| `/whatif`       | `/whatif <总PPpp 或 #排名>`                              | 估算 osu!standard 总 PP 与全球排名；纯数字默认按排名解析                         |
| `/addpp`        | `/addpp 200*4` 或 `/addpp m谱面ID [条件...]`             | 估算新增成绩后的总 PP、排名和变化；成绩条件顺序不限                              |
| `/rbp`          | `/rbp [uid/username/@user]`                             | 获取随机BP                                                                      |
| `/mp`           | `/mp`                                                   | 多人房间列表                                                                    |
| `/watch`        | `/watch add/del/list [目标]`                            | 添加/删除/列出监视任务                                                          |
| `/challenge`    | `/challenge configure` → `/challenge start`             | 配置并开始群挑战：指定/随机谱面、自由/固定难度、必选/禁用Mod、水平计分             |
| `/mpwatch`      | `/mpwatch start/stop/status [目标]`                     | 按群成员添加、停止或查看多人房间监视；`stop all` 可停止本群全部监视             |
| `/romai`        | `/romai [目标]`                                         | 开始监视自己或目标正在进行的RomAI比赛                                           |
| `/mc`           | `/mc <服务器地址>`                                      | 获取指定MC服务器状态                                                            |
| `/ai`           | `/ai [on/off/reset/reset all]`                          | 开启/关闭/重置本群的AI对话                                                      |
| `/wx`           | `/wx start <UID列表> <谱面ID列表>` / `/wx stop`         | 监视指定玩家在指定谱面取得的成绩，重启后自动恢复                                |
| `/dcs`          | `/dcs start <guild-id>.<channel-id>` / `/dcs stop`      | 开启或解除当前 QQ 群与 Discord 频道的双向消息同步                               |
| `/stat`         | `/stat`                                                 | 服务状态和统计信息文本                                                          |
| `/inspect`      | `/inspect`                                              | 获取当前上下文信息                                                              |
| `/help`         | `/help`                                                 | 显示帮助信息                                                                    |

部分指令会先回复“请求已加入队列，预计等待时间 X 秒”，待异步请求完成后再额外发送结果消息。

`/challenge configure` 显示当前配置和 QQ 按钮，修改后使用 `/challenge start` 开始。
群成员通过 `/challenge join` 报名，成绩自动收集，`/challenge lb` 查看排行。
详细设置、计分方式和部署要求见 [群挑战说明](docs/group-challenges.md)。

`/whatif 12345pp` 估算账号总 PP 对应的全球排名，`/whatif #12345` 或 `/whatif 12345` 估算该排名所需的总 PP。
支持小数 PP 和大小写 `pp`，群聊、私聊均可使用，无需绑定。结果包含参考数据时间，超出样本范围时只给出边界提示。
程序内置真实数据快照，通过现有 oStella `/users` 接口按需在后台每日刷新并持久化；刷新失败仍可使用旧快照。
数据来源、拟合方法和验证结果见 [whatif 说明](docs/whatif.md)。

`/addpp 123` 或 `/addpp 200*4` 估算新增成绩后的加权总 PP 和排名变化；
`/addpp m1234567 HDDT 97.41% 13miss 18ok 1200x` 先计算谱面成绩的 PP。条件顺序不限，有 Miss 时需给 Combo。
默认使用自己的绑定账号。完整条件与估算范围见 [addpp 说明](docs/addpp.md)，需同步更新 oStella。

`/r`和`/rsc`（回放渲染）会先返回“生成请求正在等待中，队列位置：N”，随后返回请求状态，最后在渲染完成后再发送回放视频。

`/snap rp1 01:23.456` 按谱面歌曲时间生成快照；`/snap obj123` 定位最近目标的第 123 个物件；
`/snap #3 -50ms` 定位第 3 个 Miss 判定前 50ms。物件和 Miss 从 1 开始编号，Miss 与 `/ma` 的列表一致；
偏移最多 ±60000ms。时间也可写 `83.456s` 或 `83456ms`，DT/HT 下仍使用原谱面时间。
画面包含皮肤物件与判定、滑条进度、鼠标及轨迹、按键、判定条、歌曲进度、实时 PP 和准确率。
只支持 osu!standard；画面采用固定 1920×1080 配置。需同时更新 oStella 后端，详情见 [回放快照说明](docs/replay-snapshot.md)。

上传 osu! 服务器上不存在的 `.osr` 回放后，机器人会返回形如 `loc123456789` 的本地成绩ID；该ID可用于 `/s`、`/sa`、`/ma`、`/snap`、`/r`、
`/rsc` 等成绩目标指令。

### 快捷查询

对于一些需要指定谱面ID或成绩ID的指令（如 `/m`、`/s`、`/ms` 等），支持快捷查询写法，格式为 `rs5`、`bp3`、`rp2`。

快捷查询也可以直接作为指令使用，并在后面指定玩家，例如 `/bp5 @用户`；紧凑写法同样支持列表范围，例如 `/bp21-30`、
`/rp6-10 @用户`。

这些指令会共享最近一次显式指定的查询目标。已有最近目标时，`/r`、`/rsc`、`/ma`、`/snap` 可以省略目标，例如 `/r 01:00-01:30`、
`/rsc +12345,67890 -`、`/ma #2`、`/snap obj123`。

也可以在前面写上玩家ID、用户名或@用户，例如 `123456 rs5`、`peppy bp3`、`@ABC bp3`，表示查询指定玩家的最近成绩第 5 条或最好成绩第
3 条。

- `rs5`：使用你已绑定的玩家ID，查询“最近成绩第 5 条”
- `rp1`：使用你已绑定的玩家ID，查询“最近通过成绩第 1 条”
- `bp3`：使用你已绑定的玩家ID，查询“最好成绩第 3 条”
- `12345 rs1`：使用12345作为玩家ID，查询“最近成绩第 1 条”
- `peppy rs1`：先按用户名查找玩家ID，再查询“最近成绩第 1 条”
- `@ABC rs1`：使用ABC绑定的用户的ID作为玩家ID，查询“最近成绩第 1 条”

省略玩家目标时，使用快捷查询前需要先执行 `/bind`，否则会提示无法使用快捷查询。

### 回放上传

你可以在私聊中直接发送你的回放文件，Seira会将其转存。
这样就可以对未上传到osu!服务器的回放进行分析了。

## 调试命令

> [!WARNING]
>
> **警告：危险区域！**
>
> 以下命令仅用于调试，使用不当可能会造成数据丢失、账号封禁等后果，且仅在调试模式启用且发送者为bpt管理员的情况下可用。
>
> 这些指令可能会随时进行添加、修改或删除，且不保证向后兼容。

| 命令                               | 结果                                   |
|------------------------------------|----------------------------------------|
| `/debug.upload <type> <cos> <url>` | 上传指定文件                           |
| `/debug.test`                      | 发送一段测试文本                       |
| `/debug.message <base64>`          | 将指定Base64解码并作为Markdown消息发出 |
| `/debug.db <sql>`                  | 对后端数据库执行指定的SQL语句          |
| `/debug.update-user-info`          | 更新数据库中所有玩家的信息             |
| `/debug.active-message`            | 发送一条主动消息                       |

## 自行部署

> [!NOTE]
> 此节仅面向想自行部署 Seira 的用户。
> 若想直接使用，请见[使用指南](https://docs.seira.top/overview/use.html)

### 1) 准备环境

- JDK 25
- Maven
- QQ 开放平台机器人应用凭据
- 一个可访问的 oStella API

### 2) 配置 `config.yml`

启动程序时，若不存在`config.yml`则会自动创建。请根据提示编辑 `config.yml`。
默认配置参见[seira-example-config.yml](/src/main/resources/seira-example-config.yml)

### 3) 启动

```shell
mvn -U clean compile exec:java
```
