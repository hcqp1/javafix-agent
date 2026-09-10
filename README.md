# JavaFixAgent

JavaFixAgent 是一个面向 Java 代码仓库的自主软件缺陷定位与修复智能体。

## 项目目标

给定一个 Java 项目和 Bug 描述，JavaFixAgent 能够自主完成：

- 分析代码仓库
- 搜索相关代码
- 定位相关类和方法
- 修改源代码
- 执行 Maven / Gradle 测试
- 分析测试失败信息
- 自动重新尝试修复
- 输出最终 Git Patch

## 项目路线

- [ ] 基础 Agent Loop
- [ ] LLM 接入
- [ ] Shell 命令执行
- [ ] Repository 代码搜索
- [ ] 文件修改
- [ ] Maven 测试反馈
- [ ] Agent 运行轨迹记录
- [ ] Java Symbol Index
- [ ] 基于 AST 的代码定位
- [ ] Context Engineering
- [ ] Benchmark 评测
- [ ] 消融实验

## 当前状态

🚧 项目开发中

当前目标：

实现 JavaFixAgent V0，使 Agent 能够根据 Bug 描述，
自主搜索 Java 项目、修改代码并执行测试。

## 项目愿景

本项目重点研究大型 Java Repository 场景下 Coding Agent 的：

- 代码定位
- 上下文选择
- AST / Symbol 检索
- 测试反馈
- 自动修复
- Agent 评测
