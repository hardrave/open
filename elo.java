@Configuration
public class OpenAiConfig {

    @Bean
    public OpenAIClient baseOpenAi(@Value("${proxy.host}") String proxyHost,
                                   @Value("${openai.base-url:}") String baseUrl) {

        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                                new InetSocketAddress(proxyHost, 443));

        OpenAIOkHttpClient.Builder b = OpenAIOkHttpClient.builder()
                .apiKey("ignored")            // placeholder
                .proxy(proxy);                // ← jedyne ustawienie proxy :contentReference[oaicite:0]{index=0}

        if (!baseUrl.isBlank()) b.baseUrl(baseUrl);
        return b.build();
    }
}

@Component
@RequiredArgsConstructor
public class OpenAiProxyClient {

    private final OpenAIClient base;          // bean z OpenAiConfig
    private final ProxyTokenProvider tokens;  // świeży Bearer

    /** Udostępnia klienta z aktualnym nagłówkiem Proxy-Authorization. */
    public <T> T withToken(Function<OpenAIClient,T> call) {
        OpenAIClient scoped = base.withOptions(opt ->
            opt.putHeader("Proxy-Authorization", "Bearer " + tokens.current())
        );                                    // withOptions istnieje od 2.x :contentReference[oaicite:1]{index=1}
        return call.apply(scoped);
    }
}

@Service
@RequiredArgsConstructor
class LlmService {

    private final OpenAiProxyClient proxy;

    String chat(String userMsg) {
        return proxy.withToken(client ->
            client.chat().completions()
                  .create(r -> r
                      .model("gpt-4o-mini")
                      .addMessage(m -> m.role("user").content(userMsg)))
        ).choices().get(0).message().content();
    }
}
