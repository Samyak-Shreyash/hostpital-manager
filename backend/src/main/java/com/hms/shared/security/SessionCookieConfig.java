package com.hms.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * The session cookie from api-design.md section 1: {@code HMSSESSION}, HttpOnly, Secure, SameSite=Lax.
 * Declared explicitly because Spring Session does not pick up {@code server.servlet.session.cookie.*}
 * here; AuthenticationIT asserts the attributes.
 */
@Configuration(proxyBeanMethods = false)
class SessionCookieConfig {

    @Bean
    CookieSerializer cookieSerializer(@Value("${hms.session.cookie-secure:true}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("HMSSESSION");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure); // false only for plain-http local dev
        serializer.setSameSite("Lax");
        serializer.setCookiePath("/");
        return serializer;
    }
}
