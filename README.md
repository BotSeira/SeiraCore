# Seira

Seira 是一个提供 osu! 成绩查询的 QQ 机器人。
支持生成最好成绩图、最近成绩图、排行榜等，持续更新中...

Seira 依赖 [oStella](https://github.com/ZayrexDev/oStella) 作为上游数据服务。

## 添加机器人

扫描下面的二维码添加机器人↓

<img width="100" alt="Image_1777528636326_363" src="https://github.com/user-attachments/assets/37ca0619-5ace-4168-8265-a47ac2422407" />

## 注意事项

Seira正在活跃开发中，在使用的过程中可能会有一些Bug，也会有维护的情况发生🥹

如果使用过程中遇到了问题，或是有功能建议，欢迎Issue & PR 🥰

另外，由于QQ机器人最近正在进行业务调整，部分功能（如指令列表和视频上传）可能会不稳定或暂时不可用

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

### 查询群友的排行榜！

<img width="400" alt="image" src="https://github.com/user-attachments/assets/cbc361a4-61e0-440d-908e-f1d52009373e" />

### 渲染成绩回放视频！

不论是单人回放...

<img width="400" alt="image" src="https://github.com/user-attachments/assets/cb8b0a95-1e22-4bdf-aff2-ac0309534617" />

还是群友的同屏回放！

<img width="400" alt="image" src="https://github.com/user-attachments/assets/e5f7cea1-d759-4a0e-a8ec-5ad0f0fcc274" />

## 快速开始

### 1) 准备环境

- JDK 25
- Maven
- QQ 机器人应用凭据 或 Napcat服务
- 一个可访问的 oStella API

### 2) 配置 `config.yml`

启动程序时，若不存在`config.yml`则会自动创建。请根据提示编辑 `config.yml`。
默认配置参见[seira-example-config.yml](/src/main/resources/seira-example-config.yml)

### 3) 启动

```shell
mvn -U clean compile exec:java
```

## 常用命令

> 所有命令都以 `/` 开头。

| 命令              | 用法                                      | 结果                    |
|-----------------|-----------------------------------------|-----------------------|
| `/bind`         | `/bind <uid>`                           | 绑定当前用户到 osu uid       |
| `/unbind`       | `/unbind`                               | 解除当前用户的 uid 绑定        |
| `/clearhistory` | `/clearhistory`                         | 清除当前用户在群聊中的记录         |
| `/f`            | `/f`                                    | 获取好友列表                |
| `/fall`         | `/fall`                                 | 获取全部好友列表              |
| `/fclear`       | `/fclear`                               | 清除好友记录                |
| `/bo`           | `/bo [n] [uid/@user]`                   | 最好n个成绩图，无参时获取最佳成绩详情   |
| `/rs`           | `/rs [n] [uid/@user]`                   | 最近n个成绩图，无参时获取最近一个成绩详情 |
| `/m`            | `/m <id/rsN/boN> [Mod]`                 | 获取指定谱面信息              |
| `/s`            | `/s <id/rsN/boN>`                       | 获取指定成绩图               |
| `/sa`           | `/sa <id/rsN/boN>`                      | 获取指定成绩分析图             |
| `/ma`           | `/ma <id/rsN/boN> [n]`                  | 获取指定成绩的Miss分析         |
| `/u`            | `/u <id>`                               | 获取指定用户信息              |
| `/r`            | `/r <id/rsN/boN> [[mm:ss]-[mm:ss]]`     | 生成并发送指定成绩回放视频         |
| `/rsc`          | `/rsc <id/rsN/boN> [+<uid1>,<uid2>...]` | 生成并发送指定成员的成绩同屏回放视频    |
| `/rstat`        | `/rstat [id]`                           | 获取视频生成进度              |
| `/ms`           | `/ms <id/rsN/boN>`                      | 获取指定谱面集信息             |
| `/dl`           | `/dl <id/rsN/boN/mp>`                   | 获取指定谱面集的镜像下载链接        |
| `/sms`          | `/sms <query>`                          | 搜索谱面集                 |
| `/lb`           | `/lb [id] [<uid1>,<uid2>...]`           | 列出指定谱面排行或表现分排行        |
| `/daily`        | `/daily`                                | 每日挑战信息                |
| `/mp`           | `/mp`                                   | 多人房间列表                |
| `/status`       | `/status`                               | 服务状态文本                |
| `/inspect`      | `/inspect`                              | 获取当前上下文信息             |
| `/help`         | `/help`                                 | 显示帮助信息                |

部分指令会先回复“请求已加入队列，预计等待时间 X 秒”，待异步请求完成后再额外发送结果消息。

`/r`和`/rsc`（回放渲染）会先返回“生成请求正在等待中，队列位置：N”，随后返回请求状态，最后在渲染完成后再发送回放视频。

### 快捷查询

对于一些需要指定谱面ID或成绩ID的指令（如 `/m`、`/s`、`/ms` 等），支持快捷查询写法，格式为 `rs5`、`bo3`。

也可以在前面写上玩家ID~~或@~~，例如 `123456 rs5`、~~`@ABC bo3`~~，表示查询指定玩家的最近成绩第 5 条或最好成绩第 3 条。

- `rs5`：使用你已绑定的玩家ID，查询“最近成绩第 5 条”
- `bo3`：使用你已绑定的玩家ID，查询“最好成绩第 3 条”
- `12345 rs1`：使用12345作为玩家ID，查询“最近成绩第 1 条”
- ~~`@ABC rs1`：使用ABC绑定的用户的ID作为玩家ID，查询“最近成绩第 1 条”~~

使用快捷查询前需要先执行 `/bind <玩家ID>`，否则会提示无法使用快捷查询。

> 由于QQ业务调整，暂时无法使用`@用户`查询绑定信息，请改用直接输入uid的方式。
