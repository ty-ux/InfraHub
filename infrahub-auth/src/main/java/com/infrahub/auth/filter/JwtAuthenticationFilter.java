package com.infrahub.auth.filter;

import com.infrahub.auth.service.JwtTokenProvider;
import com.infrahub.common.context.UserContext;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.system.service.SysUserService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.stream.Collectors;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final SysUserService sysUserService;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, SysUserService sysUserService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.sysUserService = sysUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            try {
                UserContext identity = jwtTokenProvider.parseToken(token);
                var user = sysUserService.getById(identity.getUserId());
                if (user == null || !"ENABLED".equals(user.getStatus()) || !user.getUsername().equals(identity.getUsername())) {
                    throw new JwtException("Inactive account");
                }
                UserContext userContext = sysUserService.getActiveContext(user);
                UserContextHolder.set(userContext);
                MDC.put("userId", String.valueOf(userContext.getUserId()));
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userContext.getUsername(),
                        null,
                        userContext.getAuthorities().stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet()));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException ignored) {
                SecurityContextHolder.clearContext();
                UserContextHolder.clear();
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            UserContextHolder.clear();
        }
    }
}
