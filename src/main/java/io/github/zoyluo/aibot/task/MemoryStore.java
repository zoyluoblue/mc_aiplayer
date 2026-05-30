package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.brain.ChatMessage;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.memory.BotMemoryStore;

import java.util.ArrayList;
import java.util.List;

public final class MemoryStore {
    public static final MemoryStore INSTANCE = new MemoryStore();

    private static final int KEEP_MESSAGES = 12;
    private static final int MAX_MESSAGES = 20;

    private MemoryStore() {
    }

    public List<ChatMessage> prepareHistory(AIPlayerEntity bot, List<ChatMessage> rawHistory) {
        List<ChatMessage> withMemory = injectPersistentMemory(bot, rawHistory);
        if (withMemory.size() <= MAX_MESSAGES) {
            return sanitize(withMemory);
        }
        List<ChatMessage> compact = new ArrayList<>();
        ChatMessage first = withMemory.getFirst();
        if ("system".equals(first.role())) {
            compact.add(first);
        }
        String memory = BotMemoryStore.INSTANCE.of(bot.getUuid()).inject();
        if (!memory.isBlank()) {
            compact.add(ChatMessage.system("Persistent memory:\n" + memory));
        }
        TaskStatus status = TaskManager.INSTANCE.status(bot);
        if (!"idle".equals(status.name())) {
            compact.add(ChatMessage.system("Current task: " + status.description()
                    + ". State=" + status.state()
                    + ", progress=" + String.format(java.util.Locale.ROOT, "%.2f", status.progress())));
        }
        int start = Math.max("system".equals(first.role()) ? 1 : 0, withMemory.size() - KEEP_MESSAGES);
        compact.add(ChatMessage.system("Earlier conversation summary: older messages were compacted locally to keep the context short."));
        compact.addAll(withMemory.subList(start, withMemory.size()));
        return sanitize(compact);
    }

    /**
     * 发送前兜底:保证 OpenAI/DeepSeek 的消息配对合法,杜绝 400
     * "Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"。
     * 任何 trim/截断/注入把 assistant(tool_calls) 与其后的 tool 回应切断后,这里统一修正:
     * - 丢弃"孤儿 tool"(前一条不是带 tool_calls 的 assistant、也不是另一条 tool)。
     * - assistant 带 tool_calls 但后面没有 tool 回应(被裁断)→ 剥掉 tool_calls 降级为普通 assistant。
     */
    private static List<ChatMessage> sanitize(List<ChatMessage> in) {
        List<ChatMessage> out = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            ChatMessage m = in.get(i);
            if ("tool".equals(m.role())) {
                ChatMessage prev = out.isEmpty() ? null : out.get(out.size() - 1);
                boolean validResponse = prev != null
                        && (("assistant".equals(prev.role()) && hasToolCalls(prev)) || "tool".equals(prev.role()));
                if (validResponse) {
                    out.add(m);
                }
                // 否则:孤儿 tool,丢弃
            } else if ("assistant".equals(m.role()) && hasToolCalls(m)) {
                boolean toolFollows = (i + 1 < in.size()) && "tool".equals(in.get(i + 1).role());
                if (toolFollows) {
                    out.add(m);
                } else {
                    out.add(ChatMessage.assistant(m.content() == null ? "" : m.content(), null));
                }
            } else {
                out.add(m);
            }
        }
        return out;
    }

    private static boolean hasToolCalls(ChatMessage m) {
        return m.toolCalls() != null && !m.toolCalls().isEmpty();
    }

    private List<ChatMessage> injectPersistentMemory(AIPlayerEntity bot, List<ChatMessage> rawHistory) {
        String memory = BotMemoryStore.INSTANCE.of(bot.getUuid()).inject();
        if (memory.isBlank()) {
            return rawHistory;
        }
        List<ChatMessage> result = new ArrayList<>(rawHistory.size() + 1);
        if (!rawHistory.isEmpty() && "system".equals(rawHistory.getFirst().role())) {
            result.add(rawHistory.getFirst());
            result.add(ChatMessage.system("Persistent memory:\n" + memory));
            result.addAll(rawHistory.subList(1, rawHistory.size()));
        } else {
            result.add(ChatMessage.system("Persistent memory:\n" + memory));
            result.addAll(rawHistory);
        }
        return result;
    }
}
