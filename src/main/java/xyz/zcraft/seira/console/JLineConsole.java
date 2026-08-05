package xyz.zcraft.seira.console;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Interactive local administration console backed by JLine. */
public final class JLineConsole implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(JLineConsole.class);
    private static final String PROMPT = "seira> ";

    private final ConsoleCommandProcessor processor;
    private final ExecutorService consoleThread = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("seira-console").factory()
    );
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Terminal terminal;

    public JLineConsole(ConsoleCommandProcessor processor) {
        this.processor = processor;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            consoleThread.execute(this::runLoop);
        }
    }

    private void runLoop() {
        try (Terminal createdTerminal = TerminalBuilder.builder().system(true).build()) {
            terminal = createdTerminal;
            Files.createDirectories(Path.of("data"));
            LineReader reader = LineReaderBuilder.builder()
                    .appName("SeiraCore")
                    .terminal(createdTerminal)
                    .parser(new DefaultParser())
                    .completer(new CommandCompleter())
                    .variable(LineReader.HISTORY_FILE, Path.of("data", "console-history"))
                    .variable(LineReader.HISTORY_SIZE, 500)
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .build();

            while (running.get()) {
                try {
                    String line = reader.readLine(PROMPT);
                    ConsoleCommandProcessor.ConsoleResult result = processor.execute(line);
                    if (!result.message().isBlank()) {
                        reader.printAbove((result.success() ? "" : "错误：") + result.message());
                    }
                } catch (UserInterruptException ignored) {
                } catch (EndOfFileException e) {
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            if (running.get()) {
                LOG.error("Interactive console stopped unexpectedly", e);
            }
        } finally {
            terminal = null;
            running.set(false);
        }
    }

    @Override
    public void close() {
        running.set(false);
        Terminal current = terminal;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                LOG.warn("Failed to close interactive console terminal", e);
            }
        }
        consoleThread.shutdownNow();
    }

    private static final class CommandCompleter implements Completer {
        private static final Map<String, List<String>> SUBCOMMANDS = Map.of(
                "config", List.of("show", "reload"),
                "admin", List.of("list", "add", "remove"),
                "data", List.of("stats", "query"),
                "send", List.of("group", "private")
        );
        private static final List<String> ROOT_COMMANDS = List.of("help", "config", "admin", "data", "send", "stop");

        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            List<String> values;
            if (line.wordIndex() == 0) {
                values = ROOT_COMMANDS;
            } else if (line.wordIndex() == 1 && !line.words().isEmpty()) {
                values = SUBCOMMANDS.getOrDefault(line.words().getFirst().toLowerCase(Locale.ROOT), List.of());
            } else {
                values = List.of();
            }
            values.forEach(value -> candidates.add(new Candidate(value)));
        }
    }
}
