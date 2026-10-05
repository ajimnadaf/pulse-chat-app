package com.pulse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Component
class JwtService {
  private final SecretKey key; private final long hours;
  JwtService(@Value("${app.jwt.secret}") String s, @Value("${app.jwt.hours}") long h){ key=Keys.hmacShaKeyFor(s.getBytes(StandardCharsets.UTF_8)); hours=h; }
  String create(String user){ return Jwts.builder().subject(user).expiration(new Date(System.currentTimeMillis()+hours*3600_000)).signWith(key).compact(); }
  String parse(String t){ try { return Jwts.parser().verifyWith(key).build().parseSignedClaims(t).getPayload().getSubject(); } catch(Exception e){ return null; } }
}

@Component
class JwtFilter extends OncePerRequestFilter {
  private final JwtService jwt; JwtFilter(JwtService j){ jwt=j; }
  @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    String h = req.getHeader("Authorization");
    if (h != null && h.startsWith("Bearer ")) {
      String user = jwt.parse(h.substring(7));
      if (user != null) SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
    chain.doFilter(req, res);
  }
}

@Configuration
class SecurityConfig {
  @Bean SecurityFilterChain chain(HttpSecurity http, JwtFilter f) throws Exception {
    http.csrf(c -> c.disable()).cors(c -> {})
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .headers(h -> h.frameOptions(fo -> fo.disable()))
      .authorizeHttpRequests(a -> a.requestMatchers("/api/auth/**","/h2-console/**","/ws/**","/api/files/**").permitAll().anyRequest().authenticated())
      .exceptionHandling(e -> e.authenticationEntryPoint((rq, rs, ex) -> rs.sendError(401)))
      .addFilterBefore(f, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
  @Bean PasswordEncoder encoder(){ return new BCryptPasswordEncoder(); }
}
