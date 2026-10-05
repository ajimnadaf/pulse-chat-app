package com.pulse;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

record Creds(@NotBlank @Size(min=3,max=30) String username, @NotBlank @Size(min=6,max=100) String password) {}

@RestController @RequestMapping("/api/auth") @Validated
class AuthController {
  private final UserRepo users; private final PasswordEncoder enc; private final JwtService jwt;
  AuthController(UserRepo u, PasswordEncoder e, JwtService j){ users=u; enc=e; jwt=j; }
  @PostMapping("/register") Map<String,String> register(@RequestBody @jakarta.validation.Valid Creds c){
    if (users.findByUsername(c.username()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already taken");
    AppUser u = new AppUser(); u.username = c.username(); u.passwordHash = enc.encode(c.password()); users.save(u);
    return Map.of("token", jwt.create(u.username), "username", u.username);
  }
  @PostMapping("/login") Map<String,String> login(@RequestBody @jakarta.validation.Valid Creds c){
    AppUser u = users.findByUsername(c.username()).filter(x -> enc.matches(c.password(), x.passwordHash))
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong username or password"));
    return Map.of("token", jwt.create(u.username), "username", u.username);
  }
}

@RestControllerAdvice
class Errors {
  @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Map<String,String>> rse(ResponseStatusException e){
    return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason() == null ? "Error" : e.getReason())); }
  @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class) ResponseEntity<Map<String,String>> val(org.springframework.web.bind.MethodArgumentNotValidException e){
    var f = e.getBindingResult().getFieldError();
    return ResponseEntity.badRequest().body(Map.of("error", f == null ? "Invalid input" : f.getField() + ": " + f.getDefaultMessage())); }
}
