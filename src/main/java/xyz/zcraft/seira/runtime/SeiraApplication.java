package xyz.zcraft.seira.runtime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.binding.BindingService;
import xyz.zcraft.seira.binding.UserDataStore;
import xyz.zcraft.seira.bot.QQBot;
import xyz.zcraft.seira.config.AppConfig;
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
    private final AtomicBoolean closed = new AtomicBoolean();

    public SeiraApplication(AppConfig config) {
        Objects.requireNonNull(config, "config");
        UserDataStore.init(config.seira().sqlitePath());

        LOG.info("Initializing application services");
        DailyLuck.initialize(config.qq().appId());
        BotStat.initialize();

        ApplicationExecutors createdExecutors = new ApplicationExecutors();
        BindingService createdBindingService = new BindingService(
                config.binding(), createdExecutors.commandTasks()
        );
        try {
            this.bot = new QQBot(config, createdBindingService, createdExecutors);
        } catch (RuntimeException e) {
            createdBindingService.close();
            createdExecutors.close();
            BotStat.shutdown();
            throw e;
        }
        this.executors = createdExecutors;
        this.bindingService = createdBindingService;
    }

    public void run() {
        bindingService.start();
        bot.start();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Shutting down Seira");
        bot.stop();
        bindingService.close();
        executors.close();
        bot.close();
        DailyLuck.saveToFile();
        BotStat.shutdown();
    }
}
