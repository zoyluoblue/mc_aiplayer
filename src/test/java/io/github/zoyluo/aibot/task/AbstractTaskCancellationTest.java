package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AbstractTaskCancellationTest {
    @Test
    void userCancellationIsTerminalButNotFailed() {
        TestTask task = new TestTask();

        task.cancel(null, "cancelled:test");
        task.abort(null);
        task.cancel(null, "cancelled:again");

        assertEquals(TaskState.CANCELLED, task.state());
        assertEquals("cancelled:test", task.failureReason());
        assertEquals(1, task.cleanupCalls);
    }

    private static final class TestTask extends AbstractTask {
        private int cleanupCalls;

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String describe() {
            return "test";
        }

        @Override
        public double progress() {
            return 0.0D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }

        @Override
        protected void onAbort(AIPlayerEntity bot) {
            cleanupCalls++;
        }
    }
}
