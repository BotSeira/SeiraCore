# Seira

Seira 是一个提供 osu! 成绩查询的 QQ 机器人。
支持生成最好成绩图、最近成绩图、排行榜等，持续更新中...

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

<img width="400" alt="a0b5d1a746b7a6871b7b36a6fe299f71" src="https://github.com/user-attachments/assets/7b66c432-5980-4fc7-924a-ca6db7b61242" />

### 查询谱面、谱面集、分数信息！

<img width="400" alt="c7f53e4ef0badac2e926c49c61c1fa39" src="https://github.com/user-attachments/assets/5c5e2f77-c646-4b3f-9da1-abcba3a6baa2" />

<img width="400" alt="ca3d8f97cb5328dfe915fcb9480ded71" src="https://github.com/user-attachments/assets/84e4910e-bd03-4ad6-984c-18f3499becae" />

### 分析成绩！

<img width="400" alt="9a62229f5e0214e5b69c013ad67c7bee" src="https://github.com/user-attachments/assets/1c5ff0e9-a72a-425e-a791-bbf9ed89c47c" />

<img width="400" alt="b84e318656844ce028e63b264940f2f2" src="https://github.com/user-attachments/assets/fb6ccf5a-ad21-4afb-8588-05bf4b5429c2" />

### 玩猜 Rank！

<img width="400" alt="image" src="https://github.com/user-attachments/assets/85e57e04-ae75-4dff-9182-5d96a6eb8695" />

### 渲染成绩回放视频！

<img width="400" alt="image" src="https://github.com/user-attachments/assets/cb8b0a95-1e22-4bdf-aff2-ac0309534617" />

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
| `/snap`         | `/snap [成绩目标] <mm:ss.fff/秒数s/objN/#N> [±偏移ms]`  | 生成指定时间、物件或 Miss 的完整回放快照；别名 `/snapshot`                      |
| `/u`            | `/u [uid/username/@user]`                               | 获取指定用户信息                                                                |
| `/@`            | `/@[someone]`                                           | 获取自己或指定用户的文字版信息                                                  |
| `/r`            | `/r [id/locId/rsN/bpN] [[mm:ss]-[mm:ss]]`               | 生成并发送指定或最近目标的回放视频。省略范围时自动识别高光，使用`-`渲染整个回放 |
| `/rg`           | `/rg <start/group/#Rank/end/wish/stats [all]/lb [all]>` | 猜 Rank 游戏及个人战绩查询                                                      |
| `/rsc`          | `/rsc [id/locId/rsN/bpN] [+<id1>,<id2>...]`             | 生成并发送指定或最近目标的成绩同屏回放视频；追加用户和范围顺序不限              |
| `/rstat`        | `/rstat [id]`                                           | 获取视频生成进度                                                                |
| `/rget`         | `/rget [id]`                                            | 获取视频                                                                        |
| `/rcancel`      | `/rcancel <id>`                                         | 取消排队中、渲染中或上传中的回放任务                                            |
| `/ms`           | `/ms <id/rsN/bpN>`                                      | 获取指定谱面集信息                                                              |
| `/dl`           | `/dl <id/rsN/bpN/mp>`                                   | 获取指定谱面集的镜像下载链接                                                    |
| `/sms`          | `/sms <query>`                                          | 搜索谱面集                                                                      |
| `/lb`           | `/lb [id] [<uid1>,<uid2>...]`                           | 列出指定谱面排行或表现分排行                                                    |
| `/daily`        | `/daily`                                                | 每日挑战信息                                                                    |
| `/roll`         | `/roll [dice]`                                          | 掷骰子                                                                          |
| `/luck`         | `/luck`                                                 | 今日人品                                                                        |
| `/whatif`       | `/whatif <总PPpp 或 #排名>`                             | 估算 osu!standard 总 PP 与全球排名；纯数字默认按排名解析                        |
| `/addpp`        | `/addpp 200*4` 或 `/addpp m谱面ID [条件...]`            | 估算新增成绩后的总 PP、排名和变化；成绩条件顺序不限                             |
| `/rbp`          | `/rbp [uid/username/@user]`                             | 获取随机BP                                                                      |
| `/mp`           | `/mp`                                                   | 多人房间列表                                                                    |
| `/watch`        | `/watch add/del/list [目标]`                            | 添加/删除/列出监视任务                                                          |
| `/challenge`    | `/challenge configure` → `/challenge start`             | 配置并开始群挑战：指定/随机谱面、自由/固定难度、必选/禁用Mod、水平计分          |
| `/mpwatch`      | `/mpwatch start/stop/status [目标]`                     | 按群成员添加、停止或查看多人房间监视；`stop all` 可停止本群全部监视             |
| `/romai`        | `/romai [目标]`                                         | 开始监视自己或目标正在进行的RomAI比赛                                           |
| `/mc`           | `/mc <服务器地址>`                                      | 获取指定MC服务器状态                                                            |
| `/ai`           | `/ai [on/off/reset/reset all]`                          | 开启/关闭/重置本群的AI对话                                                      |
| `/wx`           | `/wx start <UID列表> <谱面ID列表>` / `/wx stop`         | 监视指定玩家在指定谱面取得的成绩，重启后自动恢复                                |
| `/dcs`          | `/dcs start <guild-id>.<channel-id>` / `/dcs stop`      | 开启或解除当前 QQ 群与 Discord 频道的双向消息同步                               |
| `/stat`         | `/stat`                                                 | 服务状态和统计信息文本                                                          |
| `/inspect`      | `/inspect`                                              | 获取当前上下文信息                                                              |
| `/help`         | `/help`                                                 | 显示帮助信息                                                                    |

### 快捷查询

`/s`、`/sa`、`/ma`、`/snap`（`/snapshot`）和 `/r` 支持 `[@用户] m谱面ID` 作为成绩目标，例如 `/sa @用户 m123456`、`/ma m123456 #2`、`/snap @用户 m123456 01:23.456`。玩家可用 UID、用户名或 @用户，省略时查询自己在该谱面上的成绩；各指令后续参数保持原有含义。`/sm` 保持原有语法。

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
