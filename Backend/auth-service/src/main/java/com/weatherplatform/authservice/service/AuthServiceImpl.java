package com.weatherplatform.authservice.service;

import com.weatherplatform.authservice.dto.LoginRequest;
import com.weatherplatform.authservice.dto.LoginResponse;
import com.weatherplatform.authservice.dto.RegisterRequest;
import com.weatherplatform.authservice.dto.UserResponse;
import com.weatherplatform.authservice.entity.User;
import com.weatherplatform.authservice.entity.UserRole;
import com.weatherplatform.authservice.exception.BadCredentialsException;
import com.weatherplatform.authservice.exception.DuplicateResourceException;
import com.weatherplatform.authservice.exception.ResourceNotFoundException;
import com.weatherplatform.authservice.mapper.UserMapper;
import com.weatherplatform.authservice.repository.UserRepository;
import com.weatherplatform.authservice.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        log.info("Registering new user with email: {}", request.getEmail());

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("User with email '" + request.getEmail() + "' already exists");
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.USER)
                .build();

        User saved = userRepository.save(user);
        log.info("User registered successfully with ID: {}", saved.getId());
        return userMapper.toResponse(saved);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        log.info("Login attempt for email: {}", request.getEmail());

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    log.warn("Login failed — email not found: {}", request.getEmail());
                    return new BadCredentialsException("Invalid email or password");
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("Login failed — invalid password for email: {}", request.getEmail());
            throw new BadCredentialsException("Invalid email or password");
        }

        String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        log.info("Login successful for user ID: {}", user.getId());

        return LoginResponse.builder()
                .token(token)
                .type("Bearer")
                .expiresIn(jwtTokenProvider.getExpirationMs())
                .user(userMapper.toResponse(user))
                .build();
    }

    @Override
    public UserResponse getCurrentUser(Long userId) {
        log.debug("Fetching current user profile for ID: {}", userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + userId));
        return userMapper.toResponse(user);
    }
}
