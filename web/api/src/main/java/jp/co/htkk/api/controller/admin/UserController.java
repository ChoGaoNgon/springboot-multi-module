package jp.co.htkk.api.controller.admin;

import com.github.pagehelper.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jp.co.htkk.api.controller.AbstractBaseController;
import jp.co.htkk.business.service.admin.UserService;
import jp.co.htkk.dto.admin.user.request.UserCreateRequest;
import jp.co.htkk.dto.admin.user.response.UserListResponse;
import jp.co.htkk.dto.admin.user.response.UserResponse;
import jp.co.htkk.entity.generator.User;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.InvocationTargetException;

@Tag(name = "UserController", description = "User CRUD API")
@RestController
@AllArgsConstructor
public class UserController extends AbstractBaseController {

    private final UserService userService;

    @Operation(summary = "Create a user")
    @PreAuthorize("hasAuthority('USER_WRITE')")
    @PostMapping(value = "${endpoint.admin.user.create}")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserCreateRequest request, BindingResult bindingResult)
            throws BindException, InvocationTargetException, IllegalAccessException, InstantiationException {
        User user = (User) bindingResultWithValidate(bindingResult, request, userService::createUser);
        return ResponseEntity.status(HttpStatus.OK).body(UserResponse.of(user));
    }

    @Operation(summary = "Get a user by id")
    @PreAuthorize("hasAuthority('USER_READ')")
    @GetMapping(value = "${endpoint.admin.user.getById}")
    public ResponseEntity<UserResponse> getById(@PathVariable("userId") Long userId) {
        return ResponseEntity.status(HttpStatus.OK).body(UserResponse.of(userService.getUser(userId)));
    }

    @Operation(summary = "List users")
    @PreAuthorize("hasAuthority('USER_READ')")
    @GetMapping(value = "${endpoint.admin.user.list}")
    public ResponseEntity<UserListResponse> list(
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        Page<User> page = userService.listUsers(pageNum, pageSize);
        return ResponseEntity.status(HttpStatus.OK).body(UserListResponse.of(page));
    }
}
