# JavaFixAgent

面向 Java 代码仓库的自主缺陷定位与修复智能体：给定一个 Java 项目和一个 Bug 描述，
它自己搜索代码、定位问题、修改源码、执行测试，并根据测试反馈反复修正，最终输出一个 Git Patch。

## 架构

```
Bug 描述
   │
   ▼
AgentLoop ──────────► LlmClient（决定下一步动作）
   │  ▲                    │
   │  └────────────────────┘
   │
   ├─► ReadFileTool / SearchCodeTool / WriteFileTool / ShellTool
   ├─► MavenTestRunner ──► TestResult（测试反馈）
   └─► Git Patch
```

## 当前状态

> 最后更新：2026-09-12

**已实现**

- `TestRunner` / `TestResult`：测试执行的抽象。退出码能区分「测试失败」「启动失败」「超时」
- `MavenTestRunner`：在目标项目目录执行 `mvn test`；输出落盘到 `target/javafix-mvn-test.log`，
  带超时保护，超时会终止整棵进程树而不留下孤儿进程
- 端到端集成测试：现场生成一个带 Bug 的 Maven 项目，验证 Runner 能识别出失败的测试用例，
  并验证超时、路径不存在两条异常分支

**进行中**

- Agent Loop：`AgentLoop.run()` 目前仍是空实现
- 工具实现：`ReadFileTool` / `SearchCodeTool` / `WriteFileTool` / `ShellTool` 只定义了契约，尚未实现
- LLM 接入：`LlmClient` 只有接口，还没有任何实现

**计划中**

- Agent 运行轨迹记录
- 自带 Bug 样本集的构建与修复成功率评测
- Java Symbol Index、基于 AST 的代码定位
- Context Engineering，以及与同类方案的对比实验

## 阶段目标

让 Agent 在自带的 Bug 样本集上端到端跑通「定位 → 修改 → 测试」闭环，
并给出可复现的成功率、平均迭代轮数与 token 消耗（数据待评测完成后回填）。

## 快速开始

```bash
# 构建并运行测试
mvn test
```

当前入口 `Main` 只打印一行启动信息——Agent 循环尚未接通，还不能真正修 bug。

## 设计取舍

- **不引入 Spring AI / LangChain4j 等 Agent 框架**，循环自己写。本项目要验证的正是循环本身的设计
  （上下文选择、工具调度、测试反馈如何影响下一轮决策），套框架会把这个核心问题藏起来。
- **先只支持 Maven**。Gradle 等到 Maven 链路跑通、评测有数据之后再考虑。
- **依赖保持最小**。V0 唯一的第三方依赖是测试用的 JUnit 5。

## 项目愿景

本项目重点研究大型 Java Repository 场景下 Coding Agent 的：

- 代码定位
- 上下文选择
- AST / Symbol 检索
- 测试反馈
- 自动修复
- Agent 评测
