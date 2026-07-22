package com.foxmimi.springaichat.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day31 离线观察：{@link MessageWindowChatMemory} 的窗口裁剪与会话隔离行为。
 * <p>
 * 全程直接对真实的 {@code MessageWindowChatMemory} + 默认内存仓库操作，<b>不发起任何模型调用</b>，
 * 因此确定、免费、可反复跑。目的是<b>观察</b>而非笼统跑通：逐轮打印窗口内容，亲眼看到
 * 「最旧消息被逐出、system 消息被保留」以及决定 2 里提到的「对齐到完整一轮（USER 起）」行为。
 * </p>
 * <p>
 * 断言只覆盖<b>铁定成立的不变量</b>（总数不超上限、system 恒在、会话互不串），
 * 而「maxMessages=12 到底稳定保留几轮」这类取决于 2.0.0 具体裁剪逻辑的结论，
 * 交给控制台打印来定论——对齐决定 2 留下的「需用真实 2.0.0 复核」的边界。
 * </p>
 */
class MessageWindowChatMemoryObservationTest {

    /**
     * 打印一个窗口的内容，形如：[SYSTEM] sys / [USER] U1 / [ASSISTANT] A1，便于肉眼观察逐出顺序。
     */
    private void dump(String label, List<Message> window) {
        StringBuilder sb = new StringBuilder(label).append("（共 ").append(window.size()).append(" 条）：\n");
        for (Message m : window) {
            sb.append("    [").append(m.getMessageType()).append("] ").append(m.getText()).append("\n");
        }
        System.out.println(sb);
    }

    private long countByType(List<Message> window, MessageType type) {
        return window.stream().filter(m -> m.getMessageType() == type).count();
    }

    // ---------------------------------------------------------------------
    // 观察 A：小窗口逐轮观察——看清「逐出最旧、保留 system」的机制
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("小窗口逐轮观察：最旧消息被逐出，system 始终保留")
    void observeEvictionRoundByRound() {
        // 故意把窗口调到很小（4），让逐出在几轮内就发生、看得见
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(4).build();
        String cid = "observe-eviction";

        // 先放一条 system——它占坑但按决定 2「永不被逐出」
        memory.add(cid, new SystemMessage("你是助手（system）"));

        // 逐轮加入 user/assistant，每轮后打印当前窗口
        for (int round = 1; round <= 4; round++) {
            memory.add(cid, new UserMessage("U" + round));
            memory.add(cid, new AssistantMessage("A" + round));
            dump("加入第 " + round + " 轮后", memory.get(cid));
        }

        List<Message> finalWindow = memory.get(cid);

        // 不变量断言（铁定成立的部分）
        assertThat(finalWindow).hasSizeLessThanOrEqualTo(4);              // 总数受 maxMessages 封顶
        assertThat(countByType(finalWindow, MessageType.SYSTEM)).isEqualTo(1); // system 保留、且只有 1 条
        assertThat(finalWindow.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM); // system 恒在最前
        // 最旧的 U1/A1 应已被逐出（窗口只有 4 条，装不下 1 system + 4 轮）
        assertThat(finalWindow).noneMatch(m -> "U1".equals(m.getText()));
    }

    // ---------------------------------------------------------------------
    // 观察 B：用生产值 maxMessages=12 + 1 system，实测「稳定保留几轮」
    //         —— 直接回应决定 2 悬而未决的问题
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("生产窗口 maxMessages=12 + 1 system：实测稳定保留几轮")
    void observeProductionWindowRounds() {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(12).build();
        String cid = "observe-12";

        memory.add(cid, new SystemMessage("你是助手（system）"));
        // 加 8 轮，远超 5 轮，逼出裁剪
        for (int round = 1; round <= 8; round++) {
            memory.add(cid, new UserMessage("U" + round));
            memory.add(cid, new AssistantMessage("A" + round));
        }

        List<Message> window = memory.get(cid);
        long userCount = countByType(window, MessageType.USER);
        dump("8 轮之后（maxMessages=12, 1 system）", window);
        System.out.println(">>> 实测：窗口内 USER 条数 = " + userCount
                + "，即稳定保留约 " + userCount + " 轮（对照决定 2 预期的 5 轮）");

        // 不变量：总数不超 12，system 恒在
        assertThat(window).hasSizeLessThanOrEqualTo(12);
        assertThat(countByType(window, MessageType.SYSTEM)).isEqualTo(1);
        // 具体几轮不硬断言，交给上面打印观察（避免把 main 分支假设当成 2.0.0 结论）
    }

    // ---------------------------------------------------------------------
    // 观察 C：会话隔离——不同 conversationId 的历史互不可见，A.clear 不影响 B
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("会话隔离：不同 conversationId 互不可见，clear 只清自己")
    void observeIsolation() {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(12).build();
        String a = "conv-A";
        String b = "conv-B";

        memory.add(a, new UserMessage("我叫陈明"));
        memory.add(b, new UserMessage("我叫李雷"));

        // A 的历史里绝不能出现 B 的内容，反之亦然
        assertThat(memory.get(a)).anyMatch(m -> "我叫陈明".equals(m.getText()));
        assertThat(memory.get(a)).noneMatch(m -> "我叫李雷".equals(m.getText()));
        assertThat(memory.get(b)).noneMatch(m -> "我叫陈明".equals(m.getText()));

        // 清空 A 不应波及 B
        memory.clear(a);
        assertThat(memory.get(a)).isEmpty();
        assertThat(memory.get(b)).anyMatch(m -> "我叫李雷".equals(m.getText()));
    }
}
