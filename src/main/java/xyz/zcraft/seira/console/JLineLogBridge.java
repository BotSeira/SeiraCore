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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

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

    static final class JLineAppender extends AbstractAppender {
        private final LineReader reader;
        private final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<>(1000);
        private final AtomicBoolean running = new AtomicBoolean();
        private Thread outputThread;

        JLineAppender(LineReader reader, Appender originalAppender) {
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
        public void start() {
            if (!running.compareAndSet(false, true)) return;
            super.start();
            outputThread = Thread.ofPlatform().daemon().name("seira-console-output").start(() -> {
                while (running.get()) {
                    try {
                        reader.printAbove(pending.take());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (RuntimeException ignored) {
                        // Never log back into this appender if the terminal cannot render a message.
                    }
                }
            });
        }

        @Override
        public void stop() {
            running.set(false);
            if (outputThread != null) outputThread.interrupt();
            pending.clear();
            super.stop();
        }

        @Override
        public void append(LogEvent event) {
            if (!running.get()) return;
            Serializable rendered = toSerializable(event);
            if (rendered != null) {
                // Logging while holding a business lock must never wait for JLine's reader lock.
                // Keep memory bounded if terminal output stalls; file appenders retain the full log.
                String text = rendered.toString();
                if (!pending.offer(text)) {
                    pending.poll();
                    pending.offer(text);
                }
            }
        }
    }
}
