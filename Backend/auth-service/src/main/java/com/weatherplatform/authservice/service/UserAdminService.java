package com.weatherplatform.authservice.service;

import com.weatherplatform.authservice.dto.UserResponse;
import com.weatherplatform.authservice.entity.User;
import com.weatherplatform.authservice.entity.UserRole;
import com.weatherplatform.authservice.exception.BusinessRuleException;
import com.weatherplatform.authservice.exception.ResourceNotFoundException;
import com.weatherplatform.authservice.mapper.UserMapper;
import com.weatherplatform.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Administrator-only account management.
 *
 * Two invariants are enforced here rather than left to the UI, because the UI
 * is not a security boundary:
 * <ul>
 *   <li>the platform must always retain at least one administrator, and</li>
 *   <li>an administrator cannot demote or delete themselves</li>
 * </ul>
 * Either would otherwise be a one-click way to lock everyone out of the
 * console permanently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAdminService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public List<UserResponse> listUsers(String role) {
        List<User> users = (role == null || role.isBlank())
                ? userRepository.findAllByOrderByCreatedAtDesc()
                : userRepository.findByRoleOrderByCreatedAtDesc(parseRole(role));

        return users.stream().map(userMapper::toResponse).toList();
    }

    @Transactional
    public UserResponse updateRole(Long userId, UserRole newRole, Long actingUserId) {
        User user = findOrThrow(userId);

        if (user.getId().equals(actingUserId) && newRole != UserRole.ADMIN) {
            throw new BusinessRuleException(
                    "You cannot remove your own administrator role. Ask another administrator to do it.");
        }

        if (user.getRole() == UserRole.ADMIN && newRole != UserRole.ADMIN) {
            requireAnotherAdminRemains("demote");
        }

        user.setRole(newRole);
        User saved = userRepository.save(user);

        log.info("User {} role changed to {} by user {}", userId, newRole, actingUserId);
        return userMapper.toResponse(saved);
    }

    @Transactional
    public void deleteUser(Long userId, Long actingUserId) {
        User user = findOrThrow(userId);

        if (user.getId().equals(actingUserId)) {
            throw new BusinessRuleException("You cannot delete your own account.");
        }

        if (user.getRole() == UserRole.ADMIN) {
            requireAnotherAdminRemains("delete");
        }

        userRepository.delete(user);
        log.info("User {} deleted by user {}", userId, actingUserId);
    }

    private void requireAnotherAdminRemains(String action) {
        if (userRepository.countByRole(UserRole.ADMIN) <= 1) {
            throw new BusinessRuleException(
                    "Cannot " + action + " the last administrator — the console would become unreachable.");
        }
    }

    private User findOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + userId));
    }

    private UserRole parseRole(String role) {
        try {
            return UserRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role '" + role + "'. Expected ADMIN or USER.");
        }
    }
}
