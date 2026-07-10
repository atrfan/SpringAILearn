package com.foxmimi.springaichat.model;

/**
 * 这里是和extract.yaml文件输出的结果对应的类，后续会通过beanoutputconverter将ai输出的结果反序列化到这里
 * @param name  人物姓名
 * @param date  日期
 * @param amount    金额
 */
public record ExtractResult(
        String name,
        String date,
        String amount
) {
}
