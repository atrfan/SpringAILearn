package com.foxmimi.experiment.day05;

/**
 * 模型定价配置，用于计算实验的 API 调用成本。
 * <p>
 * 价格单位采用"每百万 Token 的美元价格"，与主流 API 供应商的定价页面保持一致。
 * 输入和输出通常有不同的单价，必须分别计算后求和。
 * <p>
 * <strong>重要：</strong>价格会随时间变化，不要硬编码。每次实验前应查询供应商的
 * 最新定价页面，并将来源 URL 和快照日期记录下来，确保成本计算可追溯。
 *
 * @param model             模型名称，如 "deepseek-chat"
 * @param priceDate         价格快照日期，如 "2026-06-24"
 * @param priceSourceUrl    价格来源页面的 URL
 * @param inputPricePerM    输入 Token 每百万的美元价格
 * @param outputPricePerM   输出 Token 每百万的美元价格
 */
public record Day05CostConfig(
        String model,
        String priceDate,
        String priceSourceUrl,
        double inputPricePerM,
        double outputPricePerM
) {
    /** 紧凑构造器：校验所有字段合法性。 */
    public Day05CostConfig {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (priceDate == null || priceDate.isBlank()) {
            throw new IllegalArgumentException("价格快照日期不能为空");
        }
        if (priceSourceUrl == null || priceSourceUrl.isBlank()) {
            throw new IllegalArgumentException("价格来源 URL 不能为空");
        }
        if (inputPricePerM < 0) {
            throw new IllegalArgumentException("输入价格不能为负数");
        }
        if (outputPricePerM < 0) {
            throw new IllegalArgumentException("输出价格不能为负数");
        }
    }

    /**
     * 根据 Token 数量计算成本（美元）。
     *
     * @param promptTokens     输入 Token 数
     * @param completionTokens 输出 Token 数
     * @return 总成本（美元）
     */
    public double calculateCost(int promptTokens, int completionTokens) {
        double inputCost = promptTokens * inputPricePerM / 1_000_000.0;
        double outputCost = completionTokens * outputPricePerM / 1_000_000.0;
        return inputCost + outputCost;
    }
}
