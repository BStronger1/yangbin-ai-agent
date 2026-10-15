package com.yangbin.yangbinaiagent.config;
import jakarta.servlet.FilterChain;
import com.yangbin.yangbinaiagent.security.UserContext;
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
        if(request.getMethod().equals("GET") && request.getRequestURI().equals(request.getContextPath()+"/health")) {
            chain.doFilter(request,response);return;
        }
        UserContext.User user;
        if(token.isBlank()) {
            // Local development only: production sets an internal proxy token.
            user=UserContext.ADMIN;
        } else {
            String supplied=request.getHeader("X-Internal-Token");
            if(supplied==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {response.sendError(401);return;}
            try {user=new UserContext.User(request.getHeader("X-User-Id"),request.getHeader("X-User-Role"));}
            catch(Exception e){response.sendError(401);return;}
        }
        try(var ignored=UserContext.open(user)){chain.doFilter(request,response);}
    }
}
