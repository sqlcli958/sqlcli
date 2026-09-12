#!/usr/bin/env node
/**
 * devctl.mjs —— 基于 mise 的全栈工作区开发环境驱动器
 *
 * 面向「一个工作区里并列多个项目」的全栈敏捷开发场景:
 *   workspace/
 *     ├── ops/         管理脚本 + workspace.json
 *     ├── backend/     ← 各子项目
 *     └── frontend/
 *
 * 闭环:  detect → init → install → build → start → health → (改代码) → restart → health
 *
 * 零依赖: 只用 Node 内置模块。跨平台: Windows / macOS / Linux。
 * 运行:   node ops/devctl.mjs <命令> [选项]
 *         没装 node 也能跑: mise x node@lts -- node ops/devctl.mjs <命令>
 */

import fs from 'node:fs'
import path from 'node:path'
import net from 'node:net'
import http from 'node:http'
import os from 'node:os'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const SELF = fileURLToPath(import.meta.url)
const IS_WIN = process.platform === 'win32'

// 探测时永远不进的目录
const SKIP_DIRS = new Set([
  'node_modules', 'target', 'dist', 'build', 'out', '.git', '.idea', '.vscode',
  '.devctl', 'unpackage', 'vendor', '__pycache__', '.venv', 'venv', '.next',
  'coverage', '.gradle', '.mvn', 'ops', 'docs', '.toolchain', 'logs',
  // 组件/插件目录: 里面的 package.json 是被依赖的包, 不是可启动项目
  'uni_modules', 'nativeplugins', 'wxcomponents', 'hybrid', 'components',
  'packages', 'plugins', 'examples', 'example', 'demo', 'test', 'tests',
])

// ───────────────────────────────────────────────────────────── 输出

const C = process.stdout.isTTY
  ? { r: '\x1b[0m', dim: '\x1b[2m', red: '\x1b[31m', grn: '\x1b[32m', yel: '\x1b[33m', cyn: '\x1b[36m' }
  : { r: '', dim: '', red: '', grn: '', yel: '', cyn: '' }

const say = (m) => console.log(`  ${m}`)
const ok = (m) => console.log(`  ${C.grn}[OK]${C.r}   ${m}`)
const warn = (m) => console.log(`  ${C.yel}[WARN]${C.r} ${m}`)
const bad = (m) => console.log(`  ${C.red}[FAIL]${C.r} ${m}`)
const head = (m) => console.log(`\n${C.cyn}== ${m}${C.r}`)

// ───────────────────────────────────────────────────────────── 基础工具

const exists = (p) => { try { fs.accessSync(p); return true } catch { return false } }
// 去 BOM: Windows 上的编辑器和 PowerShell 的 Set-Content -Encoding UTF8 都会写 UTF-8 BOM,
// JSON.parse 遇到 BOM 会直接抛错。
const stripBom = (s) => (s.charCodeAt(0) === 0xfeff ? s.slice(1) : s)
const readText = (p) => { try { return stripBom(fs.readFileSync(p, 'utf8')) } catch { return '' } }
const readJson = (p) => { try { return JSON.parse(readText(p)) } catch { return null } }
const ensureDir = (p) => fs.mkdirSync(p, { recursive: true })

/**
 * 同步执行命令, 返回 {code, out}
 * 不开 shell: 外部命令一律是真正的可执行文件 (mise / git / taskkill),
 * 包管理器那类 .cmd 全部包在 `mise exec -- npm ...` 里, 外层始终是 mise.exe。
 * Node 24 对 shell:true + 参数数组会报 DEP0190 弃用告警, 也不安全。
 */
function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, {
    cwd: opts.cwd || process.cwd(),
    encoding: 'utf8',
    windowsHide: true,
    ...opts,
  })
  const out = `${r.stdout || ''}${r.stderr || ''}`
    .split('\n')
    // JAVA_TOOL_OPTIONS 之类的全局设置会让每次 java 调用都往 stderr 吐一行, 属噪音
    .filter((l) => !/Picked up (JAVA_TOOL_OPTIONS|_JAVA_OPTIONS)/.test(l))
    .join('\n')
  return { code: r.status === null ? 1 : r.status, out: out.trim() }
}

/** 递归收集文件名匹配的路径, 带深度上限 */
function walk(root, matcher, maxDepth = 3, depth = 0, acc = []) {
  if (depth > maxDepth) return acc
  let entries
  try { entries = fs.readdirSync(root, { withFileTypes: true }) } catch { return acc }
  for (const e of entries) {
    const full = path.join(root, e.name)
    if (e.isDirectory()) {
      if (SKIP_DIRS.has(e.name) || e.name.startsWith('.')) continue
      walk(full, matcher, maxDepth, depth + 1, acc)
    } else if (matcher(e.name)) {
      acc.push(full)
    }
  }
  return acc
}

// ───────────────────────────────────────────────────────────── mise 封装

function requireMise() {
  const r = run('mise', ['--version'])
  if (r.code !== 0) {
    bad('未找到 mise。安装方式:')
    say('  Windows : winget install jdx.mise')
    say('  macOS   : brew install mise')
    say('  Linux   : curl https://mise.run | sh')
    process.exit(1)
  }
  return r.out.split('\n')[0]
}

/**
 * 拿工具的安装目录。
 * 为什么不用裸 PATH 上的 java/node —— mise 把自己的路径【追加】在 PATH 末尾,
 * 系统里预装的 JDK/Maven 排在前面会直接赢掉, 于是 `java` 解析到的根本不是 mise 装的那个。
 * 一律 mise where / mise exec。
 */
function toolPath(dir, tool) {
  const r = run('mise', ['where', tool], { cwd: dir })
  if (r.code !== 0) return null
  const line = r.out.split('\n').map((s) => s.trim()).filter(Boolean)[0]
  return line || null
}

// ───────────────────────────────────────────────────────────── 版本推导

function javaVersionFrom(pomText) {
  for (const re of [
    /<java\.version>\s*([\d.]+)\s*<\/java\.version>/,
    /<maven\.compiler\.source>\s*([\d.]+)\s*<\/maven\.compiler\.source>/,
    /<maven\.compiler\.release>\s*([\d.]+)\s*<\/maven\.compiler\.release>/,
    /<source>\s*([\d.]+)\s*<\/source>/,
  ]) {
    const m = pomText.match(re)
    if (m) return `temurin-${m[1].replace(/^1\./, '').split('.')[0]}`
  }
  return 'temurin-17'
}

function javaVersionFromGradle(text) {
  const m = text.match(/(?:sourceCompatibility|targetCompatibility|JavaLanguageVersion\.of)\D*([\d.]+)/)
  if (m) return `temurin-${m[1].replace(/^1\./, '').split('.')[0]}`
  return 'temurin-17'
}

function nodeVersionFor(dir) {
  // 已有的 mise/asdf 配置优先 —— 那是人工定过的, 不该被探测覆盖
  for (const f of ['mise.toml', '.mise.toml', '.tool-versions']) {
    const t = readText(path.join(dir, f))
    const m = t.match(/^\s*node\s*[=" ]\s*"?([\d.]+|lts)"?/m)
    if (m) return m[1]
  }
  const nvmrc = readText(path.join(dir, '.nvmrc')).trim().replace(/^v/, '')
  if (nvmrc) return nvmrc
  const pkg = readJson(path.join(dir, 'package.json'))
  const eng = pkg?.engines?.node
  if (eng) {
    const m = eng.match(/(\d+(?:\.\d+)*)/)
    if (m) return m[1]
  }
  return 'lts'
}

function pythonVersionFor(dir) {
  const t = readText(path.join(dir, 'pyproject.toml'))
  const m = t.match(/requires-python\s*=\s*"[^\d]*(\d+\.\d+)/)
  if (m) return m[1]
  const pv = readText(path.join(dir, '.python-version')).trim()
  if (pv) return pv
  return '3.12'
}

// ───────────────────────────────────────────────────────────── 端口推导

function springPort(dir) {
  const files = walk(dir, (n) => /^application(-\w+)?\.(ya?ml|properties)$/.test(n), 5)
  for (const f of files) {
    const t = readText(f)
    // server.port 在 yml 里是 server: \n  port: N
    const yml = t.match(/^server:\s*$[\s\S]{0,400}?^\s{2,}port:\s*(\d+)/m)
    if (yml) return Number(yml[1])
    const prop = t.match(/^\s*server\.port\s*=\s*(\d+)/m)
    if (prop) return Number(prop[1])
  }
  return 8080
}

function nodePort(dir) {
  for (const cfg of ['vue.config.js', 'vite.config.js', 'vite.config.ts', 'nuxt.config.js', 'next.config.js']) {
    const t = readText(path.join(dir, cfg))
    if (!t) continue
    const m = t.match(/port\s*:\s*(\d+)/)
    if (m) return Number(m[1])
  }
  if (exists(path.join(dir, 'vue.config.js'))) return 8080
  if (exists(path.join(dir, 'vite.config.js')) || exists(path.join(dir, 'vite.config.ts'))) return 5173
  return 3000
}

// ───────────────────────────────────────────────────────────── 包管理器

function nodePM(dir) {
  if (exists(path.join(dir, 'pnpm-lock.yaml'))) return 'pnpm'
  if (exists(path.join(dir, 'yarn.lock'))) return 'yarn'
  return 'npm'
}

function pythonPM(dir) {
  if (exists(path.join(dir, 'uv.lock'))) return 'uv'
  if (exists(path.join(dir, 'poetry.lock'))) return 'poetry'
  return 'pip'
}

// ───────────────────────────────────────────────────────────── 探测

function detectUnits(root) {
  const units = []
  const claimed = new Set()

  // ---- Maven: 只认最外层 pom (聚合工程根), 子模块不算独立单元
  const poms = walk(root, (n) => n === 'pom.xml', 3)
  const rootPoms = poms.filter(
    (p) => !poms.some((q) => q !== p && path.dirname(p).startsWith(path.dirname(q) + path.sep))
  )
  for (const p of rootPoms) {
    const dir = path.dirname(p)
    claimed.add(dir)
    units.push({
      name: path.basename(dir),
      type: 'maven',
      dir: path.relative(root, dir) || '.',
      tools: { java: javaVersionFrom(readText(p)), maven: '3.9.9' },
      port: springPort(dir),
      healthPath: '/',
      dependsOn: [],
      buildCmd: ['mvn', '-q', '-DskipTests', 'package'],
      startCmd: null,          // maven 走 fat jar, 启动时动态找
    })
  }

  // ---- Gradle
  for (const g of walk(root, (n) => n === 'build.gradle' || n === 'build.gradle.kts', 3)) {
    const dir = path.dirname(g)
    if (claimed.has(dir)) continue
    claimed.add(dir)
    const wrapper = exists(path.join(dir, IS_WIN ? 'gradlew.bat' : 'gradlew'))
    units.push({
      name: path.basename(dir),
      type: 'gradle',
      dir: path.relative(root, dir) || '.',
      tools: { java: javaVersionFromGradle(readText(g)) },
      port: springPort(dir),
      healthPath: '/',
      dependsOn: [],
      buildCmd: [wrapper ? (IS_WIN ? 'gradlew.bat' : './gradlew') : 'gradle', 'build', '-x', 'test'],
      startCmd: [wrapper ? (IS_WIN ? 'gradlew.bat' : './gradlew') : 'gradle', 'bootRun'],
    })
  }

  // ---- Node
  for (const pkgPath of walk(root, (n) => n === 'package.json', 3)) {
    const dir = path.dirname(pkgPath)
    if (claimed.has(dir)) continue
    const pkg = readJson(pkgPath)
    if (!pkg) continue
    claimed.add(dir)

    const scripts = pkg.scripts || {}
    const devScript = ['dev', 'serve', 'start', 'dev:h5'].find((s) => scripts[s])
    const pm = nodePM(dir)

    if (!devScript) {
      // 例: HBuilderX 的 uni-app 工程, 只有依赖没有脚本, 命令行起不来
      units.push({
        name: path.basename(dir),
        type: 'node-nostart',
        dir: path.relative(root, dir) || '.',
        tools: { node: nodeVersionFor(dir) },
        port: 0, healthPath: '', dependsOn: [],
        buildCmd: null, startCmd: null,
        note: 'package.json 里没有可用的 dev 脚本, 无法从命令行启动 (多为 IDE 工程, 如 HBuilderX)',
      })
      continue
    }

    units.push({
      name: path.basename(dir),
      type: 'node',
      dir: path.relative(root, dir) || '.',
      tools: { node: nodeVersionFor(dir) },
      port: nodePort(dir),
      healthPath: '/',
      dependsOn: [],
      buildCmd: scripts.build ? [pm, 'run', 'build'] : null,
      startCmd: [pm, 'run', devScript],
    })
  }

  // ---- Python
  for (const f of walk(root, (n) => n === 'pyproject.toml' || n === 'requirements.txt', 3)) {
    const dir = path.dirname(f)
    if (claimed.has(dir)) continue
    claimed.add(dir)
    const pm = pythonPM(dir)
    let startCmd = ['python', 'main.py']
    if (exists(path.join(dir, 'manage.py'))) startCmd = ['python', 'manage.py', 'runserver']
    else if (exists(path.join(dir, 'app.py'))) startCmd = ['python', 'app.py']
    units.push({
      name: path.basename(dir),
      type: 'python',
      dir: path.relative(root, dir) || '.',
      tools: { python: pythonVersionFor(dir) },
      port: 8000, healthPath: '/', dependsOn: [],
      buildCmd: pm === 'uv' ? ['uv', 'sync'] : pm === 'poetry' ? ['poetry', 'install'] : ['pip', 'install', '-r', 'requirements.txt'],
      startCmd,
    })
  }

  // ---- Go
  for (const f of walk(root, (n) => n === 'go.mod', 3)) {
    const dir = path.dirname(f)
    if (claimed.has(dir)) continue
    claimed.add(dir)
    const m = readText(f).match(/^go\s+(\d+\.\d+)/m)
    units.push({
      name: path.basename(dir), type: 'go', dir: path.relative(root, dir) || '.',
      tools: { go: m ? m[1] : 'latest' },
      port: 8080, healthPath: '/', dependsOn: [],
      buildCmd: ['go', 'build', './...'], startCmd: ['go', 'run', '.'],
    })
  }

  // ---- Rust
  for (const f of walk(root, (n) => n === 'Cargo.toml', 3)) {
    const dir = path.dirname(f)
    if (claimed.has(dir)) continue
    claimed.add(dir)
    const tc = readText(path.join(dir, 'rust-toolchain.toml')).match(/channel\s*=\s*"([^"]+)"/)
    units.push({
      name: path.basename(dir), type: 'rust', dir: path.relative(root, dir) || '.',
      tools: { rust: tc ? tc[1] : 'stable' },
      port: 8080, healthPath: '/', dependsOn: [],
      buildCmd: ['cargo', 'build'], startCmd: ['cargo', 'run'],
    })
  }

  // ---- 去掉嵌套单元: 一个项目内部的子包 (uni_modules/nativeplugins/子模块) 不算独立项目
  const nested = new Set()
  for (const a of units) {
    for (const b of units) {
      if (a === b) continue
      const ad = path.resolve(root, a.dir)
      const bd = path.resolve(root, b.dir)
      if (ad !== bd && ad.startsWith(bd + path.sep)) { nested.add(a.name); break }
    }
  }
  const top = units.filter((u) => !nested.has(u.name))

  // ---- 依赖推导: 前端默认依赖后端 (后端没起来前端就是白屏)
  const backends = top.filter((u) => ['maven', 'gradle', 'python', 'go', 'rust'].includes(u.type))
  if (backends.length === 1) {
    for (const u of top) if (u.type === 'node') u.dependsOn = [backends[0].name]
  }

  return top
}

// ───────────────────────────────────────────────────────────── workspace.json

const wsFile = (root) => path.join(root, 'ops', 'workspace.json')

function loadWorkspace(root) {
  const f = wsFile(root)
  const ws = readJson(f)
  if (!ws) {
    bad(`未找到 ${path.relative(root, f)}，先运行:  node ops/devctl.mjs init`)
    process.exit(1)
  }
  return ws
}

function saveWorkspace(root, ws) {
  ensureDir(path.join(root, 'ops'))
  fs.writeFileSync(wsFile(root), JSON.stringify(ws, null, 2) + '\n', 'utf8')
}

/** 按 dependsOn 拓扑排序; 有环则退化为原顺序并告警 */
function orderUnits(units) {
  const byName = new Map(units.map((u) => [u.name, u]))
  const out = []
  const state = new Map()
  let cyclic = false
  const visit = (u) => {
    const s = state.get(u.name)
    if (s === 'done') return
    if (s === 'visiting') { cyclic = true; return }
    state.set(u.name, 'visiting')
    for (const d of u.dependsOn || []) {
      const dep = byName.get(d)
      if (dep) visit(dep)
    }
    state.set(u.name, 'done')
    out.push(u)
  }
  units.forEach(visit)
  if (cyclic) warn('dependsOn 存在循环依赖，已退化为声明顺序启动')
  return out
}

// ───────────────────────────────────────────────────────────── mise.toml

function renderMiseToml(ws) {
  const tools = {}
  for (const u of ws.units) for (const [k, v] of Object.entries(u.tools || {})) if (!(k in tools)) tools[k] = v

  const L = []
  L.push('# 由 devctl.mjs init 生成 —— 本工作区的开发环境工具链')
  L.push('# 新机器: mise install  →  node ops/devctl.mjs build  →  node ops/devctl.mjs start')
  L.push('')
  L.push('[tools]')
  for (const [k, v] of Object.entries(tools)) L.push(`${k} = "${v}"`)
  L.push('')

  // Java 工作区常要换 JDK 发行版 / 指定私服 settings / 换本地仓库位置,
  // 直接把示例注释在这里, 需要时取消注释即可, 不用去翻文档。
  if ('java' in tools || 'maven' in tools) {
    L.push('# ─────────────────────────────────────────────────────────────')
    L.push('# 可选配置：按需取消注释后修改')
    L.push('#')
    L.push('# 1) 换 JDK 发行版 / 版本 —— 直接改上面 [tools] 里的 java')
    L.push('#    可选值示例： temurin-8 / temurin-17 / corretto-8.482.08.1 / zulu-11')
    L.push('#    查可用版本： mise ls-remote java')
    L.push('#    注意：Temurin 8 不带 JavaFX，代码里若有 javafx.* 导入需改用 corretto-8')
    L.push('#')
    L.push('# 2) 指定 Maven settings.xml（私服 / 镜像 / 认证）')
    L.push('# 3) 指定本地仓库位置')
    L.push('#    两者都通过 MAVEN_ARGS 传给 Maven，可以只写一个，也可以合起来写：')
    L.push('#')
    L.push('# [env]')
    L.push('# MAVEN_ARGS = "-s {{config_root}}/ops/settings.xml -Dmaven.repo.local={{config_root}}/.m2repo"')
    L.push('#')
    L.push('#    {{config_root}} = 本文件所在目录，路径跟着仓库走，换机器不用改。')
    L.push('#')
    L.push('#    ⚠️ 两个前提：')
    L.push('#    a. MAVEN_ARGS 需要 Maven >= 3.9；更低版本改用项目根的 .mvn/maven.config')
    L.push('#    b. 只要启用了 [env]，就必须在本目录执行一次 `mise trust`，')
    L.push('#       否则 mise 会以「error parsing config file ... are not trusted」')
    L.push('#       为由拒绝加载【整个】配置文件（报错像语法错，其实是信任问题）')
    L.push('# ─────────────────────────────────────────────────────────────')
    L.push('')
  }

  L.push('# 任务统一走 devctl.mjs。')
  L.push('# 注意 run 用的是 TOML 字面量字符串(三个单引号):')
  L.push('# 基本字符串(双引号)里 Windows 路径的反斜杠会被当转义, C:\\Users 的 \\U 会让 mise 整个配置加载失败。')
  L.push('#')
  L.push('# ⚠️ start / restart 不做成 mise 任务:')
  L.push('#   它们会留下常驻的后台服务, 而 mise run 会等整个进程树退出 —— 在 Windows 上直接挂死。')
  L.push('#   启动请直接用:  node ops/devctl.mjs start')
  for (const a of ['install', 'build', 'stop', 'health', 'status', 'logs', 'doctor']) {
    L.push('')
    L.push(`[tasks.${a}]`)
    L.push(`run = '''node ops/devctl.mjs ${a}'''`)
  }
  L.push('')
  return L.join('\n')
}

// ───────────────────────────────────────────────────────────── 运行态

const runDir = (root) => { const d = path.join(root, '.devctl'); ensureDir(d); return d }
const pidFile = (root, n) => path.join(runDir(root), `${n}.pid`)
const logFile = (root, n) => path.join(runDir(root), `${n}.log`)

function readPid(root, name) {
  const t = readText(pidFile(root, name)).trim()
  if (!t) return null
  const pid = Number(t)
  if (!Number.isInteger(pid)) return null
  try { process.kill(pid, 0); return pid } catch { return null }   // signal 0 = 只探活不发信号
}

function killTree(pid) {
  if (IS_WIN) {
    run('taskkill', ['/PID', String(pid), '/T', '/F'])
    return
  }
  // Unix: 启动时 detached=true 建了独立进程组, 负号 pid 杀整组
  try { process.kill(-pid, 'SIGTERM') } catch { try { process.kill(pid, 'SIGTERM') } catch {} }
}

function portOpen(port, timeout = 800) {
  return new Promise((resolve) => {
    if (!port) return resolve(false)
    const s = net.connect({ host: '127.0.0.1', port, timeout })
    const done = (v) => { s.destroy(); resolve(v) }
    s.on('connect', () => done(true))
    s.on('error', () => done(false))
    s.on('timeout', () => done(false))
  })
}

function httpProbe(port, p, timeout = 5000) {
  return new Promise((resolve) => {
    const req = http.request(
      { host: '127.0.0.1', port, path: p || '/', method: 'GET', timeout },
      (res) => { res.resume(); resolve({ ok: true, status: res.statusCode }) }
    )
    req.on('timeout', () => { req.destroy(); resolve({ ok: false, err: '超时' }) })
    req.on('error', (e) => resolve({ ok: false, err: e.code || e.message }))
    req.end()
  })
}

const ERR_RE = /(^|\s)(ERROR|FATAL|SEVERE)\b|Exception in thread|Caused by:|BUILD FAILURE|Cannot find module|ERR_MODULE|EADDRINUSE|Traceback \(most recent call last\)|panic:|error\[E\d+\]/

function scanLog(root, name, maxLines = 12) {
  const t = readText(logFile(root, name))
  if (!t) return []
  return t.split('\n').filter((l) => ERR_RE.test(l)).slice(-maxLines)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ───────────────────────────────────────────────────────────── 启动

/**
 * 找构建出来的 jar。专门进 target/ 目录 —— walk() 会跳过 target, 这里不能用它。
 * 覆盖 <root>/target/*.jar 和各子模块 <root>/<module>/target/*.jar。
 */
function findJars(root) {
  const out = []
  const scanTarget = (d) => {
    const t = path.join(d, 'target')
    let entries
    try { entries = fs.readdirSync(t, { withFileTypes: true }) } catch { return }
    for (const e of entries) if (e.isFile() && e.name.endsWith('.jar')) out.push(path.join(t, e.name))
  }
  scanTarget(root)
  let subs
  try { subs = fs.readdirSync(root, { withFileTypes: true }) } catch { return out }
  for (const s of subs) {
    if (!s.isDirectory() || s.name.startsWith('.') || s.name === 'target') continue
    scanTarget(path.join(root, s.name))
  }
  return out
}

function resolveStart(root, u) {
  const dir = path.join(root, u.dir)

  if (u.type === 'maven') {
    // 优先跑已打好的 fat jar: 单进程, PID 干净, stop 好收拾
    // 注意不能复用 walk(): 它的 SKIP_DIRS 里有 target(探测时要跳过构建产物), 而 jar 恰恰在 target 里
    const jars = findJars(dir)
      .filter((p) => !/sources|javadoc|original/.test(path.basename(p)))
      .map((p) => ({ p, size: fs.statSync(p).size }))
      .sort((a, b) => b.size - a.size)
    if (!jars.length) return { err: 'target 下没有 jar，先运行 build' }
    const jh = toolPath(dir, 'java')
    if (!jh) return { err: 'mise 里没有 java，先运行 install' }
    const javaBin = path.join(jh, 'bin', IS_WIN ? 'java.exe' : 'java')
    return { cmd: javaBin, args: ['-jar', jars[0].p], cwd: path.dirname(path.dirname(jars[0].p)), viaMise: false }
  }

  if (!u.startCmd) return { err: u.note || '没有配置 startCmd' }
  // 其余一律 mise exec, 由 mise 保证解析到正确版本的 node/python/go/...
  return { cmd: 'mise', args: ['exec', '--', ...u.startCmd], cwd: dir, viaMise: true }
}

async function startUnit(root, u, { wait = true } = {}) {
  if (u.type === 'node-nostart') { warn(`${u.name}: ${u.note} — 跳过`); return 'skip' }

  const already = readPid(root, u.name)
  if (already) { warn(`${u.name} 已在运行 (PID ${already})`); return 'running' }

  const s = resolveStart(root, u)
  if (s.err) { bad(`${u.name}: ${s.err}`); return 'fail' }

  // 端口已经有人占着 —— 后面的"就绪"判断会被它骗过去, 先说清楚
  if (u.port && (await portOpen(u.port))) {
    warn(`${u.name}: 端口 ${u.port} 启动前就已被占用，就绪判断可能失真（先 stop 或换端口）`)
  }

  const log = logFile(root, u.name)
  fs.writeFileSync(log, '', 'utf8')
  const fd = fs.openSync(log, 'a')

  const child = spawn(s.cmd, s.args, {
    cwd: s.cwd,
    // detached 必须两个平台都开:
    //  - Unix : 建独立进程组, stop 时用负号 PID 整组 kill
    //  - Win  : 不开的话子进程和 devctl 在同一个 console/job 里, devctl 一退出子进程就被带走
    detached: true,
    stdio: ['ignore', fd, fd],
    windowsHide: true,
    env: { ...process.env, NODE_OPTIONS: process.env.NODE_OPTIONS || '--max-old-space-size=4096' },
  })
  child.unref()
  fs.writeFileSync(pidFile(root, u.name), String(child.pid), 'utf8')
  ok(`${u.name} 已启动 PID=${child.pid}${u.port ? ` port=${u.port}` : ''}`)
  say(`${C.dim}日志: ${path.relative(root, log)}${C.r}`)

  if (!wait || !u.port) return 'started'

  // 等端口起来, 顺便盯日志里的致命错误 —— 早失败早报错, 不用干等超时
  const deadline = Date.now() + (u.startTimeoutMs || 180000)
  process.stdout.write('  等待就绪 ')
  while (Date.now() < deadline) {
    // 先判进程死活, 再判端口 —— 反过来的话, 别的服务占着同一端口会让已经崩掉的进程被误判成"就绪"
    if (!readPid(root, u.name)) {
      process.stdout.write(' \n')
      bad(`${u.name} 进程已退出`)
      const errs = scanLog(root, u.name)
      if (errs.length) errs.forEach((l) => say(`${C.red}│${C.r} ${l}`))
      else say(`${C.dim}日志为空，完整输出见 ${path.relative(root, logFile(root, u.name))}${C.r}`)
      return 'fail'
    }
    if (await portOpen(u.port)) { process.stdout.write(' \n'); ok(`${u.name} 端口 ${u.port} 就绪`); return 'started' }
    process.stdout.write('.')
    await sleep(2000)
  }
  process.stdout.write(' \n')
  // 进程还活着却始终等不到端口 —— 绝大多数情况是 workspace.json 里的 port 配错了,
  // 而不是启动慢。直接说出来, 别让人对着超时干猜。
  warn(`${u.name} 等待就绪超时：进程还活着，但 ${u.port} 端口一直没监听`)
  const errs = scanLog(root, u.name)
  if (errs.length) {
    say('日志里的错误行:')
    errs.forEach((l) => say(`${C.red}│${C.r} ${l}`))
  } else {
    say(`日志里没有报错，优先怀疑端口配错了：`)
    say(`  1) 看日志确认真实端口:  node ops/devctl.mjs logs -u ${u.name}`)
    say(`  2) 改 ops/workspace.json 里 ${u.name} 的 "port"`)
    say(`  3) 确实只是启动慢，就调大该单元的 "startTimeoutMs"（当前 ${u.startTimeoutMs || 180000}ms）`)
  }
  return 'timeout'
}

// ───────────────────────────────────────────────────────────── 命令

async function cmdDetect(root) {
  head(`探测: ${root}`)
  const units = detectUnits(root)
  if (!units.length) { warn('未识别到任何项目'); return null }
  for (const u of units) {
    const tools = Object.entries(u.tools || {}).map(([k, v]) => `${k}@${v}`).join(' ')
    say(`${u.name.padEnd(24)} ${u.type.padEnd(13)} port=${String(u.port || '—').padEnd(6)} ${tools}`)
    if (u.note) warn(`  └ ${u.note}`)
    if (u.dependsOn?.length) say(`${C.dim}  └ 依赖: ${u.dependsOn.join(', ')}${C.r}`)
  }
  say('')
  say(`${C.dim}以上为推导结果。init 后可直接编辑 ops/workspace.json 修正端口/启动命令/依赖。${C.r}`)
  return units
}

async function cmdInit(root) {
  const units = await cmdDetect(root)
  if (!units) process.exit(1)

  const old = readJson(wsFile(root))
  if (old) {
    // 保留人工改过的字段, 只补新发现的项目
    const byName = new Map(old.units.map((u) => [u.name, u]))
    for (let i = 0; i < units.length; i++) {
      const prev = byName.get(units[i].name)
      if (prev) units[i] = { ...units[i], ...prev }
    }
    warn('已存在 ops/workspace.json，保留其中已有的人工配置')
  }

  const ws = { version: 1, root: path.basename(root), units }
  saveWorkspace(root, ws)
  ok(`已写入 ops/workspace.json`)

  // devctl 复制进工作区, 让工作区自包含 —— 没装本技能的同事也能用
  const target = path.join(root, 'ops', 'devctl.mjs')
  if (path.resolve(target) !== path.resolve(SELF)) {
    fs.copyFileSync(SELF, target)
    ok('已复制 ops/devctl.mjs')
  }

  fs.writeFileSync(path.join(root, 'mise.toml'), renderMiseToml(ws), 'utf8')
  ok('已写入 mise.toml')

  // mise 按文件哈希记信任, 每次重写 mise.toml 都会让之前的信任失效。
  // 带 [tasks]/[env] 的配置未受信任时 mise 会拒绝加载【整个】文件,
  // 且报错写成 "error parsing config file", 极易误判为语法错。所以这里直接补一次。
  const tr = run('mise', ['trust'], { cwd: root })
  if (tr.code === 0) ok('已执行 mise trust')
  else warn(`mise trust 失败，请手动在 ${root} 执行一次: mise trust`)

  // .gitignore: 运行态别进版本库
  const gi = path.join(root, '.gitignore')
  let giText = readText(gi)
  for (const line of ['.devctl/', 'mise.local.toml']) {
    if (!giText.split('\n').some((l) => l.trim() === line)) giText += (giText.endsWith('\n') || !giText ? '' : '\n') + line + '\n'
  }
  fs.writeFileSync(gi, giText, 'utf8')
  ok('.gitignore 已补 .devctl/')

  say('')
  say('下一步:  mise install  →  node ops/devctl.mjs build  →  node ops/devctl.mjs start')
}

function cmdInstall(root) {
  head('mise install')
  const r = run('mise', ['install'], { cwd: root, stdio: 'inherit' })
  if (r.code !== 0) { bad(`mise install 失败 (exit ${r.code})`); process.exit(1) }
  ok('工具链就绪')
  const cur = run('mise', ['ls', '--current'], { cwd: root })
  cur.out.split('\n').filter(Boolean).forEach(say)
}

function cmdBuild(root, ws, only) {
  for (const u of ws.units) {
    if (only && u.name !== only) continue
    if (!u.buildCmd) { if (u.type === 'node-nostart') warn(`${u.name}: ${u.note} — 跳过`); continue }
    head(`build: ${u.name} [${u.type}]`)
    const dir = path.join(root, u.dir)

    // Node 项目先确保依赖装了
    if (u.type === 'node' && !exists(path.join(dir, 'node_modules'))) {
      say('node_modules 缺失，先装依赖')
      const pm = nodePM(dir)
      const i = run('mise', ['exec', '--', pm, 'install'], { cwd: dir, stdio: 'inherit' })
      if (i.code !== 0) { bad(`${pm} install 失败`); process.exit(1) }
    }

    const r = run('mise', ['exec', '--', ...u.buildCmd], { cwd: dir, stdio: 'inherit' })
    if (r.code !== 0) { bad(`${u.name} 构建失败 (exit ${r.code})`); process.exit(1) }
    ok(`${u.name} 构建完成`)
  }
}

async function cmdStart(root, ws, only) {
  head('启动')
  const units = orderUnits(ws.units).filter((u) => !only || u.name === only)
  let failed = false
  for (const u of units) {
    const r = await startUnit(root, u)
    if (r === 'fail' || r === 'timeout') {
      failed = true
      // 依赖它的项目起了也没用, 直接停
      const dependents = units.filter((x) => (x.dependsOn || []).includes(u.name))
      if (dependents.length) { warn(`跳过依赖 ${u.name} 的项目: ${dependents.map((d) => d.name).join(', ')}`); break }
    }
  }
  say('')
  say(`验证:  node ops/devctl.mjs health`)
  if (failed) process.exitCode = 1
}

function cmdStop(root, ws, only) {
  head('停止')
  for (const u of ws.units) {
    if (only && u.name !== only) continue
    if (u.type === 'node-nostart') continue
    const pid = readPid(root, u.name)
    if (pid) {
      killTree(pid)
      ok(`${u.name} 已停止 (PID ${pid})`)
    } else {
      say(`${u.name} 未在运行`)
    }
    try { fs.unlinkSync(pidFile(root, u.name)) } catch {}
  }
}

async function cmdHealth(root, ws, only) {
  head('健康检查')
  let allOk = true
  for (const u of ws.units) {
    if (only && u.name !== only) continue
    if (u.type === 'node-nostart' || !u.port) continue

    const pid = readPid(root, u.name)
    const pOpen = await portOpen(u.port)
    let info = ''
    let good = false

    if (pOpen) {
      const h = await httpProbe(u.port, u.healthPath)
      if (h.ok) { good = true; info = `HTTP ${h.status}` }
      else { info = `端口通但 HTTP 无响应 (${h.err})` }
    } else {
      info = '端口未监听'
    }

    const line = `${u.name.padEnd(24)} 进程:${(pid ? 'OK' : '--').padEnd(4)} 端口${String(u.port).padEnd(6)}${(pOpen ? 'OK' : '--').padEnd(4)} ${info}`
    if (good) ok(line)
    else { bad(line); allOk = false }

    // 不管通不通都扫日志 —— 服务起来了但内部报错也要暴露
    const errs = scanLog(root, u.name, 6)
    if (errs.length) {
      warn(`  ${u.name} 日志中的错误:`)
      errs.forEach((l) => say(`${C.red}  │${C.r} ${l.slice(0, 200)}`))
      allOk = false
    }
  }
  say('')
  if (allOk) { ok('全部健康'); process.exitCode = 0 }
  else { bad('存在问题（退出码 1）'); process.exitCode = 1 }
}

async function cmdStatus(root, ws) {
  head(`状态: ${root}`)
  for (const u of ws.units) {
    const pid = readPid(root, u.name)
    let state = '停止'
    if (pid) state = `运行中 PID=${pid}`
    else if (u.port && (await portOpen(u.port))) state = '端口被占用（非本工具启动）'
    else if (u.type === 'node-nostart') state = '不纳入启停'
    say(`${u.name.padEnd(24)} ${u.type.padEnd(13)} port=${String(u.port || '—').padEnd(6)} ${state}`)
  }
}

function cmdLogs(root, ws, only, lines) {
  for (const u of ws.units) {
    if (only && u.name !== only) continue
    const f = logFile(root, u.name)
    if (!exists(f)) continue
    head(`日志: ${u.name}  (${path.relative(root, f)})`)
    const all = readText(f).split('\n')
    all.slice(-lines).forEach((l) => l && say(l))
  }
}

function cmdDoctor(root) {
  head('环境诊断')
  say(`平台   : ${process.platform} ${process.arch}`)
  say(`Node   : ${process.version}`)
  say(`mise   : ${requireMise()}`)

  const cur = run('mise', ['ls', '--current'], { cwd: root })
  say('')
  say('--- mise 当前解析 ---')
  cur.out.split('\n').filter(Boolean).forEach(say)

  say('')
  say('--- 裸 PATH vs mise exec ---')
  for (const [tool, args] of [['java', ['-version']], ['node', ['--version']], ['mvn', ['-v']]]) {
    const which = run(IS_WIN ? 'where' : 'which', [tool])
    const bare = which.code === 0 ? which.out.split('\n')[0].trim() : '(不在 PATH 上)'
    const viaMise = run('mise', ['exec', '--', tool, ...args], { cwd: root })
    const ver = viaMise.code === 0 ? viaMise.out.split('\n')[0].trim() : '(mise 里没有)'
    say(`${tool.padEnd(6)} 裸PATH: ${bare}`)
    say(`${''.padEnd(6)} mise  : ${ver}`)
  }

  say('')
  say('--- 已知陷阱 ---')
  if (process.env.JAVA_TOOL_OPTIONS) warn(`JAVA_TOOL_OPTIONS=${process.env.JAVA_TOOL_OPTIONS} — 每次 java 调用都会往 stderr 打一行，脚本需过滤`)
  const sep = IS_WIN ? ';' : ':'
  const strayJava = (process.env.PATH || '').split(sep).filter((p) => /jdk|java|corretto|zulu/i.test(p) && !/mise/i.test(p))
  if (strayJava.length) {
    warn(`PATH 上有 ${strayJava.length} 个非 mise 的 Java 路径，排在 mise 之前会赢:`)
    strayJava.forEach((p) => say(`    ${p}`))
    say(`${C.dim}    => 所以本工具一律用 mise exec / mise where，从不用裸 java${C.r}`)
  }
  if (IS_WIN) say(`${C.dim}Windows: 停进程用 taskkill /T /F；Unix: 用进程组负号 PID${C.r}`)
}

// ───────────────────────────────────────────────────────────── CLI

function parseArgs(argv) {
  const a = { action: 'status', path: '.', unit: null, lines: 60, name: null }
  const rest = []
  for (let i = 0; i < argv.length; i++) {
    const t = argv[i]
    if (t === '--path' || t === '-p') a.path = argv[++i]
    else if (t === '--unit' || t === '-u') a.unit = argv[++i]
    else if (t === '--lines' || t === '-n') a.lines = Number(argv[++i]) || 60
    else if (t === '--help' || t === '-h') a.action = 'help'
    else rest.push(t)
  }
  if (rest.length) a.action = rest[0]
  if (rest.length > 1) a.name = rest[1]
  return a
}

const HELP = `
devctl —— 基于 mise 的全栈工作区开发环境驱动器

用法:  node ops/devctl.mjs <命令> [选项]

环境准备
  detect              探测工作区里有哪些项目，只打印不落盘
  init                写 ops/workspace.json + mise.toml，并把自身复制到 ops/
  install             mise install，按 mise.toml 装齐工具链
  doctor              诊断 mise / PATH / 工具链解析问题

开发闭环
  build               编译各项目
  start               按依赖顺序启动，等端口就绪
  health              端口 + HTTP 探活 + 日志扫错   退出码 0=通过 1=有问题
  restart             stop + start，改完代码用这个
  stop                停止
  status              各项目运行状态
  logs                日志尾部

选项
  -p, --path <目录>    工作区根，默认当前目录
  -u, --unit <项目名>  只对单个项目操作
  -n, --lines <数量>   logs 显示行数，默认 60
`

async function main() {
  const a = parseArgs(process.argv.slice(2))
  if (a.action === 'help') { console.log(HELP); return }

  const root = path.resolve(a.path)
  if (!exists(root)) { bad(`路径不存在: ${root}`); process.exit(1) }

  requireMise()

  switch (a.action) {
    case 'detect': await cmdDetect(root); return
    case 'init': await cmdInit(root); return
    case 'doctor': cmdDoctor(root); return
    case 'install': cmdInstall(root); return
  }

  const ws = loadWorkspace(root)
  switch (a.action) {
    case 'build': cmdBuild(root, ws, a.unit); break
    case 'start': await cmdStart(root, ws, a.unit); break
    case 'stop': cmdStop(root, ws, a.unit); break
    case 'restart':
      cmdStop(root, ws, a.unit)
      await sleep(2000)
      await cmdStart(root, ws, a.unit)
      break
    case 'health': await cmdHealth(root, ws, a.unit); break
    case 'status': await cmdStatus(root, ws); break
    case 'logs': cmdLogs(root, ws, a.unit, a.lines); break
    default:
      bad(`未知命令: ${a.action}`)
      console.log(HELP)
      process.exit(1)
  }
}

main().catch((e) => { bad(e.stack || e.message); process.exit(1) })
