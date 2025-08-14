1 ProxyTokenProvider.java
(identyczny do wcześniejszego – tylko importy)

java
Copy
Edit
package com.mycorp.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConfigurationProperties(prefix = "token")
public class ProxyTokenProvider {

    private String url;
    private String clientId;
    private String clientSecret;
    private String username;
    private String password;

    private final RestTemplate rest = new RestTemplate();
    private final AtomicReference<String>  token  = new AtomicReference<>();
    private final AtomicReference<Instant> valid  = new AtomicReference<>(Instant.EPOCH);

    // --- setters wymagane przez ConfigurationProperties ---
    public void setUrl(String url) { this.url = url; }
    public void setClientId(String id) { this.clientId = id; }
    public void setClientSecret(String s) { this.clientSecret = s; }
    public void setUsername(String u) { this.username = u; }
    public void setPassword(String p) { this.password = p; }

    @PostConstruct
    void init() { refresh(); }

    public String current() {
        if (Instant.now().isAfter(valid.get().minusSeconds(10))) {
            synchronized (this) {
                if (Instant.now().isAfter(valid.get().minusSeconds(10))) refresh();
            }
        }
        return token.get();
    }

    @SuppressWarnings("unchecked")
    private void refresh() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String,String> f = new LinkedMultiValueMap<>();
        f.set("grant_type",    "password");
        f.set("client_id",     clientId);
        f.set("client_secret", clientSecret);
        f.set("username",      username);
        f.set("password",      password);

        Map<String,Object> rsp =
            rest.postForObject(url, new HttpEntity<>(f, h), Map.class);

        String access = (String) rsp.get("access_token");
        int    ttl    = ((Number) rsp.getOrDefault("expires_in", 300)).intValue();

        token.set(access);
        valid.set(Instant.now().plusSeconds(ttl));
    }
}
2 OpenAiConfig.java
java
Copy
Edit
package com.mycorp.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.Proxy;

@Configuration
public class OpenAiConfig {

    @Bean
    public OpenAIClient openAiBase(
            @Value("${proxy.host}") String proxyHost,
            @Value("${openai.base-url:}") String baseUrl) {

        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                                new InetSocketAddress(proxyHost, 443));

        OpenAIOkHttpClient.Builder b = OpenAIOkHttpClient.builder()
                .apiKey("ignored")     // placeholder
                .proxy(proxy);

        if (!baseUrl.isBlank()) b.baseUrl(baseUrl);
        return b.build();
    }
}
3 OpenAiProxyClient.java
java
Copy
Edit
package com.mycorp.proxy;

import com.openai.client.OpenAIClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class OpenAiProxyClient {

    private final OpenAIClient       baseClient;
    private final ProxyTokenProvider tokens;

    @Autowired
    public OpenAiProxyClient(OpenAIClient baseClient,
                             ProxyTokenProvider tokens) {
        this.baseClient = baseClient;
        this.tokens     = tokens;
    }

    public <T> T withToken(Function<OpenAIClient,T> callback) {
        OpenAIClient scoped = baseClient.withOptions(opts ->
            opts.putHeader("Proxy-Authorization", "Bearer " + tokens.current())
        );
        return callback.apply(scoped);
    }
}
import jest java.util.function.Function – upewnij się, że
nie masz com.google.common.base.Function ani innej o tej nazwie.

4 LlmService.java
java
Copy
Edit
package com.mycorp.service;

import com.mycorp.proxy.OpenAiProxyClient;
import com.openai.client.chat.ChatCompletion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LlmService {

    private final OpenAiProxyClient proxy;

    @Autowired
    public LlmService(OpenAiProxyClient proxy) {
        this.proxy = proxy;
    }

    public String chat(String userMsg) {

        // ——— rozbijamy na dwie linie, żeby kompilator łatwiej wywnioskował typ —
        ChatCompletion completion = proxy.withToken(
            (OpenAIClient c) -> c.chat().completions()
                                  .create(r -> r
                                      .model("gpt-4o-mini")
                                      .addMessage(m -> m.role("user")
                                                        .content(userMsg)))
        );

        return completion.choices().get(0).message().content();
    }
}
Uwaga: ChatCompletion to faktyczna klasa w SDK 2.12.4;
jeśli używasz innej (ChatCompletionResponse, ChatCompletionChoice,
itp.) zamień import + zmienną na właściwą.

5 Maven (najistotniejsze)
xml
Copy
Edit
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<dependency>
  <groupId>com.openai</groupId>
  <artifactId>openai-java</artifactId>
  <version>2.12.4</version>
</dependency>
Checklist – jeśli nadal widzisz “must be functional interface”
Usuń wszystkie importy z Guavy, Scal-i itp. o nazwie Function.
Musi być tylko java.util.function.Function.


    package com.mycorp.service;

import com.mycorp.proxy.ProxyTokenProvider;
import com.openai.client.OpenAIClient;
import com.openai.core.http.RequestOptions;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LlmService {

    private final OpenAIClient       openai;
    private final ProxyTokenProvider tokens;

    @Autowired
    public LlmService(OpenAIClient openai, ProxyTokenProvider tokens) {
        this.openai = openai;
        this.tokens = tokens;
    }

    public String chat(String userMsg) {

        /* 1) budujemy parametry */
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .addUserMessage(userMsg)
                .model(ChatModel.GPT_4_1)        // lub inny model
                .build();

        /* 2) dodajemy Proxy-Authorization przy tym *pojedynczym* wywołaniu */
        RequestOptions opts = RequestOptions.builder()
                .header("Proxy-Authorization", "Bearer " + tokens.current())
                .build();

        /* 3) wywołanie */
        ChatCompletion completion = openai.chat().completions().create(params, opts);

        return completion.choices().get(0).message().content();
    }
}

import com.mycorp.proxy.ProxyTokenProvider;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.*;
import org.springframework.stereotype.Service;

@Service
public class LlmService {

    private final OpenAIClient openai;           // wstrzyknięty z OpenAiConfig
    private final ProxyTokenProvider tokens;     // świeży Bearer

    public LlmService(OpenAIClient openai, ProxyTokenProvider tokens) {
        this.openai = openai;
        this.tokens = tokens;
    }

    public String chat(String userMsg) {

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .addUserMessage(userMsg)
                .model(ChatModel.GPT_4_1)
                .build();

        // per-request dokładamy Authorization dla PROXY
        OpenAIClient scoped = openai.withOptions(o ->
            o.putHeader("Proxy-Authorization", "Bearer " + tokens.current())
        );

        ChatCompletion resp = scoped.chat().completions().create(params);

        return resp.choices().get(0).message().content();
    }
}
script>
<script>
  const quill = new Quill('#editor', { theme: 'snow' });

  // If it's a NEW object, *{content} is usually empty/null, so editor starts blank.
  // Still, if there is any prefilled value (e.g., after validation error), load it:
  const hidden = document.getElementById('content');
  if (hidden.value && hidden.value.trim().length > 0) {
    // Paste existing HTML back into Quill (use with care; sanitize server-side)
    quill.clipboard.dangerouslyPasteHTML(hidden.value);
  }

  // Keep hidden field updated on submit
  document.querySelector('form').addEventListener('submit', () => {
    hidden.value = quill.root.innerHTML; // or JSON.stringify(quill.getContents()) for Delta
  });
</script>

<script>
document.addEventListener('DOMContentLoaded', function () {
  const editorEl = document.getElementById('editor');
  const hidden = document.getElementById('content');
  const form = (hidden && hidden.form) || (editorEl && editorEl.closest('form')) || document.querySelector('form');

  if (!window.Quill) { console.error('Quill not loaded'); return; }
  if (!editorEl || !hidden || !form) { console.error('Missing #editor, #content, or <form>'); return; }

  const quill = new Quill(editorEl, { theme: 'snow' });

  // preload from hidden (e.g., after validation error)
  if (hidden.value && hidden.value.trim().length > 0) {
    quill.clipboard.dangerouslyPasteHTML(hidden.value);
  }

  // ensure hidden field has up-to-date HTML on submit
  form.addEventListener('submit', function () {
    hidden.value = quill.root.innerHTML;
  });
});
</script>

