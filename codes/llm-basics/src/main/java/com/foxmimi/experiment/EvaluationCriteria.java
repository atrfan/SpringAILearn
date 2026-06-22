package com.foxmimi.experiment;

import java.util.List;

/**
 * 评价标准，定义如何判断 LLM 回答的正确性。
 * <p>
 * 不同任务类型关注不同的评价维度：
 * <ul>
 *   <li>分类任务：检查输出是否在 {@code allowedLabels} 内、是否匹配 {@code expectedAnswer}</li>
 *   <li>开放生成：检查输出数量是否达到 {@code expectedItemCount}、每项长度是否不超过 {@code maxItemLength}</li>
 *   <li>幻觉探测：检查是否表达不确定性 ({@code mustExpressUncertainty})，并标记需要人工核查 ({@code manualFactCheckRequired})</li>
 * </ul>
 */
public record EvaluationCriteria(
        /** 评价标准的中文描述，供人工或自动化评判参考。 */
        String description,
        /** 分类任务的允许标签集合；为空列表表示不适用。 */
        List<String> allowedLabels,
        /** 分类任务的期望标准答案；{@code null} 表示不适用。 */
        String expectedAnswer,
        /** 开放生成任务期望的输出条目数；{@code null} 表示不适用。 */
        Integer expectedItemCount,
        /** 开放生成任务单条输出的最大长度（如汉字数）；{@code null} 表示不适用。 */
        Integer maxItemLength,
        /** 是否要求模型明确表达不确定性（幻觉探测任务使用）。 */
        boolean mustExpressUncertainty,
        /** 是否需要人工进行事实核查（幻觉探测任务使用）。 */
        boolean manualFactCheckRequired
) {
    /** 紧凑构造器：校验必填字段，防御性复制列表，确保数值参数合法。 */
    public EvaluationCriteria {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("评价标准不能为空");
        }
        allowedLabels = allowedLabels == null ? List.of() : List.copyOf(allowedLabels);
        if (expectedItemCount != null && expectedItemCount <= 0) {
            throw new IllegalArgumentException("expectedItemCount 必须大于 0");
        }
        if (maxItemLength != null && maxItemLength <= 0) {
            throw new IllegalArgumentException("maxItemLength 必须大于 0");
        }
    }
}
