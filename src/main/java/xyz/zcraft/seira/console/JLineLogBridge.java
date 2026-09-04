package xyz.zcraft.seira.console;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jline.reader.LineReader;

import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Routes terminal log events through JLine so the active prompt can be redrawn safely.
 */
final class JLineLogBridge implements AutoCloseable {
    private static final String CONSOLE_APPENDER_NAME = "CONSOLE";
    private static final String JLINE_APPENDER_NAME = "JLINE_CONSOLE";

    private final LoggerContext context;
    private final LoggerConfig rootLogger;
    private final Appender originalAppender;
    private final JLineAppender jLineAppender;
    private final Level appenderLevel;
    private final Filter appenderFilter;
    private final AtomicBoolean closed = new AtomicBoolean();

    private JLineLogBridge(
            LoggerContext context,
            LoggerConfig rootLogger,
            Appender originalAppender,
            JLineAppender jLineAppender,
            Level appenderLevel,
            Filter appenderFilter
    ) {
        this.context = context;
        this.rootLogger = rootLogger;
        this.originalAppender = originalAppender;
        this.jLineAppender = jLineAppender;
        this.appenderLevel = appenderLevel;
        this.appenderFilter = appenderFilter;
    }

    static JLineLogBridge install(LineReader reader) {
        Objects.requireNonNull(reader, "reader");
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        LoggerConfig rootLogger = configuration.getRootLogger();
        Appender originalAppender = rootLogger.getAppenders().get(CONSOLE_APPENDER_NAME);
        if (originalAppender == null) {
            return new JLineLogBridge(context, rootLogger, null, null, null, null);
        }

        AppenderRef originalRef = rootLogger.getAppenderRefs().stream()
                .filter(ref -> CONSOLE_APPENDER_NAME.equals(ref.getRef()))
                .findFirst()
                .orElse(null);
        Level level = originalRef == null ? null : originalRef.getLevel();
        Filter filter = originalRef == null ? null : originalRef.getFilter();
        JLineAppender replacement = new JLineAppender(reader, originalAppender);
        replacement.start();

        synchronized (rootLogger) {
            rootLogger.removeAppender(CONSOLE_APPENDER_NAME);
            rootLogger.addAppender(replacement, level, filter);
        }
        context.updateLoggers();
        return new JLineLogBridge(context, rootLogger, originalAppender, replacement, level, filter);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || originalAppender == null) {
            return;
        }
        synchronized (rootLogger) {
            rootLogger.removeAppender(JLINE_APPENDER_NAME);
            rootLogger.addAppender(originalAppender, appenderLevel, appenderFilter);
        }
        context.updateLoggers();
        jLineAppender.stop();
    }

    private static final class JLineAppender extends AbstractAppender {
        private final LineReader reader;

        private JLineAppender(LineReader reader, Appender originalAppender) {
            super(
                    JLINE_APPENDER_NAME,
                    null,
                    originalAppender.getLayout(),
                    originalAppender.ignoreExceptions(),
                    Property.EMPTY_ARRAY
            );
            this.reader = reader;
        }

        @Override
        public void append(LogEvent event) {
            Serializable rendered = toSerializable(event);
            if (rendered != null) {
                reader.printAbove(rendered.toString());
            }
        }
    }
}
