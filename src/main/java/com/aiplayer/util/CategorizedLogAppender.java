package com.aiplayer.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

public final class CategorizedLogAppender extends AbstractAppender {
    private static final String APPENDER_NAME = "AiPlayerCategorizedLog";
    private static final String PACKAGE_LOGGER = "com.aiplayer";
    private static volatile boolean installed;

    private CategorizedLogAppender() {
        super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        Appender appender = configuration.getAppender(APPENDER_NAME);
        if (appender == null) {
            appender = new CategorizedLogAppender();
            appender.start();
            configuration.addAppender(appender);
        }
        if (!configuration.getRootLogger().getAppenders().containsKey(APPENDER_NAME)) {
            configuration.getRootLogger().addAppender(appender, Level.ALL, null);
        }

        enableAiPlayerPackageLogger(configuration);
        context.updateLoggers();
        installed = true;
    }

    @Override
    public void append(LogEvent event) {
        LogEvent immutableEvent = event == null ? null : event.toImmutable();
        if (!CategorizedLogWriter.shouldWrite(immutableEvent)) {
            return;
        }
        try {
            CategorizedLogWriter.write(immutableEvent);
        } catch (Exception ignored) {
        }
    }

    private static void enableAiPlayerPackageLogger(Configuration configuration) {
        LoggerConfig loggerConfig = configuration.getLoggers().get(PACKAGE_LOGGER);
        if (loggerConfig == null) {
            configuration.addLogger(PACKAGE_LOGGER, new LoggerConfig(PACKAGE_LOGGER, Level.ALL, true));
            return;
        }
        loggerConfig.setLevel(Level.ALL);
    }
}
