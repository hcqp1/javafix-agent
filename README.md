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
   ├─► read_file / search_code / write_file / run_tests / shell
   ├─► MavenTestRunner（优先项目自带的 Maven Wrapper）
   └─► 最终答复 + 运行轨迹
```

## 当前状态

> 最后更新：2026-09-13

**已实现**

- 四阶段工作流（`AgentLoop` + `Phase`）：复现 → 定位 → 修复 → 验证。输入是用户报告的现象，
  不是已经定位好的缺陷；每个阶段有独立的指令和可编程的出口条件。复现阶段在代码层面
  硬性禁止修改 `src/main` 下的生产代码——先把现象变成一条失败的测试，才允许动手修。
- `TestRunner` / `TestResult`：测试执行抽象，退出码区分「测试失败」「启动失败」「超时」。
- `MavenTestRunner`：优先使用项目自带的 Maven Wrapper（真实仓库会在 pom 里锁定 Maven 版本，
  系统那套 mvn 会因版本不符被 enforcer 挡下）；输出落盘到 `target/javafix-mvn-test.log`；
  默认超时 15 分钟（真实仓库构建很重），超时终止整棵进程树而不留下孤儿进程。
- 工具：`read_file` / `search_code` / `write_file` / `run_tests` / `shell`。路径限制在仓库根目录内；
  `run_tests` 支持 `test` 参数只跑一个测试类，把一次验证从几十分钟收窄到几秒。
- 协议加固：对模型输出做容错解析；运行轨迹不再回灌"可被模型模仿的格式"；
  观察结果限长，避免一次大文件读取污染后续上下文。
- `OpenAiCompatibleLlmClient`：OpenAI 兼容协议（DeepSeek / 通义 / Kimi / Ollama 都可用），
  带超时与退避重试；5xx、429 会重试，其他 4xx 直接失败并带出服务端原因。
- `Main`：命令行入口，模型配置从环境变量读取、不写进代码也不进仓库；终端只打印摘要，
  完整运行轨迹写入临时文件。
- GitHub Actions：push 到 main 与所有 PR 都会运行完整测试。

**进行中**

- 真实仓库（如 RocketMQ、Dubbo）上的复现与定位能力仍在验证和打磨。
- 上下文工程：目前只做到"观察结果限长"，按阶段裁剪上下文、检索式上下文尚未实现。

**计划中**

- 符号级代码定位（`find_references`、AST / Java Symbol Index）
- 自带 Bug 样本集的构建，以及修复成功率、定位准确率、成本的评测
- Gradle 支持
- Context Engineering 与同类方案的对比实验

## 阶段目标

让 Agent 拿到一个真实仓库和一段现象描述，就能自主复现、定位并修复缺陷；并给出可复现的
修复成功率、定位准确率（top-k 文件命中）与平均成本（步数 / token / 耗时）。

## 快速开始

```bash
# 构建并运行测试（不需要联网，也不需要 API Key）
mvn test
```

想让它真的修一个 bug，先配置模型（以 DeepSeek 为例）：

```powershell
set JAVAFIX_LLM_BASE_URL=https://api.deepseek.com/v1
set JAVAFIX_LLM_API_KEY=你的 key
set JAVAFIX_LLM_MODEL=deepseek-chat
```

然后指定一个项目目录和一段现象描述：

```powershell
mvn -q exec:java "-Dexec.args=D:\some-java-project 定时消息偶尔丢失，请定位并修复"
```

运行结束会打印 Agent 的结论、各阶段摘要和度量；完整轨迹写入临时文件（路径打印在末尾），
排查「它为什么这么改」时再去看。

## 设计取舍

- **不引入 Spring AI / LangChain4j 等 Agent 框架**。要验证的正是循环与阶段本身的设计，
  套框架会把核心问题藏起来。
- **用四阶段状态机而不是单一循环**。模型永远倾向"直接改代码"这条最短路径，
  阶段与硬约束把它拉回"先复现、再定位、最后才改"。
- **能用代码强制的规则不靠提示词**。例如复现阶段禁止写生产代码、观察结果限长——
  模型在压力下一定会违反提示词，但绕不过代码检查。
- **优先使用项目自带的 Maven Wrapper**。真实仓库会锁定 Maven 版本，系统 mvn 会失败。
- **先只支持 Maven**。Gradle 等 Maven 链路跑通、评测有数据之后再考虑。
- **生产依赖只有一个**：Jackson，用来处理模型接口的 JSON。HTTP 用 JDK 自带的 `HttpClient`。

## 项目愿景

本项目重点研究大型 Java Repository 场景下 Coding Agent 的：

- 代码定位
- 上下文选择
- AST / Symbol 检索
- 测试反馈
- 自动修复
- Agent 评测
