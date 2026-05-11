package com.aiplayer.execution;

import com.aiplayer.entity.AiPlayerEntity;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

public class CodeExecutionEngine {
    private final AiPlayerEntity aiPlayer;
    private final Context graalContext;
    private final AiPlayerAPI aiPlayerAPI;

    private static final long DEFAULT_TIMEOUT_MS = 30000;

    public CodeExecutionEngine(AiPlayerEntity aiPlayer) {
        this.aiPlayer = aiPlayer;
        this.aiPlayerAPI = new AiPlayerAPI(aiPlayer);
        this.graalContext = Context.newBuilder("js")
            .allowAllAccess(false)
            .allowIO(false)
            .allowNativeAccess(false)
            .allowCreateThread(false)
            .allowCreateProcess(false)
            .allowHostClassLookup(className -> false)
            .allowHostAccess(null)
            .option("js.java-package-globals", "false")
            .option("js.timer-resolution", "1")
            .build();
        graalContext.getBindings("js").putMember("aiPlayer", aiPlayerAPI);
        String consolePolyfill = """
            var console = {
                log: function(...args) {
                    java.lang.System.out.println('[AiPlayer Code] ' + args.join(' '));
                }
            };
            """;

        try {
            graalContext.eval("js", consolePolyfill);
        } catch (PolyglotException e) {
        }
    }

        public ExecutionResult execute(String code) {
        return execute(code, DEFAULT_TIMEOUT_MS);
    }

        public ExecutionResult execute(String code, long timeoutMs) {
        if (code == null || code.trim().isEmpty()) {
            return ExecutionResult.error("No code provided");
        }

        try {
            Value result = graalContext.eval("js", code);
            String output = result.isNull() ? "null" : result.toString();

            return ExecutionResult.success(output);

        } catch (PolyglotException e) {
            if (e.isExit()) {
                return ExecutionResult.error("Code called exit: " + e.getExitStatus());
            }

            if (e.isInterrupted()) {
                return ExecutionResult.error("Execution interrupted (timeout?)");
            }

            if (e.isSyntaxError()) {
                return ExecutionResult.error("Syntax error: " + e.getMessage());
            }
            String errorMsg = e.getMessage();
            if (errorMsg == null || errorMsg.isEmpty()) {
                errorMsg = "Unknown execution error";
            }

            return ExecutionResult.error("Error: " + errorMsg);

        } catch (Exception e) {
            return ExecutionResult.error("Unexpected error: " + e.getMessage());
        }
    }

        public boolean validateSyntax(String code) {
        try {
            graalContext.eval("js", "function __validate() { " + code + " }");
            return true;
        } catch (PolyglotException e) {
            return false;
        }
    }

        public AiPlayerAPI getAPI() {
        return aiPlayerAPI;
    }

        public void close() {
        if (graalContext != null) {
            graalContext.close();
        }
    }

        public static class ExecutionResult {
        private final boolean success;
        private final String output;
        private final String error;

        private ExecutionResult(boolean success, String output, String error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }

        public static ExecutionResult success(String output) {
            return new ExecutionResult(true, output, null);
        }

        public static ExecutionResult error(String error) {
            return new ExecutionResult(false, null, error);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public String getError() {
            return error;
        }

        @Override
        public String toString() {
            if (success) {
                return "Success: " + output;
            } else {
                return "Error: " + error;
            }
        }
    }
}
