package com.hms.identity.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hms.identity.application.UserView;
import com.hms.identity.application.UserWithTemporaryPassword;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * api-design.md section 3 user. {@code temporaryPassword} is present only in the create and reset-password
 * responses (shown once); {@code toString} hides it so it cannot reach a log through this object.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record UserResponse(UUID id, String email, String fullName, String status, boolean mustChangePassword,
        List<String> roleCodes, long version, Instant createdAt, Instant updatedAt, String temporaryPassword) {

    static UserResponse of(UserView user) {
        return new UserResponse(user.id(), user.email(), user.fullName(), user.status(), user.mustChangePassword(),
                user.roleCodes(), user.version(), user.createdAt(), user.updatedAt(), null);
    }

    static UserResponse of(UserWithTemporaryPassword created) {
        UserView user = created.user();
        return new UserResponse(user.id(), user.email(), user.fullName(), user.status(), user.mustChangePassword(),
                user.roleCodes(), user.version(), user.createdAt(), user.updatedAt(), created.temporaryPassword());
    }

    @Override
    public String toString() {
        return "UserResponse[id=" + id + ", temporaryPassword=" + (temporaryPassword == null ? "-" : "***") + "]";
    }
}
