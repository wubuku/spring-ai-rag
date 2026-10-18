# 可信评测、失败取证与变更准入进度

基线`3f5f22fa`；分支`feat/trustworthy-evaluation-admission`。
规划见[PLAN](2026-10-18_TRUSTWORTHY_EVALUATION_ADMISSION_PLAN.md)。

## 2026-10-18 启动

- 初始main干净；fetch后新建专用分支，同工作区开发，无并行worktree或子agent。
- 已核对定义/验证器/QA执行器、调用与证据边界、隐私/擦除、质量runner及发布脚本。
- 冻结“诊断不写正文数据库、新调用不冒充历史重现、Java/Python共享断言夹具”的低风险决策。
- 规划待连续3/3，生产代码尚未修改。

## 2026-10-18 18:55 规划收敛

- 连续三轮检查：18:53需求/自包含；18:53代码/授权/数据；18:55验收/成本/交付，均无问题和修改。
- 生产实现开始，总体约15%。无问题检查总结只输出会话，不以review代替真实测试。

## 2026-10-18 19:29 实现与验收进行中

- 已实现可选结构化断言/结果语义、主动同步诊断、稳定语料摘要、私有录制/本地重放/过期清理、离线基线准入及发布选项。
- v1语料与定义字节未改；v2独立版本；正式双语说明与六组入口同步。
- 隐私边界收紧：持久metrics仅位置/布尔结果，不含匹配文本；真实DOCUMENT_CONTEXT与来源投影分开；
  截断上限改为UTF-8字节并显式标识，避免“32K字符≈32KiB”错误。未扩大范围、无新增数据库表。
- `mvn clean compile test-compile`及全量`mvn test`通过：8687报告测试，0失败/错误；215为已有门控跳过，待显式PG补跑。
- 仓库门禁63/63、文档门禁16/16、旧Python13项/新Python12项通过。
- 新受控HTTP/PG验收已验证24例多轮、基线正反例、语料摘要、主动取证/纯本地重放、正常拒答与provider失败。
  首次使用长语料触发截断，未冒充完整重放；调整为小语料正例。503故障受既有重试退避影响超出测试客户端45秒，
  改用不重试的400故障覆盖失败采集分支；503恢复仍由既有双节点演练验收。测试夹具缺少Key到期/CAS参数已补齐。
- 双真实模型v2矩阵与主动诊断正在运行；完整发布/PG/旧双实例恢复/dev.sh验收、实现3/3及Git交付尚未完成。

## 2026-10-18 20:28 真实结果、修复与最终复验

- 双真实模型各24例×3轮完成：Grok `grok-4.5` 为69/72，MiniMax `MiniMax-M3` 为66/72，
  合计135/144。严格质量组合非零退出，不能批准为生产质量基线；所有逐轮失败均保留，没有修改v1/v2定义洗绿。
  Grok三次zero-loss为词法断言失败；MiniMax另有不足证据声明/引用及期待结果不符。
  原始metrics并未把这些失败标为结构化MODEL_SAFETY_REFUSAL，不猜测为安全政策拒答或服务故障。
- 两provider的真实Embedding导入/Search、HTTP rerank、外部Native/OpenAI JSON/SSE、官方Python/Node SDK均通过；
  两次current-price主动诊断complete=true且本地重放通过。正常安全拒答另由受控36组合及单次取证验证。
- 独立克隆库的新Grok zero-loss诊断回答数值0正确、未附量词“条”，因此不满足严格QUANTITY规则。
  这只能解释该次新调用，不是三次历史答案；长证据被截断，complete=false，不能声称完整重放。
  较早一次诊断被应用并发容量拒绝，未调用模型，也未当作模型质量证据。私有失败制品均保留。
- 全量Maven复跑8689报告测试，0失败/错误，215默认门控跳过；显式28套件PG矩阵231测试全过、无跳过，
  真实执行V1–V65。仓库门禁63全过；发布验收包含1030 Vitest、93 Mock Playwright、构建与官方SDK HTTP。
  发布脚本可选Docker/Helm/npm ci及部分runtime选项未启用，不冒称全部可选门禁通过；独立runtime验收另行记录。
- 新受控HTTP验收、旧双实例质量/恢复、依赖故障/k6/备份恢复均通过。
  dev首次启动失败：target内Service字节码含19处“Unresolved compilation problems”常量及错误RunRow类型描述符；
  当时有IDE Java语言服务在运行，但未断言具体写入者。保存错误字节码后clean构建恢复，不给产品代码打补丁掩盖产物污染。
  20:26 dev.sh完整启停、真实Embedding/LLM的PLAIN/KNOWLEDGE/AGENT/JSON/SSE、live OpenAPI与2项真实Playwright全过。
- 补查复现Java/Python对“一百〇五”的不一致：Java回归先红，随后将〇/两归一化到零/二；
  共享夹具增至37项，包含复合中文零、负数及非法零十，Python先行通过。最终源码的clean/全量/PG/HTTP/dev复验待完成。
  此修复和此前规划细化均使最终收敛计数保持0，不能用初期规划3/3冒充最终版本已检查。
- 私有证据根：`.verification/trustworthy-delivery/`、`.verification/trustworthy-release/`；
  双模型矩阵`.verification/quality-real/20261018-192728-9df86523/`，独立新诊断`20261018-195831-04edef95/`；
  受控HTTP`.verification/dependency-recovery/20261018-200210-7e0c644d/`、
  双节点`.verification/quality-production/20261018-200333-e7dcd3dc/`，第一次成功启停`.verification/quality-dev/20261018-202145-8a17b404/`。
  这些目录均为本地隐私证据，不提交Git、不作为他人必须持有的上下文。

## 最终复查补充：语料摘要累计资源上限

- 发现新摘要查询虽然结果有界，但每Collection固定LIMIT100001，会在之前已读取一部分集合后额外超读，
  不符合全请求最多100001个指纹的既定上限；SQL按摘要排序也会先计算超大集合的全部摘要。
- 已等待完整PG矩阵释放Maven后复现失败并修复：查询按剩余额度LIMIT，先截取有界行，
  再在Java对完整集合的指纹排序；超限仍只将相关摘要标为null，不改变既有Run API。
  新增跨Collection额度耗尽回归，将稳定摘要正例改为无序输入；定向测试先红，修复后全量Maven和受影响PG通过。
- 已完成的全矩阵保留为未受影响模块的证据；修复后重跑clean、全量Maven/仓库门禁、
  受影响Evaluation PG、新HTTP/dev门槛。最终检查计数仍为0，不以旧测试冒充该修复的验证。

## 最终源码门槛与上游可用性复核

- 最后资源上限修复后，clean compile/test-compile、全量Maven通过：8690报告测试、0失败/错误、215默认门控跳过；
  仓库63门禁通过。之前28套件真实PG矩阵231项全过，最后窄范围修复后另补受影响Evaluation PG 7项、无跳过。
- 最终源码受控HTTP验证通过：24例×8轮共192项，包含定义/基线摘要、内容更新/metadata漂移、
  双确认、实际prompt/evidence/answer、私有重放、正常安全拒答、provider永久失败、owner隔离和执行中权限撤回。
  新证据`.verification/dependency-recovery/20261018-205133-301240a3/`。
- 最终dev复验确认启动/状态成功，但上游Grok预探测60秒未获得HTTP响应，smoke非零退出；
  失败及自动清理记录保留在`.verification/quality-dev/20261018-205254-b6b15c75/`。
  该次旧smoke脚本打印的`/tmp` pong是旧残留，不能算作本次上游响应；HTTP000和失败状态没有改判。
  随后独立可用性探测一次HTTPError、一次HTTP200（4.67秒，实际grok-4.5-build），均保留结果，不猜测未记录的HTTP错误码。
  使用既有`REAL_LLM_PREFLIGHT_TIMEOUT_SECONDS=180`进行一次有界完整重验；未修改应用重试/路由，也未重跑质量矩阵到绿。
- 当前没有未处理的生产代码问题；完整运行复验结果见下一节。严格真实质量未达标这一限制继续保留。

## 2026-10-18 21:05 验收冻结与归档

- 最终源码的dev.sh完整复验已通过：启动/状态、13项真实Embedding/LLM验证、Native/OpenAI JSON/SSE、
  live OpenAPI兼容基线、2项真实Playwright、停止和端口无残留；证据
  `.verification/quality-dev/20261018-205959-2400779e/`，21:04:23结束。
  上一次超时失败仍保留，未把它或任意真实质量失败改判为通过。
- 最终生产源码自资源上限修复后没有再修改。先前真实144项矩阵在此窄范围边界修复前执行；
  不能宣称37项新词法边界或累计额度已由那次旧运行验证。它们由最后全量Java/Python、PG与HTTP补验覆盖，
  最终真实dev联调另验证完整主链。旧前端/SDK/双节点/备份演练证据只用于其未受影响范围。
- 两个真实失败质量制品另由离线准入读取：允许作为失败候选分析，均拒绝作为已批准baseline。
  不会因为Client协议、诊断重放或服务启停成功，就宣布135/144的严格质量矩阵通过。
- 正式双语说明和六组导航入口已提升；本plan/progress按生命周期归档。
  本文冻结后仅剩最终规划/实现连续只读3/3、Git commit/fetch/快进合回main/push与清洁检查。
  无问题审查不反写文档；各轮时间/范围/结果及Git结论由会话输出和本次提交记录追溯。
