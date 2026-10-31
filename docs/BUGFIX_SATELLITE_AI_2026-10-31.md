# 卫星数据/AI 工具缺陷修复（2026-10-31）

本文记录 `hamkit-ai.md` 中卫星查询异常的真实根因与修复，重点纠正原诊断中
“过境结果被同一次计算复用”的误判。

## 现象

- 卫星列表中 20 颗卫星的 `next_aos` 完全相同，且已早于当前时间。
- `visible` 全部为 `true`。
- `modes` 大面积为空。
- `get_satellite_passes("ISS")` / `get_satellite_live("ISS")` 命中 `OUTPOST MISSION2`（NORAD 58334）。

## 根因

### 1. 名称解析静默取子串第一条

`AiAppFacadeImpl.resolveSatellite()` 使用 `name.contains(query)` 并取预测列表第一条。
`OUTPOST MISSION2` 的 `MISSION` 含子串 `ISS`，因此 `ISS` 被错误解析到 58334；
真正的 `ISS (ZARYA)` / `ISS (NAUKA)` 反而排在后面。

修复：新增 `SatelliteNameMatcher`，按
“NORAD 全等 → 名称/主名称全等 → 前缀 → 单词边界 → 子串”分层匹配。
最高层命中多颗时返回歧义，工具层提示用 NORAD 指定，不再静默选第一条。

### 2. 在境卫星的 AOS 被写成预测时刻

`SatellitePredictor.findNextPass()` 在“当前在境”分支直接把 `aosMs` 设为 `fromMs`
（预测基准时刻）。同一批在境卫星共享同一个 `now`，所以 `next_aos` 全部相同、
且查询时已过去——这并不是跨卫星缓存复用，代码中不存在按卫星复用的预测缓存。

修复：在境时向前回溯（5 分钟步长，最多 `min(1.5 × 轨道周期, 6h)`）找到本次过境
真实 AOS，再用二分法精化；峰值改为在完整 `[AOS, LOS]` 窗口内重新扫描。
回溯失败（长时间在境/异常 TLE）才回退到预测时刻。
GEO 恒定可见仍用 `AOS=now / LOS=now+24h`，但输出 `is_geo=true` 明确区分。

### 3. `modes` 只读硬编码目录

`SatellitePredictor` 仅使用 `SatelliteCatalog.MODES_BY_CATALOG_NUMBER`，
目录外卫星（如只有 SatNOGS 转发器的 `OUTPOST MISSION2`）模式为空。

修复：新增 `SatelliteCatalog.effectiveModes(catalogNumber, radios)`，
聚合“活跃转发器模式 ∪ 目录模式”并去重；预测器、卫星列表项共用这一份逻辑。
`MainViewModel` 预测时传入转发器库。

### 4. 搜索排序被 `visible` 支配

`search_satellites` 先按 `isCurrentlyVisible` 排序，子串假阳性卫星可能排在
名称精确匹配的卫星前面。

修复：query 非空时先按 `SatelliteNameMatcher` 匹配层级排序，再按在境、AOS 排序。

## 输出语义调整

- `search_satellites`：`next_aos` 仅在 AOS 位于未来时输出；在境卫星输出
  `in_pass_since`，并增加 `is_geo`、`in_pass_count`。
- `get_satellite_passes`：输出 `in_pass` / `is_geo`，在境时给出 `note` 说明
  AOS 已过去、请用 LOS 判断剩余时间。
- AI 卫星卡片的过境行在 `isInPass` 时标注“正在过境”。

## 缓存迁移

`SatellitePredictCacheStore` 增加缓存版本 `v=2`。读取到无版本或旧版本的缓存时
视为无缓存，强制重新预测，避免修复后 2 小时内仍回填旧的“AOS=now”数据。

## 测试

新增/更新：

- `SatelliteNameMatcherTest`：`ISS` 歧义候选只含 ZARYA/NAUKA，不含 MISSION2；
  精确名称、NORAD、别名、排序。
- `SatellitePredictorInPassTest`：在境卫星 AOS 回溯到真实过境开始；
  两颗 LEO 不共享 AOS；模式聚合忽略非活跃转发器。
- `SatelliteCatalogTest`：`effectiveModes` 合并/去重/目录外卫星。
- `SatellitePredictCacheStoreTest`：v1 缓存失效、v2 回填。

## 说明

- 非业余卫星（INMARSAT/BEIDOU 等）出现在预测范围是现有产品行为：
  预测目标为“有活跃转发器的卫星或目录卫星”。本次不改变该范围。
- `SatellitePropagator` / `Sgp4Satellite` 的注释修正为“非线程安全、单实例单线程使用”，
  与 `propagate()` 实际写入实例状态的实现一致。
