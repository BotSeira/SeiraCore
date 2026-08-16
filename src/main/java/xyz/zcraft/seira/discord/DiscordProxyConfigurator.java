package xyz.zcraft.seira.discord;

import com.neovisionaries.ws.client.ProxySettings;
import com.neovisionaries.ws.client.WebSocketFactory;
import net.dv8tion.jda.api.JDABuilder;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import xyz.zcraft.seira.config.DiscordProxyConfig;

import java.net.InetSocketAddress;
import java.net.Proxy;

final class DiscordProxyConfigurator {
    private static final int CONNECTION_TIMEOUT_MILLIS = 15_000;

    private DiscordProxyConfigurator() {
    }

    static void apply(JDABuilder builder, DiscordProxyConfig config) {
        if (!config.enabled()) return;
        builder.setWebsocketFactory(createGatewayFactory(config));
        builder.setHttpClientBuilder(createHttpClientBuilder(config));
    }

    static WebSocketFactory createGatewayFactory(DiscordProxyConfig config) {
        WebSocketFactory factory = new WebSocketFactory().setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        ProxySettings settings = factory.getProxySettings().setHost(config.host()).setPort(config.port());
        if (hasUsername(config)) settings.setCredentials(config.username(), config.password());
        return factory;
    }

    static OkHttpClient.Builder createHttpClientBuilder(DiscordProxyConfig config) {
        Proxy proxy = new Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(config.host(), config.port())
        );
        OkHttpClient.Builder builder = new OkHttpClient.Builder().proxy(proxy);
        if (hasUsername(config)) {
            String credential = Credentials.basic(config.username(), config.password());
            builder.proxyAuthenticator((route, response) -> {
                if (credential.equals(response.request().header("Proxy-Authorization"))) return null;
                return response.request().newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build();
            });
        }
        return builder;
    }

    private static boolean hasUsername(DiscordProxyConfig config) {
        return !config.username().isBlank();
    }
}
