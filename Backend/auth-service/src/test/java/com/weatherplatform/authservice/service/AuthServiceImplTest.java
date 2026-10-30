package com.weatherplatform.authservice.service;

import com.weatherplatform.authservice.dto.LoginRequest;
import com.weatherplatform.authservice.dto.LoginResponse;
import com.weatherplatform.authservice.dto.RegisterRequest;
import com.weatherplatform.authservice.dto.UserResponse;
import com.weatherplatform.authservice.entity.User;
import com.weatherplatform.authservice.entity.UserRole;
import com.weatherplatform.authservice.exception.BadCredentialsException;
import com.weatherplatform.authservice.exception.DuplicateResourceException;
import com.weatherplatform.authservice.mapper.UserMapper;
import com.weatherplatform.authservice.repository.UserRepository;
import com.weatherplatform.authservice.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private AuthServiceImpl authService;

    private RegisterRequest registerRequest(String email) {
        RegisterRequest request = new RegisterRequest();
        request.setName("Test User");
        request.setEmail(email);
        request.setPassword("Passw0rd!");
        return request;
    }

    @Test
    void register_Success() {
        RegisterRequest request = registerRequest("test@test.com");

        when(userRepository.existsByEmail("test@test.com")).thenReturn(false);
        when(passwordEncoder.encode("Passw0rd!")).thenReturn("encoded");

        User savedUser = User.builder()
                .id(1L)
                .name("Test User")
                .email("test@test.com")
                .passwordHash("encoded")
                .role(UserRole.USER)
                .build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        UserResponse mapped = UserResponse.builder()
                .id(1L)
                .name("Test User")
                .email("test@test.com")
                .role(UserRole.USER)
                .build();
        when(userMapper.toResponse(savedUser)).thenReturn(mapped);

        UserResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals("test@test.com", response.getEmail());
        assertEquals(UserRole.USER, response.getRole());
    }

    @Test
    void register_EmailExists_ShouldThrowException() {
        RegisterRequest request = registerRequest("exists@test.com");

        when(userRepository.existsByEmail("exists@test.com")).thenReturn(true);

        assertThrows(DuplicateResourceException.class, () -> authService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_Success() {
        LoginRequest request = new LoginRequest();
        request.setEmail("test@test.com");
        request.setPassword("Passw0rd!");

        User user = User.builder()
                .id(1L)
                .name("Test User")
                .email("test@test.com")
                .passwordHash("encoded")
                .role(UserRole.USER)
                .build();

        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Passw0rd!", "encoded")).thenReturn(true);
        when(jwtTokenProvider.generateToken(1L, "test@test.com", "USER")).thenReturn("mock-token");
        when(jwtTokenProvider.getExpirationMs()).thenReturn(3_600_000L);
        when(userMapper.toResponse(user)).thenReturn(UserResponse.builder()
                .id(1L)
                .email("test@test.com")
                .role(UserRole.USER)
                .build());

        LoginResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("mock-token", response.getToken());
        assertEquals("test@test.com", response.getUser().getEmail());
    }

    @Test
    void login_InvalidCredentials_ShouldThrowException() {
        LoginRequest request = new LoginRequest();
        request.setEmail("test@test.com");
        request.setPassword("wrong");

        User user = User.builder()
                .email("test@test.com")
                .passwordHash("encoded")
                .role(UserRole.USER)
                .build();

        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThrows(BadCredentialsException.class, () -> authService.login(request));
    }
}
