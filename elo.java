package com.mycorp.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Fetches / caches a 5-min bearer token from the Token-Manager. */
@Component
public class ProxyTokenProvider {

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper json = new ObjectMapper();

    private final String url, cid, csec, user, pass;
    private final AtomicReference<String>  token  = new AtomicReference<>();
    private final AtomicReference<Instant> valid  = new AtomicReference<>(Instant.EPOCH);

    public ProxyTokenProvider(
            @org.springframework.beans.factory.annotation.Value("${token.url}")          String url,
            @org.springframework.beans.factory.annotation.Value("${token.client-id}")    String cid,
            @org.springframework.beans.factory.annotation.Value("${token.client-secret}") String csec,
            @org.springframework.beans.factory.annotation.Value("${token.username}")     String user,
            @org.springframework.beans.factory.annotation.Value("${token.password}")     String pass) {
        this.url  = url;   this.cid = cid;   this.csec = csec;   this.user = user;   this.pass = pass;
    }

    @PostConstruct void eager() { refresh(); }

    /** Returns a still-valid token, refreshing if <10 s from expiry. */
    public String current() {
        if (Instant.now().isAfter(valid.get().minusSeconds(10))) {
            synchronized (this) {
                if (Instant.now().isAfter(valid.get().minusSeconds(10))) refresh();
            }
        }
        return token.get();
    }

    /* ---------------- private helpers ---------------- */

    @SuppressWarnings("unchecked")
    private void refresh() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String,String> f = new LinkedMultiValueMap<>();
        f.set("grant_type",    "password");
        f.set("client_id",     cid);
        f.set("client_secret", csec);
        f.set("username",      user);
        f.set("password",      pass);

        Map<String,Object> rsp = rest.postForObject(url, new HttpEntity<>(f, h), Map.class);

        String access = (String) rsp.get("access_token");
        int    ttl    = ((Number) rsp.getOrDefault("expires_in", 300)).intValue();

        token.set(access);
        valid.set(Instant.now().plusSeconds(ttl));
    }
}
3 OpenAiConfig.java
java
Copy
Edit
package com.mycorp.config;

import com.mycorp.proxy.ProxyTokenProvider;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import okhttp3.Authenticator;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.Proxy;

@Configuration
public class OpenAiConfig {

    @Bean
    public OkHttpClient okHttp(@Value("${proxy.host}") String host,
                               ProxyTokenProvider tokens) {

        Proxy pxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, 443));

        Authenticator auth = (route, resp) -> {
            if (resp.request().header("Proxy-Authorization") != null) return null;
            String bearer = "Bearer " + tokens.current();
            return resp.request().newBuilder()
                       .header("Proxy-Authorization", bearer)
                       .build();
        };                                         // OkHttp’s documented hook :contentReference[oaicite:0]{index=0}

        return new OkHttpClient.Builder()
                .proxy(pxy)
                .proxyAuthenticator(auth)
                .build();
    }

    @Bean
    public OpenAIClient openai(@Value("${openai.base-url:}") String baseUrl,
                               OkHttpClient ok) {

        OpenAIOkHttpClient.Builder sdk = OpenAIOkHttpClient.builder()
                .apiKey("ignored")          // builder needs a placeholder  :contentReference[oaicite:1]{index=1}
                .httpClient(ok);

        if (!baseUrl.isBlank()) sdk.baseUrl(baseUrl);
        return sdk.build();
    }
}
Use it anywhere
java
Copy
Edit
@Service
@RequiredArgsConstructor
class LlmService {
    private final OpenAIClient openai;

    String ask(String msg) {
        return openai.chat().completions()
                .create(r -> r.model("gpt-4o-mini")
                              .addMessage(m -> m.role("user").content(msg)))
                .choices().get(0).message().content();
    }
}
// src/main/java/com/mycorp/proxy/ProxyTokenProvider.java
package com.mycorp.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.http.*;
import org.springframework.util.*;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConfigurationProperties(prefix = "token")   // <-- binds token.* keys
@Getter @Setter                               // Lombok generates setters for Spring
public class ProxyTokenProvider {

    /* -------- properties injected from application.properties -------- */
    private String url;
    private String clientId;
    private String clientSecret;
    private String username;
    private String password;

    /* -------- runtime state (not bound) -------- */
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String>  token  = new AtomicReference<>();
    private final AtomicReference<Instant> valid  = new AtomicReference<>(Instant.EPOCH);

    @PostConstruct
    void eager() { refresh(); }

    public String current() {
        if (Instant.now().isAfter(valid.get().minusSeconds(10))) {
            synchronized (this) {
                if (Instant.now().isAfter(valid.get().minusSeconds(10))) refresh();
            }
        }
        return token.get();
    }

    /* -------- private helpers -------- */
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

        Map<String,Object> rsp = rest.postForObject(url, new HttpEntity<>(f, h), Map.class);

        token.set((String) rsp.get("access_token"));
        int ttl = ((Number) rsp.getOrDefault("expires_in", 300)).intValue();
        valid.set(Instant.now().plusSeconds(ttl));
    }
}
@Configuration
public class OpenAiConfig {

    @Bean
    public OpenAIClient openai(ProxyTokenProvider tokens,
                               @Value("${proxy.host}") String proxyHost,
                               @Value("${openai.base-url:}") String baseUrl) {

        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                                new InetSocketAddress(proxyHost, 443));

        Authenticator auth = (route, resp) -> {
            if (resp.request().header("Proxy-Authorization") != null) return null;
            String bearer = "Bearer " + tokens.current();
            return resp.request().newBuilder()
                       .header("Proxy-Authorization", bearer)
                       .build();
        };

        OpenAIOkHttpClient.Builder sdk = OpenAIOkHttpClient.builder()
                .apiKey("ignored")       // SDK needs a placeholder
                .proxy(proxy)
                .proxyAuthenticator(auth);

        if (!baseUrl.isBlank()) sdk.baseUrl(baseUrl);
        return sdk.build();
    }
}

