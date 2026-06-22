package com.foxmimi.experiment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link Day04ExperimentCatalog} 提供的预制用例是否符合预期结构。
 */
class Day04ExperimentCatalogTest {

    /** 验证 cases() 返回 3 个用例，覆盖全部 TaskType，且每个都有非空的评价标准。 */
    @Test
    void shouldProvideAllRequiredTaskTypesWithCriteria() {
        List<ExperimentCase> cases = Day04ExperimentCatalog.cases();

        assertEquals(3, cases.size());
        assertEquals(3, cases.stream().map(ExperimentCase::taskType).distinct().count());
        assertTrue(cases.stream().allMatch(testCase -> testCase.criteria() != null));
        assertTrue(cases.stream().allMatch(testCase ->
                !testCase.criteria().description().isBlank()));
    }

    /** 验证分类用例的评价标准：有标准答案、在允许标签内、无需人工核查。 */
    @Test
    void shouldDefineMachineCheckableClassificationCriteria() {
        EvaluationCriteria criteria = Day04ExperimentCatalog
                .classificationCase()
                .criteria();

        assertEquals("支付问题", criteria.expectedAnswer());
        assertTrue(criteria.allowedLabels().contains(criteria.expectedAnswer()));
        assertFalse(criteria.manualFactCheckRequired());
    }

    /** 验证幻觉探测用例：要求表达不确定性且需要人工事实核查。 */
    @Test
    void shouldRequireManualFactCheckForHallucinationProbe() {
        EvaluationCriteria criteria = Day04ExperimentCatalog
                .hallucinationProbeCase()
                .criteria();

        assertTrue(criteria.mustExpressUncertainty());
        assertTrue(criteria.manualFactCheckRequired());
        assertNotNull(criteria.description());
    }
}
