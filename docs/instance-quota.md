# 实例名额（Quota）统计与提示说明

本文说明 Kubernetes 插件在判断“能否再启动一个实例”
（`KubeCloudClient.canStartNewInstanceWithDetails`）时：

- 代码实际依赖哪些 TeamCity `InstanceStatus`，每个状态是否占用名额；
- 终止中、创建失败、Pending 卡住三类实例的名额规则；
- 超限提示中数字的来源（同一次快照）；
- 实例上限（profile / image limit）的边界值、保存时校验与运行时容错的取舍；
- 多镜像共享档案上限时的公平性边界。

## 1. 判定顺序

保持原有顺序不变：

1. 镜像 ID 不属于该 cloud client → 直接拒绝（未知镜像）。
2. **档案上限**：把档案下所有镜像的占用数求和，与 `profileInstanceLimit` 比较。
3. **镜像上限**：当前镜像的占用数与 `imageInstanceLimit` 比较。
4. 都未超限 → 允许启动。

档案上限先于镜像上限，因此档案满时优先报档案级提示。

## 2. `InstanceStatus` 状态对照表

`getRunningInstanceCount()`（`KubeCloudImageImpl`）今天仍然直接调用 TeamCity
云 API 的 `InstanceStatus.isStartingOrStarted()`。仓库内没有 TeamCity 云 API
源码，但已用 JetBrains 官方 Maven 仓库中的 `cloud-api` / `cloud-interface`
字节码核对：

- `org.jetbrains.teamcity:cloud-api:2026.3-DSL-eap2`
- `org.jetbrains.teamcity:cloud-interface:2022.10`（旧版本，结论一致）

`isStartingOrStarted()` 的字节码等价于：

```java
return this == RUNNING
    || this == STARTING
    || this == RESTARTING
    || this == SCHEDULED_TO_START;
```

即 **不包含** `SCHEDULED_TO_STOP`、`STOPPING`，也不包含 `UNKNOWN / STOPPED /
ERROR / ERROR_CANNOT_STOP`。该集合在 2022.10 与 2026.3 两个版本间没有变化。
实际运行时仍以 TeamCity 服务器自带的 `cloud-api` 版本为准；若未来平台修改该
谓词语义，需要重新核对本表。

名额统计没有停留在 `isStartingOrStarted()`：为避免删除中的 Pod 造成集群总量
瞬时突破上限，名额占用集合在其基础上**额外计入两个终止中状态**。分类逻辑集中在
`InstanceQuotaSnapshot.groupOf(InstanceStatus)`。

| `InstanceStatus` | `isStartingOrStarted()` | 名额分组（提示文案） | 是否占用名额 | 说明 |
|---|---|---|---|---|
| `SCHEDULED_TO_START` | `true` | starting（启动/调度中） | **是** | 新建实例的初始状态；K8s `Pending` 也映射到此状态 |
| `STARTING` | `true` | starting | **是** | 平台标记的启动中 |
| `RESTARTING` | `true` | starting | **是** | 重启中，旧资源仍在 |
| `RUNNING` | `true` | running（运行中） | **是** | 运行中的 Pod |
| `SCHEDULED_TO_STOP` | `false` | terminating（终止中） | **是（本次明确计入）** | 已排队终止，删除尚未完成，Pod/PVC 仍占资源 |
| `STOPPING` | `false` | terminating（终止中） | **是（本次明确计入）** | 正在调用 K8s 删除，Pod 消失前仍占集群资源 |
| `UNKNOWN` | `false` | — | 否 | Pod phase 无法识别时的映射；沿用旧规则不计入 |
| `STOPPED` | `false` | — | 否 | `Succeeded`/`Failed` phase 或删除完成后的终态，资源已释放 |
| `ERROR` | `false`（`isError()==true`） | — | **否** | 创建失败后由 `startNewInstance` 异步任务设置；见问题二 |
| `ERROR_CANNOT_STOP` | `false`（`isError()==true`） | — | 否（保留风险提示） | 平台“停止失败”错误态；本插件终止流程目前写的是普通 `ERROR`。严格说 Pod 可能仍在运行，需要人工处理，当前规则与 `isError()` 不计数的平台惯例保持一致 |

占用总数：

```
occupied = running + starting + terminating
```

`getRunningInstanceCount()` 的历史语义（只统计 starting/started 族）保持不变，
避免影响其他潜在调用方；名额判定改用 `InstanceQuotaSnapshot`。

## 3. 核对中的三个问题

### 问题一：终止中的实例占不占名额？——占

`terminateInstance` 先把实例置为 `SCHEDULED_TO_STOP`，提交异步任务后再置为
`STOPPING`，删除 Pod（以及可选 PVC）成功并置为 `STOPPED` 后，才调用
`populateInstances()` 刷新实例表。在 Pod 真正从 Kubernetes 消失之前，它仍然
占用节点的 CPU/内存、IP、PVC 挂载等资源。

如果名额统计忽略 `SCHEDULED_TO_STOP/STOPPING`，那么当实例数正好等于上限、
又有实例被终止时，TeamCity 的排队构建会立刻认为“有名额”并创建新 Pod，而旧 Pod
尚在优雅删除窗口内，集群中的实际 Pod 总量会在短时间内达到
`上限 + 正在终止数`。当多个实例同时终止时，超出幅度可能很大，甚至触发节点资源
耗尽/抢占。

因此本次修改把这两个状态计入 `terminating` 分组并参与档案/镜像两级上限比较。
终止完成（`STOPPED`）或实例被 `populateInstances()` 清除后，名额自动释放。
提示中单独列出 `terminating=N`，用户可以判断是“稍等删除完成”还是“上限配置过小”。

### 问题二：创建失败、状态为 `ERROR` 的实例占不占名额？——在本规则下不占

`startNewInstance` 先把实例放进镜像的实例表，再异步创建 PVC/Pod；异步任务捕获
异常后执行 `instance.setStatus(InstanceStatus.ERROR)` 并记录
`CloudErrorInfo`。此时：

- 如果 `createPod` 本身失败，K8s 中通常根本没有 Pod（代码还会回滚已创建的
  PVC），没有集群资源需要名额保护；
- 该对象会一直留在 `KubeCloudImageImpl.myIdToInstanceMap` 中，直到下一次
  `populateInstances()`：K8s Pod 列表里找不到它，且它的状态不是
  `SCHEDULED_TO_START`，于是会从 map 中移除
  （见 `populateInstances()` 中对 `SCHEDULED_TO_START` 的豁免逻辑）。

明确规则：**`ERROR` 与 `ERROR_CANNOT_STOP` 不占用启动名额**（它们不在
`isStartingOrStarted()` 集合中，也不属于 terminating 分组）。因此创建失败会
立即在名额计算上释放槽位，不必等待下一次 `populateInstances()`；残留的表项只
用于在 UI 上展示错误原因，随后由 populate 清理。这样排队构建可以立即重试到
别的名额上，而不会被一个永远不会变成 Pod 的失败对象挡住。

### 问题三：卡在 Pending 的 Pod（`SCHEDULED_TO_START`）？

K8s `Pending` phase 一直被 `KubeUtils.mapPodPhase` 映射为
`SCHEDULED_TO_START`，它属于 `starting` 分组，会**持续占用名额**。这是有意
的：Pending Pod 已经被 API Server 接受，可能已经申请/挂载卷、等待调度或拉取
镜像，在失败判定给出结论前必须占住槽位，否则会重复放大创建量。

“Pending 多久算失败、如何判定”属于需求五（启动失败判定）的范围，本题不重复
实现任何超时/失败判定逻辑。本题只保证：一旦需求五（或现有状态刷新/异步异常
处理）把状态改成 `ERROR`/`STOPPED` 或删除实例，名额快照在下一次
`canStartNewInstanceWithDetails` 时立即反映并释放名额。

## 4. 提示数字必须来自同一次快照

旧实现里档案级比较对所有镜像的 `getRunningInstanceCount()` 求和、镜像级再读
一次当前镜像计数，提示文案只有固定上限，没有当前计数。

现在 `canStartNewInstanceWithDetails` 在方法开头对
`myImageIdToImageMap.values()` 中每个镜像的 `getInstances()` **只遍历一次**，
同时累加出：

- 档案级 `InstanceQuotaSnapshot`（所有镜像相加）；
- 每个镜像 ID 对应的镜像级快照（同一次遍历中留存）。

后续档案比较、镜像比较以及拒绝文案中的 `occupied/running/starting/
terminating` 全部来自这两个不可变快照，不会在比较之后再次读取实例表，因此：

- 文案数字一定等于刚刚参与判定的数字；
- 不会出现“比较时已满、写文案时实例刚好退出”导致数字与结论矛盾；
- 单次遍历也避免了对同一实例表的多次弱一致迭代。

文案为英文（与现有代码/控制台语言一致），同时输出人类可读部分和稳定的
`key=value` 计数段，例如：

```text
The instance limit of 5 per profile is reached. Current usage: 5 occupied out of 5 (3 running, 1 starting/scheduled, 1 terminating); occupied=5 running=3 starting=1 terminating=1. Terminating instances are counted because their pods still consume cluster resources until deletion completes.
```

对应需求中的示例“档案上限5，运行3，启动1，终止1”。镜像级提示额外带镜像 ID。
上限为 `0` 时文案会明确说明“limit is 0, starting new instances is forbidden”，
此时即使占用为 0 也会拒绝（`0 >= 0`）。

两种超限仍返回
`CanStartNewInstanceResult.ReasonType.QUOTA_LIMIT_REACHED`；配置值非法时返回
不带 `QUOTA_LIMIT_REACHED` 类型的否定结果（语义上是配置错误而非名额已满）。

## 5. 上限边界与非法值处理

`KubeParametersConstants`：

- 档案：`profileInstanceLimit`（UI：Maximum instances count）
- 镜像：`imageInstanceLimit`

统一规则（由 `InstanceLimits.parse` 实现）：

| 配置值 | 含义 |
|---|---|
| `null` / 空字符串 `""` | 不限制（解析为 `-1`，判定条件 `limit >= 0` 跳过） |
| 负整数（如 `-1`、`-5`） | 不限制（保留原始负数值，判定条件同样跳过） |
| `0` | 不允许启动任何新实例 |
| 正整数 `N` | 最多占用 `N` 个名额，`occupied >= N` 时拒绝 |
| `" 3"`、`"3a"`、`"abc"` 等 | 非法：保存时校验报错；运行时返回可读的否定结果，不再抛 `NumberFormatException` 崩溃 |

`kubeSettings.js` 的镜像上限输入校验使用与后端一致的严格整数规则
（`^-?\d+$`）：空值、负数、`0`、正整数按上表处理，`3a`/`3.5`/` 3` 在前端
即被标记；档案上限字段没有专门的前端校验，以后端
`KubeProfilePropertiesProcessor` 校验为准。无论前端是否拦截，保存时校验和
运行时解析都在后端独立执行，不信任浏览器。

注意 `Integer.parseInt` 不容忍前后空白，所以 `" 3"` 与 `"3a"` 一样会失败。
当前实现选择**严格规则**：`null`/长度为 0 的字符串视为未配置，其余值必须能被
`Integer.parseInt` 整体解析；纯空白串（如 `"   "`）同样判为非法并给出可读
错误，而不是悄悄当成 3 或当成空值。

### 保存时校验（`KubeProfilePropertiesProcessor`）

- 对顶层 `profileInstanceLimit` 和每个镜像 JSON 中的 `imageInstanceLimit`
  调用同一个 `InstanceLimits.validate`；
- 非法时加入 `InvalidProperty`，TeamCity 保存档案/测试档案设置时会在对应字段
  旁展示错误，阻止把坏配置落盘。

取舍：

- **优点**：错误最早暴露，用户正在表单上下文中，修复成本最低；保证落盘配置
  合法，所有后续读取方都受益。
- **局限**：`PropertiesProcessor` 只在经 TeamCity 正常保存流程
  （UI/REST 表单处理）时执行；直接改盘、导入配置、旧版本写入的脏值、绕过
  处理器的路径不会被回溯校验。因此不能只靠保存校验，必须有运行时兜底。
- 处理器只报告错误，不擅自改写用户输入（例如不主动 trim 后回写），避免
  “看到的值”和“实际保存值”不一致。

### 运行时容错（`InstanceLimits.parse` + client 捕获）

- `KubeCloudImageData.getInstanceLimit()` 与
  `KubeCloudClientParametersImpl.getInstanceLimit()` 改为通过
  `InstanceLimits.parse` 解析，非法值抛出带字段名、原始值和修复建议的
  `InvalidInstanceLimitException`（保留原始 `NumberFormatException` 为 cause），
  而不是裸 `NumberFormatException`。
- `canStartNewInstanceWithDetails` 分别包裹档案上限与镜像上限的读取，捕获
  `InvalidInstanceLimitException`，记录 warn 日志并返回
  `CanStartNewInstanceResult.no(可读信息)`：调度器得到的是“不能启动 + 原因”，
  而不是异常栈/档案加载失败。选择 **fail-closed（拒绝启动）** 而不是把非法值
  当成 `-1` 不限制或 `0`：静默放开可能在配置笔误时打垮集群，静默归零则会让
  用户误以为名额满；明确报错最安全。

取舍：

- **优点**：覆盖所有绕过保存校验的脏配置（手工编辑、导入、历史数据），不会
  让整个档案/client 构造崩溃；错误能通过 canStart 结果暴露给调度器和用户。
- **局限**：错误只在 TeamCity 调用 canStart 或真正读取该配置时可见；它不会
  主动在档案设置页顶部产生全局 banner（除非平台消费该 negative result）。
  另外，在当前快照代码中两个 limit 都是懒解析的，`KubeCloudClientFactory.
  createNewClient` 构造镜像时并不会调用 `getInstanceLimit()`，所以严格说
  “坏值导致整个档案无法加载”需要平台其他加载路径也调用该 getter 才会发生；
  运行时兜底同时消除了这条潜在路径上的 `NumberFormatException`。

## 6. 多镜像共享档案上限：不做公平分配

档案上限是档案下**所有镜像占用数之和**与同一个 `profileInstanceLimit` 比较。
当多个镜像同时有待启动构建、而剩余名额不足时，**哪个镜像/构建先获得名额由
TeamCity 服务器的构建调度顺序决定**（排队顺序、构建优先级、agent 兼容性匹配
等平台逻辑），本插件是无状态的应答方：`canStartNewInstanceWithDetails` 只
回答“以当前快照，这个镜像能不能再开一个实例”，不预留名额、不排队、不在镜像间
做配额分割或公平调度。

因此：

- 插件不保证多镜像间的公平分配，也不保证先请求的镜像一定先得到名额
  （canStart 可能被平台并发/重复调用，真正的记账以实例表快照为准）；
- 若需要给某类负载保底/封顶，应使用每镜像上限 `imageInstanceLimit`，或在
  TeamCity 侧用 agent pool / 构建配置等机制隔离；
- 快照是弱一致视图（`ConcurrentHashMap` 遍历），它保证单次判定内部数字一致，
  但不提供跨多次 canStart 调用的原子预留；高并发启动时仍可能出现平台调度层面
  的轻微超额，这属于 TeamCity 云调度契约的一部分，不在本题“公平分配”范围内。

## 7. 离线验证

`KubeCloudClientTest` 沿用既有 jmock `KubeCloudImage` 替身与
`MockCloudClientParameters`，新增：

- 对 `InstanceStatus.values()` 每个状态各构造一个实例，断言
  `InstanceQuotaSnapshot.groupOf/occupiesQuota` 与第 2 节对照表完全一致；
- 通过 `canStartNewInstanceWithDetails` 做端到端边界核对：每个“占用名额”的
  状态在 `limit=1` 且已有一个该状态实例时被拒，每个“不占用名额”的状态被放行；
- 构造 running/starting/terminating 混合实例，断言拒绝文案中的
  `occupied/running/starting/terminating` 数字与快照计数一致，且上限值出现；
- 档案级跨镜像求和、镜像级上限、`0`/负数/空值边界；
- 非法 `profileInstanceLimit`（`"3a"`、`" 3"`）返回可读 negative result 而不
  抛异常；
- `KubeProfilePropertiesProcessor` 的保存时校验测试（合法/非法/空值/负数）。
