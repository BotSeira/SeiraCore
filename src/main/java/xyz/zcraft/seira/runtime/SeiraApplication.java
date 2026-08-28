package xyz.zcraft.seira.runtime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import xyz.zcraft.seira.binding.BindingService;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.QQBot;
import xyz.zcraft.seira.console.ConsoleCommandProcessor;
import xyz.zcraft.seira.console.JLineConsole;
import xyz.zcraft.seira.console.UserDataConsoleAccess;
import xyz.zcraft.seira.config.AppConfig;
import xyz.zcraft.seira.config.RuntimeConfig;
import xyz.zcraft.seira.security.AdminRegistry;
import xyz.zcraft.seira.services.BotStat;
import xyz.zcraft.seira.services.DailyLuck;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Composition root and single owner of all long-lived application resources. */
public final class SeiraApplication implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(SeiraApplication.class);

    private final ApplicationExecutors executors;
    private final BindingService bindingService;
    private final QQBot bot;
    private final JLineConsole console;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SeiraApplication(AppConfig config) {
        Objects.requireNonNull(config, "config");
        UserDataStore.init(config.seira().sqlitePath());
        RuntimeConfig createdRuntimeConfig = new RuntimeConfig(config);
        AdminRegistry createdAdmins = new AdminRegistry(config.seira().adminIds());
        createdRuntimeConfig.addReloadListener(result -> {
            createdAdmins.replaceConfigured(result.effectiveConfig().seira().adminIds());
            Configurator.setRootLevel(result.effectiveConfig().seira().debugMode() ? Level.DEBUG : Level.INFO);
        });

        LOG.info("Initializing application services");
        DailyLuck.initialize(config.qq().appId());
        BotStat.initialize();

        ApplicationExecutors createdExecutors = new ApplicationExecutors();
        BindingService createdBindingService = new BindingService(
                config.binding(), createdExecutors.commandTasks()
        );
        QQBot createdBot;
        try {
            createdBot = new QQBot(
                    createdRuntimeConfig,
                    createdAdmins,
                    createdBindingService,
                    createdExecutors
            );
        } catch (RuntimeException e) {
            createdBindingService.close();
            createdExecutors.close();
            BotStat.shutdown();
            throw e;
        }
        this.executors = createdExecutors;
        this.bindingService = createdBindingService;
        this.bot = createdBot;
        this.console = new JLineConsole(new ConsoleCommandProcessor(
                createdRuntimeConfig,
                createdAdmins,
                new UserDataConsoleAccess(),
                createdBot.getSender(),
                createdBot
        ));
    }

    public void run() {
        bindingService.start();
        console.start();
        bot.start();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Shutting down Seira");
        console.close();
        bot.stop();
        bindingService.close();
        executors.close();
        bot.close();
        DailyLuck.saveToFile();
        BotStat.shutdown();
        LOG.info("Shutdown complete");
    }
}
