package com.weatherplatform.authservice.controller;

import com.weatherplatform.authservice.dto.ApiResponse;
import com.weatherplatform.authservice.dto.RoleUpdateRequest;
import com.weatherplatform.authservice.dto.UserResponse;
import com.weatherplatform.authservice.service.UserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * User administration for the operator console.
 *
 * Entirely ADMIN-only. Responses never include the password hash — only the
 * {@link UserResponse} projection is ever returned.
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "User Administration", description = "Administrator-only user management")
public class UserController {

    private final UserAdminService userAdminService;

    @Operation(summary = "List Users", description = "All accounts, optionally filtered by role")
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserResponse>>> listUsers(
            @RequestParam(required = false) String role) {
        List<UserResponse> users = userAdminService.listUsers(role);
        return ResponseEntity.ok(ApiResponse.<List<UserResponse>>builder()
                .success(true)
                .message("Users retrieved successfully")
                .data(users)
                .path("/api/users")
                .build());
    }

    @Operation(summary = "Change Role", description = "Promote or demote an account between USER and ADMIN")
    @PatchMapping("/{userId}/role")
    public ResponseEntity<ApiResponse<UserResponse>> updateRole(
            @PathVariable Long userId,
            @Valid @RequestBody RoleUpdateRequest request,
            Authentication authentication) {

        Long actingUserId = (Long) authentication.getPrincipal();
        UserResponse updated = userAdminService.updateRole(userId, request.getRole(), actingUserId);

        return ResponseEntity.ok(ApiResponse.<UserResponse>builder()
                .success(true)
                .message("User role updated successfully")
                .data(updated)
                .path("/api/users/" + userId + "/role")
                .build());
    }

    @Operation(summary = "Delete User", description = "Remove an account")
    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @PathVariable Long userId,
            Authentication authentication) {

        Long actingUserId = (Long) authentication.getPrincipal();
        userAdminService.deleteUser(userId, actingUserId);

        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("User deleted successfully")
                .path("/api/users/" + userId)
                .build());
    }
}
