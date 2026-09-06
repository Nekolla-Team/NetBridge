# 代码写法规范

重构全程适用；触及的文件一律按本规范清理存量注释。

## 注释

- **函数级**：每个函数/方法/公开常量用文档注释（Rust `///`、Java Javadoc）说明用途与参数。 返回值契约（如
  `0 = 队列满须重试`、`-1 = 非法`）属于接口承诺，必须写在这里。
- **函数体内**：非必要不加注释。代码自解释；仅在意图无法从代码读出时注释，
  且只写"为什么"，不复述"做了什么"。
- **禁止随改动累积注释**：修 bug、重构时不得顺手加入描述本次改动的解释性注释。 设计依据沉淀到
  `docs/adr` 与 `docs/glossary`，不进函数体。
- **书面语**：注释禁口语化措辞；内容必须与实际行为一致——行为变更时同步修改或删除， 宁缺勿错。

## 文档引用

- **源码内禁止出现 ADR / 术语表索引**（如"见 ADR-0007""（ADR-0002）"）。 编号会漂移，索引必然失真；代码不维护指向文档的链接。
- 跨层语义约定写在函数文档注释中，不借文档编号转述。

## 存量问题

现仓库大量违反本规范：函数体内长段解释、全库散布 ADR 编号引用。
重构任务清单含"按本规范重写触及文件注释"一项。

## 生成物（generated artifact）

`rust/crates/net-bridge-native/include/netbridge.h` 是由固定版本 cbindgen 从 Rust ABI 源真相 （
`rust/crates/net-bridge-native/src/abi/`）生成的产物，并随仓库提交：

- **禁止手工编辑 `netbridge.h`**；手工改动由 CI drift gate 拒绝。
- 需要改 ABI 时只改 Rust ABI 源文件。
- 更新：`./gradlew updateNativeHeader`（或 `cd rust && cargo xtask abi-header update`）。
- 提交前若 ABI 有变更，先跑 `./gradlew verifyNativeHeader`（或 `cargo xtask abi-header check`）。
