# Seira

Seira 是一个提供 osu! 成绩查询的 QQ 机器人。
支持生成最好成绩图、最近成绩图、排行榜等，持续更新中...

Seira 依赖 [oStella](https://github.com/ZayrexDev/oStella) 作为上游数据服务。

## Seira 能干什么？

### 绑定/解绑 osu 账号！

<img width="400" alt="image" src="https://github.com/user-attachments/assets/eccd7599-7476-424e-a53f-de1929a31963" />

### 查询最好成绩！

<img width="400" alt="image" src="https://github.com/user-attachments/assets/465d5ae1-e2b7-4295-ae79-5856bb9c2689" />

<img width="400" alt="image" src="https://github.com/user-attachments/assets/a87afa85-bd55-4e9f-b8e3-8880f39e7bf1" />

### 查询铺面、铺面集、分数信息！

<img width="400" alt="image" src="https://github.com/user-attachments/assets/ee494e4f-18e7-49fb-b7f2-98dbe36b17ef" />

<img width="400" alt="image" src="https://github.com/user-attachments/assets/627de8cf-30e6-4bfb-8459-733aa11f91ae" />

<img width="400" alt="image" src="https://github.com/user-attachments/assets/d8d88e52-f1d3-4d1a-8752-f9845f57a993" />

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

| 命令        | 用法                                      | 结果                    |
|-----------|-----------------------------------------|-----------------------|
| `/bind`   | `/bind <uid>`                           | 绑定当前用户到 osu uid       |
| `/unbind` | `/unbind`                               | 解除当前用户的 uid 绑定        |
| `/bo`     | `/bo [n] [uid/@user]`                   | 最好n个成绩图，无参时获取最佳成绩详情   |
| `/rs`     | `/rs [n] [uid/@user]`                   | 最近n个成绩图，无参时获取最近一个成绩详情 |
| `/m`      | `/m <id/rsN/boN> [Mod]`                 | 获取指定铺面信息              |
| `/s`      | `/s <id/rsN/boN>`                       | 获取指定成绩图               |
| `/r`      | `/r <id/rsN/boN>`                       | 生成并发送指定成绩回放视频         |
| `/rsc`    | `/rsc <id/rsN/boN> [+<uid1>,<uid2>...]` | 生成并发送指定成员的成绩同屏回放视频    |
| `/rstat`  | `/rstat [id]`                           | 获取视频生成进度              |
| `/ms`     | `/ms <id/rsN/boN>`                      | 获取指定铺面集信息             |
| `/sms`    | `/sms <query>`                          | 搜索铺面集                 |
| `/lb`     | `/lb [id] [<uid1>,<uid2>...]`           | 列出指定铺面排行或表现分排行        |
| `/daily`  | `/daily`                                | 每日挑战信息                |
| `/mp`     | `/mp`                                   | 多人房间列表                |
| `/status` | `/status`                               | 服务状态文本                |
| `/help`   | `/help`                                 | 显示帮助信息                |

绑定后可省略 uid：`/bo`、`/rs`。
`/bo`、`/rs` 的 uid 参数支持 `@用户`，会自动使用被@用户绑定的 osu uid（例如 `/bo 5 @123456`）。
在群聊中，`/lb <bm>` 会默认使用该群里已绑定过的所有玩家 uid；私聊中 `/lb <bm>` 使用你自己的绑定 uid。
另外：`/lb` 不带参数时，会基于默认绑定 uid 生成总表现分排行榜（群聊=本群绑定用户，私聊=你自己的绑定 uid）。

其中会部分指令（如 `/bo`、`/rs`、`/m`、`/s`、`/ms`、`/sms`、`/lb`、`/daily`、`/mp`）会先回复“请求已加入队列，预计等待时间 X 秒”，待异步请求完成后再额外发送结果消息。

`/r`和`/rsc`（回放渲染）会先返回“生成请求正在等待中，队列位置：N”，随后返回请求状态，最后在渲染完成后再发送回放视频。

### 快捷查询

对于一些需要指定铺面ID或成绩ID的指令（如 `/m`、`/s`、`/ms` 等），支持快捷查询写法，格式为 `rs5`、`bo3`。

- `rs5`：使用你已绑定的玩家ID，查询“最近成绩第 5 条”
- `bo3`：使用你已绑定的玩家ID，查询“最好成绩第 3 条”

使用快捷查询前需要先执行 `/bind <玩家ID>`，否则会提示无法使用快捷查询。
