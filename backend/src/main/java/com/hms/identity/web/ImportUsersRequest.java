package com.hms.identity.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Bulk import (UI issue U4): the rows of the administrator's CSV, parsed by the client. */
record ImportUsersRequest(@NotNull @Size(min = 1, max = 200) List<@NotNull @Valid CreateUserRequest> users) {
}
