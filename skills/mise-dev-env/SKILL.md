---
name: mise-dev-env
description: 用 mise 搭建并驱动多项目工作区的开发环境。探测技术栈（Java/Maven、Gradle、Node、Python、Go、Rust）生成 mise.toml，装齐工具链，按依赖顺序启动多个项目，改完代码重启并用健康检查验证。适用于：启动项目 / 停止 / 重启 / run / start / stop / restart / 健康检查 / 搭开发环境 / 装依赖 / 编译 / build / 看日志 / 排查启动失败 / 全栈工作区 / mise。
---

# mise-dev-env

多项目工作区的开发环境驱动。**所有操作走 `ops/devctl.mjs`，不要手敲 `mvn` / `npm run dev`**
（PATH 上的工具版本大概率不是 mise 装的那个，见 Gotchas）。

驱动是 Node 单文件、零依赖、跨平台。前置只需 mise：
`winget install jdx.mise` / `brew install mise` / `curl https://mise.run | sh`

---

## 流程

### A. 接入工作区（一次性）

```bash
node ~/.claude/skills/mise-dev-env/devctl.mjs detect --path <工作区>   # 先看探测结果
node ~/.claude/skills/mise-dev-env/devctl.mjs init   --path <工作区>   # 确认后落盘
cd <工作区>
mise install                    # 按 mise.toml 装齐 jdk/node/python...
node ops/devctl.mjs build
node ops/devctl.mjs start
node ops/devctl.mjs health      # 退出码 0 = 接入成功
```

`init` 会写 `mise.toml`、`ops/workspace.json`，并把驱动复制进 `ops/`，
之后一律用 `node ops/devctl.mjs`（工作区自包含，同事 clone 下来即可用）。

### B. 日常闭环（改完代码）

```
改代码 → restart → health → 退出码?
                              ├─ 0  ✅ 完成
                              └─ 1  错误行已打印 → 修 → 回到 restart
```

```bash
node ops/devctl.mjs restart --unit <项目名>   # 只重启改动的那个，别全量
node ops/devctl.mjs health                    # 退出码即结论，不要肉眼看日志
```

`health` 三项全过才返回 0：**进程活着** + **端口通且 HTTP 有响应** + **日志无报错**。
失败时直接打印日志里的错误行，不必再去 `logs`：

```
  [OK]   api                      进程:OK   端口7801  OK   HTTP 200
  [FAIL] web                      进程:--   端口7802  --   端口未监听
  [WARN]   web 日志中的错误:
    │ Error: Cannot find module './this-module-does-not-exist'

  [FAIL] 存在问题（退出码 1）
```

`start`/`restart` 在等待就绪期间就会发现进程崩溃，不用干等超时。

### 执行约定

1. 动手前先 `status`，别重复启动已在跑的服务
2. 改完代码必须 `restart` + `health`，以退出码为准
3. 只重启受影响的项目（`--unit`）
4. 端口/启动命令不对，改 `ops/workspace.json`，不要改驱动

---

## 命令

| 命令 | 作用 |
|---|---|
| `detect` | 探测有哪些项目，只打印不落盘 |
| `init` | 写 `ops/workspace.json` + `mise.toml`，复制驱动到 `ops/` |
| `install` | `mise install` |
| `build` | 编译（Node 项目会先自动装依赖） |
| `start` | 按依赖顺序启动，等端口就绪 |
| `health` | 进程 + 端口 + HTTP + 日志扫错，**退出码 0/1** |
| `restart` / `stop` / `status` / `logs` | 顾名思义 |
| `doctor` | 诊断 mise / PATH / 工具链解析 |

选项：`-p/--path <目录>`、`-u/--unit <项目名>`、`-n/--lines <行数>`

---

## 探测不准就改 ops/workspace.json

探测只给初始值。端口、启动命令、依赖全在这个文件里，改完直接生效；
重跑 `init` 会**保留**你的修改，只补新发现的项目。

```json
{
  "name": "api",
  "type": "node",
  "dir": "api",
  "tools": { "node": "12.22.12" },
  "port": 7801,
  "healthPath": "/",
  "dependsOn": ["db"],
  "buildCmd": ["npm", "run", "build"],
  "startCmd": ["npm", "run", "dev"],
  "startTimeoutMs": 180000
}
```

`dependsOn` 决定启动顺序：被依赖的先起并等端口就绪；前置失败则跳过依赖它的项目。

---

## 指定 Maven settings.xml 等构建参数

用 mise 的 `[env]` 注入，路径用 `{{config_root}}` 跟着仓库走：

```toml
[env]
MAVEN_ARGS = "-s {{config_root}}/ops/settings.xml"
```

实测 `mvn -X` 输出确认生效：

```
[DEBUG] Reading user settings from ...\ops\settings.xml
[DEBUG] Using local repository at D:\tmp\repo-from-project-settings
```

前提两条：**`MAVEN_ARGS` 需要 Maven ≥ 3.9**；**用了 `[env]` 必须先 `mise trust`**（见下）。
Maven < 3.9 或要兼容 IDE 的 Maven 面板，改用项目根的 `.mvn/maven.config`（一行一个参数）。

## Gotchas

**`[env]` / 模板语法要求 `mise trust`，否则整个配置文件不加载。**
报错写的是「parse 失败」，极易误判成 TOML 语法错：

```
mise ERROR error parsing config file: ...\mise.toml
mise ERROR Config files in ...\mise.toml are not trusted.
Trust them with `mise trust`.
```

在工作区根执行一次 `mise trust` 即可，之后长期有效；团队成员 clone 后需各自执行一次。

**`mise run start` 会挂死** —— mise 等整个进程树退出，而 `start` 故意留常驻服务。
所以生成的 `mise.toml` 里只有会结束的任务（`install/build/stop/health/status/logs/doctor`），
没有 `start`/`restart`。启动一律 `node ops/devctl.mjs start`。
（`mise run health` 正常，非零退出码会被传播成 `ERROR task failed`。）

**mise 的路径被追加在 PATH 末尾，系统预装的工具排前面会赢。** 实测同一台机器 3 个 JDK：

```
java   裸PATH: C:\Program Files\Java\jdk1.8.0_202\bin\java.exe
       mise  : openjdk version "1.8.0_502"
```

所以驱动从不用裸 `java`/`mvn`/`node`，一律 `mise exec --` 或 `mise where`。你手敲时同理。

**TOML 双引号里的 Windows 路径会炸配置** —— `C:\Users` 的 `\U` 是非法 unicode 转义，
mise 会拒绝加载**整个**文件（`too few unicode value digits`）。生成的 `run` 一律用字面量字符串。

**后台进程 Windows 上也必须 `detached`** —— 否则 devctl 一退出服务被带走，
表现为「start 说成功，几秒后 health 说端口没监听」。

**JSON 可能带 UTF-8 BOM** —— PowerShell 的 `Set-Content -Encoding UTF8` 会写 BOM，
`JSON.parse` 直接崩、表现为「未找到 workspace.json」。驱动读时剥 BOM。

**嵌套 package.json 不是项目** —— `uni_modules/`、`nativeplugins/`、`packages/` 里的是被依赖的包，
驱动会跳过，并丢弃嵌套在其他项目内部的单元。

**没有 dev 脚本的项目标为 `node-nostart`** —— 典型是 HBuilderX 的 uni-app 工程，命令行确实起不动。
驱动识别但 `start`/`health` 跳过并说明原因。

---

## Troubleshooting

| 现象 | 处理 |
|---|---|
| `未找到 ops/workspace.json` | 没跑 `init`；或文件带 BOM / JSON 语法错 |
| `target 下没有 jar，先运行 build` | Maven 项目要先 `build`，驱动优先跑 fat jar（PID 干净好停） |
| `mise 里没有 java，先运行 install` | `mise install` 没跑，或 `mise.toml` 没声明该工具 |
| **等待就绪超时、进程却还活着** | 九成是 `workspace.json` 里 `port` 配错。驱动会直接提示你查日志确认真实端口 |
| `start` 成功但 `health` 说端口没监听 | `logs -u <项目>` 看；常见 `EADDRINUSE` 或依赖没起 |
| `stop` 说停了进程还在 | Windows 的 `taskkill /T /F` 有几秒延迟，等等再 `status` |
| 探测漏掉/多认项目 | 改 `ops/workspace.json`，重跑 `init` 会保留修改 |
