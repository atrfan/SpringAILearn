# Day05 任务覆盖与成本分析报告

- 生成时间：2026-06-24T02:03:21.712737800Z
- 任务总数：21
- API 成功数：21
- 覆盖任务类型：7 种

## 按任务类型汇总

| 任务类型 | 任务数 | API成功 | 任务成功率 | 平均输入字符 | 平均输入Token | 平均输出Token | 平均延迟(ms) |
|---|---:|---:|---:|---:|---:|---:|---:|
|CLASSIFICATION|3|3|66.7%|54.33|55.33|78.00|2116.00|
|OPEN_GENERATION|3|3|66.7%|34.67|40.00|382.67|5880.00|
|FACT_QA|3|3|100.0%|23.33|30.33|117.67|2675.67|
|SUMMARIZATION|3|3|66.7%|112.00|85.00|308.67|3926.67|
|INFORMATION_EXTRACTION|3|3|100.0%|101.33|76.33|116.67|2281.33|
|AMBIGUITY_DETECTION|3|3|66.7%|6.67|26.67|85.33|2250.67|
|REFUSAL_DETECTION|3|3|100.0%|16.33|28.00|72.00|1920.00|

## 按输入长度排序（分析长度对 Token 和延迟的影响）

| # | 任务ID | 任务类型 | 输入字符 | 输入Token | 输出Token | 延迟(ms) | Token/字符 |
|---:|---|---|---:|---:|---:|---:|---:|
|1|ambiguity-002|AMBIGUITY_DETECTION|5|26|112|2490|5.20|
|2|ambiguity-001|AMBIGUITY_DETECTION|6|27|68|2167|4.50|
|3|ambiguity-003|AMBIGUITY_DETECTION|9|27|76|2095|3.00|
|4|refusal-002|REFUSAL_DETECTION|15|29|80|1657|1.93|
|5|refusal-001|REFUSAL_DETECTION|17|28|84|2015|1.65|
|6|refusal-003|REFUSAL_DETECTION|17|27|52|2088|1.59|
|7|fact-qa-003|FACT_QA|20|28|66|1731|1.40|
|8|fact-qa-002|FACT_QA|24|30|34|1360|1.25|
|9|fact-qa-001|FACT_QA|26|33|253|4936|1.27|
|10|open-generation-002|OPEN_GENERATION|31|38|51|1594|1.23|
|11|open-generation-001|OPEN_GENERATION|34|39|1024|13399|1.15|
|12|open-generation-003|OPEN_GENERATION|39|43|73|2647|1.10|
|13|classification-003|CLASSIFICATION|47|51|169|3033|1.09|
|14|classification-001|CLASSIFICATION|55|56|42|1596|1.02|
|15|classification-002|CLASSIFICATION|61|59|23|1719|0.97|
|16|extraction-003|INFORMATION_EXTRACTION|82|77|226|3512|0.94|
|17|extraction-001|INFORMATION_EXTRACTION|93|77|73|1756|0.83|
|18|summarization-003|SUMMARIZATION|94|73|455|5142|0.78|
|19|summarization-001|SUMMARIZATION|116|87|389|4499|0.75|
|20|summarization-002|SUMMARIZATION|126|95|82|2139|0.75|
|21|extraction-002|INFORMATION_EXTRACTION|129|75|51|1576|0.58|

## 成本估算

| 项目 | 值 |
|---|---|
| 模型 | deepseek-v4-pro |
| 价格日期 | 2026-06-24 |
| 价格来源 | https://api-docs.deepseek.com/zh-cn/quick_start/pricing |
| 输入单价（$/M tokens） | 1.0000 |
| 输出单价（$/M tokens） | 2.0000 |
| 成功调用数 | 21 |
| 总输入 Token | 1025 |
| 总输出 Token | 3483 |
| 总 Token | 4508 |
| 输入成本 | $0.001025 |
| 输出成本 | $0.006966 |
| **总成本** | **$0.007991** |

## 解释边界

- 每条任务只执行 1 次，结果不具备统计显著性，不能外推为稳定性能结论。
- 任务成功率基于关键词或格式检查，不能替代人工语义评估。
- 事实错误率需要人工核查原始回答后补录。
- Token/字符比值受文本语言、tokenizer 和系统提示词共同影响。
- 成本基于快照价格计算，实际账单可能与估算存在偏差。
- 延迟受网络状况、服务端负载和模型推理时间共同影响，单次测量波动较大。
