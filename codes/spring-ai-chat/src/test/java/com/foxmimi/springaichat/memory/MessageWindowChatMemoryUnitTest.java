package com.foxmimi.springaichat.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day33 离线断言测试：{@link MessageWindowChatMemory} + 默认内存仓库的核心行为。
 * <p>
 * 与 Day31 的 {@link MessageWindowChatMemoryObservationTest} 分工不同：那份是<b>观察式</b>
 * （大量打印、只断言铁定不变量、故意不硬断言"到底几轮"）；本份是<b>断言式单元测试</b>，
 * 对存取 / 隔离 / 裁剪 / 边界四类行为做<b>硬断言</b>，作为本周验收素材。
 * </p>
 * <p>
 * 全程直接对真实 {@code MessageWindowChatMemory} 操作，<b>不发起任何模型调用</b>——确定、免费、
 * 默认 {@code mvn test} 即可跑（无 {@code @Tag("integration")}，不触发 excludedGroups）。
 * </p>
 */
class MessageWindowChatMemoryUnitTest {

    /** 每个用例都用全新窗口，避免用例间串状态。默认窗口够大，专为存取/隔离用例服务。 */
    private ChatMemory newMemory(int maxMessages) {
        return MessageWindowChatMemory.builder().maxMessages(maxMessages).build();
    }

    private long countByType(List<Message> window, MessageType type) {
        return window.stream().filter(m -> m.getMessageType() == type).count();
    }

    // =====================================================================
    // 一、历史存取
    // =====================================================================
    @Nested
    @DisplayName("历史存取")
    class HistoryReadWrite {

        @Test
        @DisplayName("add 单条后 get 能原样取回")
        void addThenGet() {
            ChatMemory memory = newMemory(12);
            String cid = "rw-basic";

            memory.add(cid, new UserMessage("我叫陈明"));

            List<Message> window = memory.get(cid);
            assertThat(window).hasSize(1);
            assertThat(window.get(0).getMessageType()).isEqualTo(MessageType.USER);
            assertThat(window.get(0).getText()).isEqualTo("我叫陈明");
        }

        @Test
        @DisplayName("多轮 add 后 get 保持写入顺序（user/assistant 交替）")
        void multiRoundKeepsOrder() {
            ChatMemory memory = newMemory(12);
            String cid = "rw-order";

            memory.add(cid, new UserMessage("U1"));
            memory.add(cid, new AssistantMessage("A1"));
            memory.add(cid, new UserMessage("U2"));
            memory.add(cid, new AssistantMessage("A2"));

            List<Message> window = memory.get(cid);
            assertThat(window).extracting(Message::getText)
                    .containsExactly("U1", "A1", "U2", "A2");
        }

        @Test
        @DisplayName("按 conversationId 取到正确的那条线，各取各的")
        void getByConversationIdReturnsOwnLine() {
            ChatMemory memory = newMemory(12);
            memory.add("line-A", new UserMessage("A 的消息"));
            memory.add("line-B", new UserMessage("B 的消息"));

            assertThat(memory.get("line-A")).extracting(Message::getText)
                    .containsExactly("A 的消息");
            assertThat(memory.get("line-B")).extracting(Message::getText)
                    .containsExactly("B 的消息");
        }
    }

    // =====================================================================
    // 二、会话隔离
    // =====================================================================
    @Nested
    @DisplayName("会话隔离")
    class Isolation {

        @Test
        @DisplayName("两个 conversationId 的内容双向互不可见")
        void twoConversationsMutuallyInvisible() {
            ChatMemory memory = newMemory(12);
            memory.add("conv-A", new UserMessage("我叫陈明"));
            memory.add("conv-B", new UserMessage("我叫李雷"));

            assertThat(memory.get("conv-A")).noneMatch(m -> "我叫李雷".equals(m.getText()));
            assertThat(memory.get("conv-B")).noneMatch(m -> "我叫陈明".equals(m.getText()));
        }

        @Test
        @DisplayName("clear(A) 不影响 B")
        void clearOneDoesNotAffectOther() {
            ChatMemory memory = newMemory(12);
            memory.add("conv-A", new UserMessage("我叫陈明"));
            memory.add("conv-B", new UserMessage("我叫李雷"));

            memory.clear("conv-A");

            assertThat(memory.get("conv-A")).isEmpty();
            assertThat(memory.get("conv-B")).extracting(Message::getText)
                    .containsExactly("我叫李雷");
        }

        @Test
        @DisplayName("并发写不同 conversationId 互不干扰")
        void concurrentWritesToDifferentIdsDoNotInterfere() throws InterruptedException {
            ChatMemory memory = newMemory(12);
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                final int id = i;
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();                       // 所有线程同一刻起跑，尽量制造真并发
                        memory.add("conv-" + id, new UserMessage("msg-" + id));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            // 每条线都只应看到自己那条消息，绝不串到别的 id
            for (int i = 0; i < threads; i++) {
                assertThat(memory.get("conv-" + i)).extracting(Message::getText)
                        .containsExactly("msg-" + i);
            }
        }
    }

    // =====================================================================
    // 三、窗口裁剪
    // =====================================================================
    @Nested
    @DisplayName("窗口裁剪")
    class WindowTrimming {

        @Test
        @DisplayName("超过 maxMessages 后最旧消息被逐出")
        void oldestEvictedWhenOverflow() {
            ChatMemory memory = newMemory(4);               // 窗口只装 4 条
            String cid = "trim-overflow";

            memory.add(cid, new UserMessage("U1"));
            memory.add(cid, new AssistantMessage("A1"));
            memory.add(cid, new UserMessage("U2"));
            memory.add(cid, new AssistantMessage("A2"));
            memory.add(cid, new UserMessage("U3"));
            memory.add(cid, new AssistantMessage("A3"));     // 共 6 条，超出 4

            List<Message> window = memory.get(cid);
            assertThat(window).hasSize(4);
            // 最旧的 U1/A1 被逐出，保留最近两轮
            assertThat(window).extracting(Message::getText)
                    .containsExactly("U2", "A2", "U3", "A3");
        }

        @Test
        @DisplayName("裁剪时 system 消息占坑但永不被逐出、恒在最前")
        void systemMessageSurvivesEviction() {
            ChatMemory memory = newMemory(4);
            String cid = "trim-system";

            memory.add(cid, new SystemMessage("你是助手"));   // 占 1 坑
            for (int round = 1; round <= 4; round++) {       // 加 4 轮，远超窗口
                memory.add(cid, new UserMessage("U" + round));
                memory.add(cid, new AssistantMessage("A" + round));
            }

            List<Message> window = memory.get(cid);
            assertThat(window).hasSizeLessThanOrEqualTo(4);
            assertThat(countByType(window, MessageType.SYSTEM)).isEqualTo(1);          // system 仍在、且唯一
            assertThat(window.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);  // 恒在最前
            assertThat(window).noneMatch(m -> "U1".equals(m.getText()));               // 被逐出的是非 system 的最旧消息
        }

        @Test
        @DisplayName("消息数恰好等于 maxMessages 时不发生裁剪")
        void exactlyAtCapacityNoTrim() {
            ChatMemory memory = newMemory(4);
            String cid = "trim-exact";

            memory.add(cid, new UserMessage("U1"));
            memory.add(cid, new AssistantMessage("A1"));
            memory.add(cid, new UserMessage("U2"));
            memory.add(cid, new AssistantMessage("A2"));     // 恰好 4 条 = maxMessages

            List<Message> window = memory.get(cid);
            assertThat(window).hasSize(4);
            assertThat(window).extracting(Message::getText)
                    .containsExactly("U1", "A1", "U2", "A2"); // 一条不少
        }
    }

    // =====================================================================
    // 四、边界与 clear
    // =====================================================================
    @Nested
    @DisplayName("边界与 clear")
    class BoundaryAndClear {

        @Test
        @DisplayName("未知 conversationId 的空历史 get 返回空列表、不报错")
        void getUnknownIdReturnsEmpty() {
            ChatMemory memory = newMemory(12);
            assertThat(memory.get("never-used")).isEmpty();
        }

        @Test
        @DisplayName("clear 后 get 为空")
        void getAfterClearIsEmpty() {
            ChatMemory memory = newMemory(12);
            String cid = "to-clear";
            memory.add(cid, new UserMessage("待清除"));

            memory.clear(cid);

            assertThat(memory.get(cid)).isEmpty();
        }

        @Test
        @DisplayName("空白/null conversationId 抛 IllegalArgumentException；非 UUID 字符串被正常接受")
        void blankIdRejectedButNonUuidAccepted() {
            ChatMemory memory = newMemory(12);

            // 决定 4 的机制来源：add/get/clear 均对空白 id 抛 IllegalArgumentException
            // （这正是 advisor 缺 CONVERSATION_ID 抛错、最终映射 400 的底层原因）
            assertThatThrownBy(() -> memory.get(""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> memory.get("   "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> memory.get(null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> memory.add("", List.of(new UserMessage("x"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> memory.clear(" "))
                    .isInstanceOf(IllegalArgumentException.class);

            // 只校非空白、不强校 UUID 格式（决定 4）：非 UUID 串是合法 id，get 正常返回空
            assertThat(memory.get("not-a-uuid")).isEmpty();
        }
    }
}
