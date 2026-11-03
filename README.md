# JavaFixAgent

[![CI](https://github.com/hcqp1/javafix-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/hcqp1/javafix-agent/actions/workflows/ci.yml)

面向 Java 代码仓库的自主缺陷定位与修复智能体：给定一个 Java 项目和一个现象描述
（Bug 报告），它自己复现问题、定位根因、修改源码并验证——而不是拿到已经标好位置的
指令才动手。

## 架构

```
现象描述
   │
   ▼
AgentLoop（四阶段状态机：复现 → 定位 → 修复 → 验证）
   │  ▲
   │  └────── LlmClient（OpenAI 兼容协议，决定下一步动作）
   │
   ├─► read_file / search_code / replace_in_file / write_file / run_tests / shell / diagnose_trace
   ├─► MavenTestRunner（普通测试与诊断运行使用各自的超时）
   └─► 最终答复 + 逐步写入的运行记录
```

## 当前状态

> 最后更新：2026-11-03

**已实现**

- 四阶段工作流（`AgentLoop` + `Phase`）：复现 → 定位 → 修复 → 验证。输入是用户报告的现象，
  不是已经定位好的缺陷；每个阶段有独立的指令和可编程的出口条件。复现阶段会拦截
  `write_file` 和 `replace_in_file` 对 `src/main` 的编辑，先把现象变成一条失败的测试。
- `TestRunner` / `TestResult`：测试执行抽象，退出码区分「测试失败」「启动失败」「超时」。
- `MavenTestRunner`：优先使用项目自带的 Maven Wrapper（真实仓库会在 pom 里锁定 Maven 版本，
  系统那套 mvn 会因版本不符被 enforcer 挡下）；输出落盘到 `target/javafix-mvn-test.log`；
  默认超时 15 分钟（真实仓库构建很重），超时终止整棵进程树而不留下孤儿进程。
- `run_tests` 会区分测试通过/用例失败、构建或工具链环境失败、超时和测试未启动。若 Surefire 已报告失败用例，
  即使日志同时包含非致命的 JDK/插件兼容提示，也以测试失败作为复现证据；没有测试结论时，环境错误和依赖下载失败仍不会被当作 Bug 复现证据。复现和验证仍使用同一测试范围。
- 工具：`read_file` / `search_code` / `replace_in_file` / `write_file` / `run_tests` / `shell` / `diagnose_trace`。文件工具的路径限制在仓库根目录内；
  `run_tests` 支持 `test` 参数只跑一个测试类，把一次验证从几十分钟收窄到几秒。
- 协议加固：对模型输出做容错解析；运行轨迹不再回灌"可被模型模仿的格式"；
  观察结果限长，避免一次大文件读取污染后续上下文。
- `diagnose_trace`：定位阶段可在确认一个可疑方法和聚焦失败测试后，对项目副本运行受控的
  Java 运行时诊断。它在临时副本中进行 AST 插桩，分别执行插桩前和插桩后的同一 Maven 测试，
  第一版仅支持一个失败测试用例，只有两次该用例的身份与结果匹配时才返回带 `className#testName` 的轨迹；临时副本会在完成或失败时清理。工具不改原项目。
- 同一任务内保留最近两次失败修复的方案、测试范围和失败反馈，下一轮修复会明确回顾这些尝试；
  达到修复轮数上限而测试仍失败时，返回未完成状态。验证还会核对复现时的测试范围、
  Java 测试源码和执行用例数，避免删掉失败测试后误报通过。
- `OpenAiCompatibleLlmClient`：OpenAI 兼容协议（DeepSeek / 通义 / Kimi / Ollama 都可用），
  带超时与退避重试；5xx、429 会重试，其他 4xx 直接失败并带出服务端原因。
- `Main`：命令行入口，模型配置从环境变量读取、不写进代码也不进仓库；终端只打印摘要，
  每条已完成的运行记录立即追加到用户目录下的 `.javafix-agent/runs/*.jsonl`。
- GitHub Actions：push 到 main 与所有 PR 都会运行完整测试。
- 基准评测：`benchmarks/dataset.json` 固定了 MyBatis M2、RocketMQ M3/M4 的缺陷版本、官方修复版本、
  回归测试、预期失败签名和关键源码文件；`benchmarks/run.py` 在临时 Git worktree 中先确认缺陷、运行 Agent、
  再独立复跑测试，输出 JSONL 修复结果、定位 top-k、耗时和可获得的 token 用量。当前内置集仅覆盖 Maven 案例。
- Java 符号定位：`find_references` 使用 JavaParser 在仓库内定位指定类型/方法的声明和语法上可识别的调用候选，
  支持限定源码路径、结果条数上限，并在部分文件解析失败时保留其他结果；定位阶段会优先调用它，无法解析或绑定时回退到 `search_code`。

**进行中**

- 真实仓库（如 RocketMQ、Dubbo）上的复现与定位能力仍在验证和打磨。
- 基准运行器已就绪；尚未完成整套真实样本运行，暂不报告修复率或成本结论。
- 上下文工程：目前只做到"观察结果限长"，按阶段裁剪上下文、检索式上下文尚未实现。

**计划中**

- 完整 Java Symbol Index 与跨模块、继承、泛型等语义级引用解析
- Gradle 支持
- Context Engineering 与同类方案的对比实验

`find_references` 当前使用 Java 17 语法解析，最多扫描 3000 个 Java 文件、单文件 1 MiB、每次返回最多 30 条结果。
它依据源码中的类型声明、参数/变量类型、显式导入和显式接收者产生候选，不加载 Maven/Gradle classpath，也不处理通配符导入、完整的继承、泛型推断或动态绑定；
因此结果用于缩小搜索范围，仍需用源码、测试及 `search_code` 复核。

## Maven Bug 基准

基准集先收录 3 个带固定版本和回归测试的 Maven 案例。每个样本使用官方修复版本中的测试文件覆盖到缺陷版本，
先运行它确认预期失败，再由 JavaFixAgent 修复，最后独立重跑相同测试。每次运行会新建临时 Git worktree，
结束后清理临时 worktree；不会改动 `--repos-dir` 中的源文件，Git 会临时登记并随后移除 worktree 元数据。
需要本机已克隆相应仓库并包含数据集列出的两个提交，以及 Python 3.10+、Git、JDK/Maven 和已配置好的 JavaFixAgent 模型。

```powershell
# 先只跑 M4；M2、M3 也可分别传 --case M2 / --case M3
python benchmarks/run.py --repos-dir 'D:\yangben\_repos' --case M4 `
  --baseline-only --output 'D:\yangben\benchmark-results\baseline.jsonl'

# 确认基线能复现后，再让 Agent 修复并记录结果（会调用已配置的模型）
python benchmarks/run.py --repos-dir 'D:\yangben\_repos' --case M4 `
  --output 'D:\yangben\benchmark-results\javafix.jsonl'

# 跑全部内置样本
python benchmarks/run.py --repos-dir 'D:\yangben\_repos' `
  --output 'D:\yangben\benchmark-results\javafix.jsonl'

# 运行基准工具单元测试
python -m unittest discover -s benchmarks -p test_run.py -v
```

JSONL 每行是一条运行记录。基线检查时 `status` 还可能是 `baseline_reproduced`。
完整运行时 `status` 取 `repaired`、`repair_failed`、`environment_failure` 或
`baseline_not_reproduced`；环境或基线问题不会混算成 Agent 修复失败。`localization.rank` 是关键源码文件
在 Agent 读取/搜索文件顺序中的名次，`top1`/`top3` 表示是否命中；日志中没有足够轨迹时会明确记为未命中或缺失。
`steps`、耗时和 token 数只在运行输出提供时记录。结果只代表这些固定样本和当前环境，不代表任意 Java 仓库的成功率。

## 运行时诊断范围与限制

- 要求 Java 17 和 Maven 项目；诊断总预算为 10 分钟，每次 Maven 最多 5 分钟，且不能超过剩余预算。Gradle 不支持。
- 第一版的选择器必须恰好报告一个 FAILED/ERROR 测试用例；多方法、参数化或动态测试若报告多条用例会被拒绝，不提供多用例轨迹归属。
- Surefire XML 的声明计数必须与直接 testcase 子节点一致，并且 Maven 必须输出唯一、匹配的完整汇总；缺失、模糊或中断的报告不返回轨迹。
- 源码与单个 XML 报告各限 1 MiB；复制、解析和输出处理检查剩余预算。捕获器用独立状态文件记录写入失败；若状态文件和备用标记都无法写入，只会终止隔离 Maven/Surefire 进程树中的诊断测试 JVM（默认是 Surefire fork），不会终止 JavaFixAgent 宿主进程；Maven 父进程可能将子进程异常报告为 fork/build failure。任一失败信号写入成功时，普通捕获错误不会中断测试。
- 模型必须先选定一个可疑 Java 方法和一个聚焦失败测试，再决定是否调用 `diagnose_trace`；
  工具不会自动选择方法。诊断结果是未经信任的证据，模型应结合源码与测试判断。
- 当前记录方法入口、局部变量声明、赋值、条件分支的 then/else 结果。字段读写、返回表达式、
  循环迭代、异常和线程事件不在支持范围内；无法安全插桩的源码会跳过或拒绝。
- 每次最多保留 500 个事件、每个值最多 160 个字符、最终输出最多 3500 个字符。
- 一致性检查比较逐测试身份及其结果类别（通过、失败、错误、跳过）；这不保证断言细节、
  执行顺序、测试环境或非确定性状态完全一致，因此轨迹仍可能受运行差异影响。
- 诊断在临时副本中进行，并在工具退出时清理，不会在原工作树中改写插桩代码。
- 不提供自动方法选择、Gradle 支持或跨任务记忆。

## 阶段目标

让 Agent 拿到一个真实仓库和一段现象描述，就能自主复现、定位并修复缺陷；并给出可复现的
修复成功率、定位准确率（top-k 文件命中）与平均成本（步数 / token / 耗时）。

## 快速开始

```bash
# 构建并运行测试（不需要联网，也不需要 API Key）
mvn test
```

想让它真的修一个 bug，首次启动持续聊天模式：

```powershell
mvn -q exec:java
```

第一次会提示输入模型 URL、模型名和 API Key；API Key 输入时不会回显，并保存在用户目录
`~/.javafix-agent/config.json`。之后启动不需要重复输入。也可以设置完整的
`JAVAFIX_LLM_BASE_URL`、`JAVAFIX_LLM_API_KEY`、`JAVAFIX_LLM_MODEL` 环境变量覆盖配置。
如果没有交互式终端，请先设置这三个环境变量再启动。

持续聊天命令：

```text
/project D:\some-java-project
/new 定时消息偶尔丢失，请定位并修复
/ask 消息发送失败时重试逻辑在哪里？
/fix 再检查一下并发场景
/tasks
/resume <任务ID>
/exit
```

`/new` 创建独立 Bug 任务；同一任务中的普通文本和 `/fix` 会继续修复，`/ask` 只允许读取和搜索代码。
`/tasks` 列出保存的任务，`/resume` 恢复指定任务。每个任务的对话与运行轨迹分别保存在
`~/.javafix-agent/tasks/<任务ID>/`，旧任务不会被新任务的上下文加载。

恢复任务时若项目路径、Git 分支、HEAD 或工作区改动与上次记录不同，JavaFixAgent 会先展示差异并要求输入
`continue` 或 `cancel`。输入 `continue` 只表示使用当前工作区继续，不会切回旧版本；JavaFixAgent 不会自动
checkout、reset、stash 或覆盖代码。任务事件逐条追加；意外退出后，末尾不完整的 JSON 行会被忽略，之前完整记录保留。

原有的一次性命令行入口仍可用：

```powershell
mvn -q exec:java "-Dexec.args=D:\some-java-project 定时消息偶尔丢失，请定位并修复"
```

一次性模式会使用相同的用户目录模型配置，并继续将运行轨迹写入 `.javafix-agent/runs/*.jsonl`。
任务事件可能包含项目源码片段和工具输出，请按项目资料保管。
验证使用测试范围、源码指纹和用例数量作为最低限度的证据，尚未逐一核对失败用例的身份。

## 设计取舍

- **不引入 Spring AI / LangChain4j 等 Agent 框架**。要验证的正是循环与阶段本身的设计，
  套框架会把核心问题藏起来。
- **用四阶段状态机而不是单一循环**。模型永远倾向"直接改代码"这条最短路径，
  阶段与硬约束把它拉回"先复现、再定位、最后才改"。
- **关键出口条件用代码检查**。例如验证阶段必须拿到测试结论、观察结果限长；
  复现阶段拦截文件编辑工具对生产代码的修改。通用 `shell` 命令目前不受这项拦截约束。
- **优先使用项目自带的 Maven Wrapper**。真实仓库会锁定 Maven 版本，系统 mvn 会失败。
- **先只支持 Maven**。Gradle 等 Maven 链路跑通、评测有数据之后再考虑。
- **生产依赖**：Jackson 用于模型接口 JSON，JavaParser 用于受限源码插桩。HTTP 使用 JDK 自带的 `HttpClient`。

## 项目愿景

本项目重点研究大型 Java Repository 场景下 Coding Agent 的：

- 代码定位
- 上下文选择
- AST / Symbol 检索
- 测试反馈
- 自动修复
- Agent 评测
