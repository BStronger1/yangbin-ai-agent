package com.yangbin.yangbinaiagent.config;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalApiTokenFilter extends OncePerRequestFilter {
    private final String token;
    public InternalApiTokenFilter(@Value("${app.internal-api-token:}") String token){this.token=token;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if(token.isBlank() || (request.getMethod().equals("GET") && request.getRequestURI().equals(request.getContextPath()+"/health"))) {
            chain.doFilter(request,response);return;
        }
        String supplied=request.getHeader("X-Internal-Token");
        if(supplied==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(401);return;
        }
        chain.doFilter(request,response);
    }
}
